package com.codejudge.worker.engine;

import com.codejudge.api.dto.problem.JudgeCaseDTO;
import com.codejudge.api.dto.problem.JudgeInfoDTO;
import com.codejudge.api.dto.submission.Language;
import com.codejudge.api.dto.submission.SubmissionProgressMessage;
import com.codejudge.api.dto.submission.SubmissionResultMessage;
import com.codejudge.api.dto.submission.SubmissionTaskMessage;
import com.codejudge.common.constants.JudgeRedisKeys;
import com.codejudge.common.mq.MqTopics;
import com.codejudge.common.mq.RocketMQTemplate;
import com.codejudge.worker.config.LanguageProfiles;
import com.codejudge.worker.config.WorkerProperties;
import com.codejudge.worker.domain.po.CompileInfo;
import com.codejudge.worker.domain.po.JudgeResult;
import com.codejudge.worker.domain.po.JudgeTask;
import com.codejudge.worker.domain.po.Submission;
import com.codejudge.worker.mapper.CompileInfoMapper;
import com.codejudge.worker.mapper.JudgeResultMapper;
import com.codejudge.worker.mapper.WorkerJudgeTaskMapper;
import com.codejudge.worker.mapper.WorkerSubmissionMapper;
import com.codejudge.worker.mq.ProgressPublisher;
import com.codejudge.worker.metrics.JudgeE2eMetrics;
import com.codejudge.worker.sandbox.SandboxException;
import com.codejudge.worker.sandbox.SandboxExecutor;
import com.codejudge.worker.sandbox.SandboxResult;
import com.codejudge.worker.sandbox.SandboxSpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 判定引擎：编译 → 逐用例执行 → 比对 → 结果回写 → RESULT 事件。
 *
 * <p><b>verdict 判定规则</b>（单用例）：
 * <pre>
 * 内层 timeout 触发(124) 或 worker 墙钟强杀 → TLE
 * 容器 OOM(137) 或内存峰值超限            → MLE
 * 非零退出码（先于 WA 判定）               → RE
 * 输出比对（精确/浮点容差）不符            → WA
 * 其余                                     → AC
 * </pre>
 * <p><b>提交结论</b>：按 seq 顺序执行，首个非 AC 用例短路（ACM 语义）并决定提交 verdict；
 * 全部通过 → AC，score = AC 用例分值之和。
 *
 * <p><b>平台故障路径（SE）</b>：沙箱不可用 / 题目信息拉取失败等与用户代码无关的故障
 * 不产生业务 verdict，走「交还任务 → attempt+1 → RETRY 延迟重投」；
 * attempt 超限则 DEAD + 业务死信。这与用户代码的 RE/CE（终态）严格区分。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JudgeEngine {

    private static final int RC_TIMEOUT = 124;
    private static final int RC_OOM = 137;

    private final SandboxExecutor sandbox;
    private final LanguageProfiles languageProfiles;
    private final WorkerProperties properties;
    private final WorkerJudgeTaskMapper taskMapper;
    private final WorkerSubmissionMapper submissionMapper;
    private final JudgeResultMapper judgeResultMapper;
    private final CompileInfoMapper compileInfoMapper;
    private final RocketMQTemplate mqTemplate;
    private final StringRedisTemplate redis;
    /** 判题进度事件发布器（生产端限流，见 ProgressPublisher） */
    private final ProgressPublisher progressPublisher;
    /** SLO S3 度量：判题端到端时延（提交 → 终态落库），见 JudgeE2eMetrics */
    private final JudgeE2eMetrics e2eMetrics;
    /** 终态回写的事务模板：插结果行与 CAS 必须同生共死（Boot 自动装配该 Bean） */
    private final org.springframework.transaction.support.TransactionTemplate txTemplate;

    /**
     * 判题主流程。调用方保证任务已被认领（status=JUDGING 且租约归本 worker）。
     *
     * <p><b>为什么主体抽到 {@link #judgeOnce}</b>：编译产物需要在**编译容器与各用例容器之间**
     * 存活（沙箱每次都起独立的 {@code --rm} 容器），故这里按「判题任务」申请一个共享产物目录，
     * 并保证**任何出口**（正常终态、CE 早退、沙箱故障抛出）都会在 finally 里释放它。
     */
    public JudgeReport judge(JudgeTask task, Submission submission, String workerId, JudgeInfoDTO problemInfo) {
        Language language = Language.of(submission.getLanguage());
        if (language == null) {
            // 受理端已校验语言；此处防御性兜底：按平台故障重试（换镜像配置后可恢复）
            throw new SandboxException("未知语言：" + submission.getLanguage());
        }

        String image = languageProfiles.image(properties, language);
        if (!sandbox.available(image)) {
            throw new SandboxException("判题镜像不可用：" + image + "（请先执行 scripts/build-sandbox-images.py）");
        }

        int memoryMb = problemInfo.getMemoryLimitMb() == null ? 256 : problemInfo.getMemoryLimitMb();

        String artifactKey = submission.getId() + "-" + task.getId();
        try {
            return judgeOnce(task, submission, workerId, problemInfo, language, image, memoryMb, artifactKey);
        } finally {
            // 释放共享产物目录；releaseArtifactDir 幂等且不抛（缓存目录不在 finally 里漏）
            sandbox.releaseArtifactDir(artifactKey);
        }
    }

    /**
     * 判题主体：编译 → 逐用例执行。
     *
     * @param artifactKey 共享编译产物目录的键；非空时编译产物跨用例复用
     */
    private JudgeReport judgeOnce(JudgeTask task, Submission submission, String workerId,
                                  JudgeInfoDTO problemInfo, Language language, String image,
                                  int memoryMb, String artifactKey) {
        // ---------- 编译阶段 ----------
        publishStage(submission, task, SubmissionProgressMessage.ST_JUDGING, 0,
                problemInfo.getTestCases() == null ? 0 : problemInfo.getTestCases().size(),
                0, null, "正在评测，已进入判题机 " + workerId, 5, true);

        // PYTHON 无编译阶段、无产物可复用 —— 传 null 让它继续用容器内 tmpfs（少一次宿主挂载）
        String sharedWork = language.isCompiled() ? artifactKey : null;

        CompileInfo compileInfo = null;
        if (language.isCompiled()) {
            CompileOutcome compile = compileInSandbox(image, language, submission.getCode(), memoryMb, sharedWork);
            if (compile.isSandboxFailed()) {
                throw new SandboxException("编译沙箱故障：" + compile.getSandboxError());
            }
            compileInfo = saveCompileInfo(submission.getId(), compile);
            if (!compile.isSuccess()) {
                // CE 是"成功的判题"：终态 SUCCESS + verdict=CE
                publishStage(submission, task, SubmissionProgressMessage.ST_COMPILED, 0,
                        problemInfo.getTestCases() == null ? 0 : problemInfo.getTestCases().size(),
                        0, null, "编译失败（CE），未进入用例执行", 5, true);
                return finishTerminal(task, submission, workerId, Verdict.CE, 0, null, null,
                        compileInfo.getId(), List.of(), problemInfo);
            }
            publishStage(submission, task, SubmissionProgressMessage.ST_COMPILED, 0,
                    problemInfo.getTestCases() == null ? 0 : problemInfo.getTestCases().size(),
                    0, null, "编译通过，开始执行测试用例", 10, true);
        }

        // ---------- 逐用例执行 ----------
        List<JudgeCaseDTO> cases = problemInfo.getTestCases() == null ? List.of() : problemInfo.getTestCases();
        if (cases.isEmpty()) {
            // 无用例属于题目配置缺陷：任何提交都"全部通过"没有意义，按平台故障重试处理
            throw new SandboxException("题目无可执行用例，疑似配置缺陷：problemId=" + problemInfo.getProblemId());
        }

        List<JudgeResult> results = new ArrayList<>();
        int passed = 0;
        int score = 0;
        Verdict finalVerdict = Verdict.AC;
        int maxTime = 0;
        long maxMem = 0;

        for (int i = 0; i < cases.size(); i++) {
            JudgeCaseDTO testCase = cases.get(i);
            CaseOutcome outcome = runCase(image, language, testCase, memoryMb, submission.getCode(), sharedWork);
            if (outcome.getVerdict().isSystemError()) {
                throw new SandboxException("运行沙箱故障：caseId=" + testCase.getCaseId());
            }
            JudgeResult row = toResult(submission.getId(), task.getId(), outcome);
            results.add(row);
            maxTime = Math.max(maxTime, outcome.getTimeMs());
            maxMem = Math.max(maxMem, outcome.getMemKb());

            boolean failed = outcome.getVerdict() != Verdict.AC;
            if (!failed) {
                passed++;
                score += testCase.getScore() == null ? 0 : testCase.getScore();
            } else {
                // 首个非 AC 短路（ACM 语义）：verdict 由第一个失败用例决定
                finalVerdict = outcome.getVerdict();
            }

            // 进度推送：末用例与短路帧强制发送（否则客户端可能永远停在中间百分比）
            boolean last = (i == cases.size() - 1);
            publishStage(submission, task, SubmissionProgressMessage.ST_CASE_DONE, i + 1, cases.size(), passed,
                    outcome.getVerdict().name(), describeCase(outcome, i + 1, cases.size()),
                    progressPercent(i + 1, cases.size()), last || failed);
            if (failed) {
                break;
            }
        }

        return finishTerminal(task, submission, workerId, finalVerdict, score, maxTime, (int) maxMem,
                compileInfo == null ? null : compileInfo.getId(), results, problemInfo);
    }

    // ==================== 进度推送 ====================

    /**
     * 发布一条判题进度。
     *
     * <p>注意这里**只传序号、结论、耗时、内存**，绝不携带用例输入或期望输出 ——
     * 进度事件会经 MQ → WebSocket 直达提交者浏览器，任何用例内容都会构成答案泄漏。
     */
    private void publishStage(Submission submission, JudgeTask task, String stage, int caseSeq, int totalCases,
                              int passed, String verdict, String message, int progress, boolean force) {
        SubmissionProgressMessage msg = new SubmissionProgressMessage();
        msg.setStage(stage);
        msg.setCaseSeq(caseSeq);
        msg.setTotalCases(totalCases);
        msg.setPassedCount(passed);
        msg.setVerdict(verdict);
        msg.setMessage(message);
        msg.setProgress(progress);
        msg.setShortCircuited(SubmissionProgressMessage.ST_CASE_DONE.equals(stage) && caseSeq < totalCases);
        progressPublisher.publish(submission, task.getId(), msg, force);
    }

    private String describeCase(CaseOutcome outcome, int seq, int total) {
        return "第 " + seq + "/" + total + " 个用例：" + outcome.getVerdict().name()
                + "（" + outcome.getTimeMs() + "ms / " + outcome.getMemKb() + "KB）";
    }

    /** 用例数折算进度：用例执行占 90%，其余留给编译与回写 */
    private int progressPercent(int done, int total) {
        if (total <= 0) {
            return 90;
        }
        return 10 + (int) Math.round(done * 80.0 / total);
    }

    // ==================== 编译 ====================

    private CompileOutcome compileInSandbox(String image, Language language, String code, int memoryMb,
                                            String artifactKey) {
        long start = System.currentTimeMillis();
        SandboxResult result = sandbox.execute(new SandboxSpec(
                image, languageProfiles.compileCommand(language), language.getSourceFile(), code, null,
                (int) properties.getCompileTimeoutMs(), properties.getCompileMemoryMb(),
                properties.getCompileTimeoutMs() + properties.getWallClockGraceMs(),
                // 编译阶段进程数放宽：编译器自己就是重度 fork 的程序（Go 工具链尤甚），
                // 用运行期的 64 会让 Go 构建稳定失败；运行阶段仍严守 pidsLimit。
                properties.getCompilePidsLimit(), artifactKey));
        long duration = System.currentTimeMillis() - start;

        if (result.isSandboxFailed()) {
            return CompileOutcome.sandboxError(result.getSandboxError());
        }
        CompileOutcome outcome = new CompileOutcome();
        outcome.setSuccess(result.getExitCode() == 0);
        outcome.setStdoutLog(result.getStdout());
        outcome.setStderrLog(result.getStderr());
        outcome.setDurationMs((int) duration);
        if (!outcome.isSuccess() && result.getExitCode() != 0 && result.getExitCode() != 1) {
            // 编译器被信号杀死（OOM 137 等）：编译环境问题，按沙箱故障重试而非 CE
            log.warn("编译进程异常退出 rc={} image={} stderr={}", result.getExitCode(), image,
                    abbreviate(result.getStderr()));
            return CompileOutcome.sandboxError("编译进程异常退出 rc=" + result.getExitCode());
        }
        log.info("编译完成 submissionImage={} rc={} duration={}ms success={}", image, result.getExitCode(),
                duration, outcome.isSuccess());
        return outcome;
    }

    // ==================== 单用例 ====================

    private CaseOutcome runCase(String image, Language language, JudgeCaseDTO testCase, int memoryMb,
                                String code, String artifactKey) {
        int timeLimit = testCase.getTimeLimitMs() != null ? testCase.getTimeLimitMs() : 1000;
        String runCmd = languageProfiles.runCommand(language, memoryMb);

        SandboxResult result = sandbox.execute(new SandboxSpec(
                image, runCmd, language.getSourceFile(), code,
                testCase.getStdin() == null ? "" : testCase.getStdin(),
                timeLimit, memoryMb, timeLimit + properties.getWallClockGraceMs(),
                // 运行阶段用严格值：防用户代码 fork 炸弹
                properties.getPidsLimit(), artifactKey));

        CaseOutcome outcome = new CaseOutcome();
        outcome.setCaseId(testCase.getCaseId());
        outcome.setSeq(testCase.getSeq());

        if (result.isSandboxFailed()) {
            // 沙箱故障映射为 SE，由上层走平台重试路径
            outcome.setVerdict(Verdict.SE);
            outcome.setOutputDigest(result.getSandboxError());
            return outcome;
        }

        outcome.setTimeMs(result.getTimeMs());
        outcome.setMemKb(result.getMemKb());
        outcome.setOutputDigest(abbreviate(result.getStdout()));
        outcome.setStderrDigest(abbreviate(result.getStderr()));

        // 判定顺序：TLE → MLE → RE → WA → AC（PLAN §6 第 6/7/8 条）
        if (result.isTimedOut() || result.getExitCode() == RC_TIMEOUT || result.getTimeMs() > timeLimit) {
            outcome.setVerdict(Verdict.TLE);
            return outcome;
        }
        if (result.isOomKilled() || result.getMemKb() > memoryMb * 1024L) {
            outcome.setVerdict(Verdict.MLE);
            return outcome;
        }
        if (result.getExitCode() != 0) {
            outcome.setVerdict(Verdict.RE);
            return outcome;
        }
        boolean matched = compare(result.getStdout(), testCase.getExpectedStdout(), testCase.getJudgeMode());
        outcome.setVerdict(matched ? Verdict.AC : Verdict.WA);
        return outcome;
    }

    /** 输出比对：0 精确（trim 归一）/ 1 浮点容差（1e-6）/ 2 特判（P3 以精确兜底，特判脚本 P5 引入） */
    private boolean compare(String actual, String expected, Integer judgeMode) {
        int mode = judgeMode == null ? 0 : judgeMode;
        String a = actual == null ? "" : actual.strip();
        String e = expected == null ? "" : expected.strip();
        if (mode == 1) {
            return FloatComparison.fuzzyEqual(a, e);
        }
        return a.equals(e);
    }

    // ==================== 回写与事件 ====================

    /** 终态回写：judge_result 批量落库 + submission/judge_task CAS 终态 + RESULT 事件 */
    private JudgeReport finishTerminal(JudgeTask task, Submission submission, String workerId,
                                       Verdict verdict, int score, Integer timeMs, Integer memKb,
                                       Long compileInfoId, List<JudgeResult> results, JudgeInfoDTO problemInfo) {
        // 历史实现先插结果行、后做 CAS 且无事务：CAS 失败（任务已被接管/重判）时已插入的行
        // 成为脏行，自动重试/故障转移也不清理 → 详情页出现重复用例记录。
        // 现在把「CAS + 插结果」放进同一事务并先 CAS：失败则整体回滚，一个脏行都不会留下。
        Boolean committed = txTemplate.execute(tx -> {
            int submitUpdated = submissionMapper.finishSuccess(submission.getId(), verdict.name(),
                    score, timeMs, memKb, compileInfoId);
            int taskUpdated = taskMapper.markSuccess(task.getId(), workerId);
            if (submitUpdated != 1 || taskUpdated != 1) {
                // CAS 失败：说明任务在执行期间被接管/重判 —— 放弃本次结果，避免脏写
                log.warn("终态 CAS 失败（任务已被接管或重判），丢弃结果 taskId={} submissionId={} rows=({},{})",
                        task.getId(), submission.getId(), submitUpdated, taskUpdated);
                return Boolean.FALSE;
            }
            for (JudgeResult row : results) {
                judgeResultMapper.insert(row);
            }
            return Boolean.TRUE;
        });
        if (!Boolean.TRUE.equals(committed)) {
            return JudgeReport.discarded();
        }
        // SLO S3 样本：终态已真实落库才计（CAS 丢弃不是终态，不计入）
        e2eMetrics.recordTerminal(submission.getSubmitTime(), verdict.name());

        // RESULT 事件发布（P4 的竞赛榜与 WS 将消费；发送失败不影响已落库终态）
        SubmissionResultMessage event = new SubmissionResultMessage();
        event.setSubmissionId(submission.getId());
        event.setTaskId(task.getId());
        event.setUserId(submission.getUserId());
        event.setProblemId(submission.getProblemId());
        event.setContestId(submission.getContestId());
        event.setVerdict(verdict.name());
        event.setScore(score);
        event.setTimeMs(timeMs);
        event.setMemoryKb(memKb);
        event.setPassedCount((int) results.stream().filter(r -> "AC".equals(r.getVerdict())).count());
        event.setTotalCount(problemInfo.getTestCases() == null ? 0 : problemInfo.getTestCases().size());
        // 提交时间必须随事件下发：竞赛榜的 ACM 罚时以「提交时刻」为准，而非判题完成时刻
        if (submission.getSubmitTime() != null) {
            event.setSubmitTimeEpochMs(submission.getSubmitTime()
                    .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
        }
        boolean ok = mqTemplate.send(MqTopics.TOPIC_JUDGE_SUBMISSION, MqTopics.Tags.SUBMISSION_RESULT, event);
        if (!ok) {
            log.warn("RESULT 事件发送失败（终态已落库，事件丢失仅影响下游指标）submissionId={}", submission.getId());
        }

        // 判题完成，从队列 ZSet 摘除（双保险：RESULT 消费端也会 ZREM）
        redis.opsForZSet().remove(JudgeRedisKeys.JUDGE_QUEUE_ZSET, String.valueOf(submission.getId()));
        // 清理进度限流状态：避免长跑 worker 的 Map 随提交数无限增长
        progressPublisher.clear(submission.getId());

        log.info("判题终态：submissionId={} taskId={} verdict={} score={} time={}ms mem={}KB passed={}",
                submission.getId(), task.getId(), verdict, score, timeMs, memKb, event.getPassedCount());
        return JudgeReport.of(verdict, score, timeMs, memKb);
    }

    /**
     * 平台故障处理：交还任务（attempt+1）或转死信。
     * 调用点：engine 抛出 SandboxException 后由 JudgeTaskHandler 捕获调用。
     *
     * @return true=已交还等待重试；false=已转死信（终态）
     */
    public boolean failTask(JudgeTask task, Submission submission, String workerId, String reason) {
        // 无论重试还是死信，都清掉进度限流状态：重试后应当能立即推送新一轮进度
        progressPublisher.clear(submission.getId());
        int attempt = task.getAttempt() == null ? 0 : task.getAttempt();
        if (attempt + 1 >= task.getMaxAttempt()) {
            int updated = taskMapper.markDead(task.getId(), workerId, reason);
            if (updated == 1) {
                submissionMapper.finishFailed(submission.getId());
                publishDlq(task, submission, reason);
                // 死信也是终态（FAILED/SE）：计入 SLO S3 样本，verdict 标签区分
                e2eMetrics.recordTerminal(submission.getSubmitTime(), "SE");
                // 死信是终态，必须把提交从待判队列摘除。
                // 不摘除的后果：该成员永远留在 ZSet 里，judge_queue_backlog 只增不减，
                // 系统空闲时也显示「有积压」，最终让积压告警彻底失真（实测已复现）。
                // 注意死信走的是 DLQ topic，不会触发 ResultEventHandler 的那次 ZREM，
                // 所以这里不能依赖"消费端兜底"。rejudge 会重新 add，摘除是安全的。
                redis.opsForZSet().remove(JudgeRedisKeys.JUDGE_QUEUE_ZSET, String.valueOf(submission.getId()));
                log.error("任务转死信：taskId={} submissionId={} reason={}", task.getId(), submission.getId(), reason);
            }
            return false;
        }
        int updated = taskMapper.handBackForRetry(task.getId(), workerId, reason);
        if (updated == 1) {
            submissionMapper.backToPending(submission.getId());
            SubmissionTaskMessage retry = new SubmissionTaskMessage();
            retry.setSubmissionId(submission.getId());
            retry.setTaskId(task.getId());
            retry.setAttempt(attempt + 1);
            retry.setSource(MqTopics.Tags.SUBMISSION_RETRY);
            retry.setReason(reason);
            // 延迟级别 2 = 5s 退避
            boolean ok = mqTemplate.send(MqTopics.TOPIC_JUDGE_SUBMISSION, MqTopics.Tags.SUBMISSION_RETRY,
                    retry, 2);
            log.warn("任务失败转重试：taskId={} submissionId={} attempt={} reason={} mqOk={}",
                    task.getId(), submission.getId(), attempt + 1, reason, ok);
            return true;
        }
        // CAS 失败：任务已被接管，无需处理
        log.warn("失败处理 CAS 失败（任务已被接管）taskId={}", task.getId());
        return false;
    }

    /** 业务死信投递（SETNX 去重，24h 窗口；失败不重试 —— judge-submission 的死信扫描兜底补投） */
    private void publishDlq(JudgeTask task, Submission submission, String reason) {
        String flag = JudgeRedisKeys.TASK_DLQ_SENT_PREFIX + task.getId();
        Boolean first = redis.opsForValue().setIfAbsent(flag, "1", Duration.ofHours(24));
        if (!Boolean.TRUE.equals(first)) {
            return;
        }
        SubmissionResultMessage msg = new SubmissionResultMessage();
        msg.setSubmissionId(submission.getId());
        msg.setTaskId(task.getId());
        msg.setUserId(submission.getUserId());
        msg.setProblemId(submission.getProblemId());
        msg.setContestId(submission.getContestId());
        msg.setVerdict(Verdict.SE.name());
        msg.setScore(0);
        msg.setPassedCount(0);
        msg.setTotalCount(0);
        boolean ok = mqTemplate.send(MqTopics.TOPIC_JUDGE_DLQ, MqTopics.Tags.SUBMISSION_DEAD, msg);
        if (!ok) {
            redis.delete(flag);
            log.error("业务死信投递失败，交由 judge-submission 死信扫描兜底 taskId={}", task.getId());
        }
    }

    private CompileInfo saveCompileInfo(Long submissionId, CompileOutcome compile) {
        CompileInfo info = new CompileInfo();
        info.setSubmissionId(submissionId);
        info.setSuccess(compile.isSuccess() ? 1 : 0);
        info.setStdoutLog(compile.getStdoutLog());
        info.setStderrLog(compile.getStderrLog());
        info.setDurationMs(compile.getDurationMs());
        compileInfoMapper.insert(info);
        return info;
    }

    private JudgeResult toResult(Long submissionId, Long taskId, CaseOutcome outcome) {
        JudgeResult row = new JudgeResult();
        row.setSubmissionId(submissionId);
        row.setTaskId(taskId);
        row.setCaseId(outcome.getCaseId());
        row.setSeq(outcome.getSeq());
        row.setVerdict(outcome.getVerdict().name());
        row.setTimeMs(outcome.getTimeMs());
        row.setMemoryKb((int) Math.min(outcome.getMemKb(), Integer.MAX_VALUE));
        row.setOutputDigest(outcome.getOutputDigest());
        row.setStderrDigest(outcome.getStderrDigest());
        return row;
    }

    /** 摘要截断：总长必须严格小于 judge_result.digest 列宽 VARCHAR(512)（含省略号后缀） */
    private String abbreviate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 460 ? s : s.substring(0, 460) + "…[截断]";
    }

    /** 判题报告（handler 日志/指标用） */
    public static final class JudgeReport {

        private final Verdict verdict;
        private final int score;
        private final Integer timeMs;
        private final Integer memKb;
        private final boolean discarded;

        private JudgeReport(Verdict verdict, int score, Integer timeMs, Integer memKb, boolean discarded) {
            this.verdict = verdict;
            this.score = score;
            this.timeMs = timeMs;
            this.memKb = memKb;
            this.discarded = discarded;
        }

        static JudgeReport of(Verdict v, int score, Integer timeMs, Integer memKb) {
            return new JudgeReport(v, score, timeMs, memKb, false);
        }

        static JudgeReport discarded() {
            return new JudgeReport(null, 0, null, null, true);
        }

        public Verdict getVerdict() {
            return verdict;
        }

        public int getScore() {
            return score;
        }

        public Integer getTimeMs() {
            return timeMs;
        }

        public Integer getMemKb() {
            return memKb;
        }

        public boolean isDiscarded() {
            return discarded;
        }
    }

    /** 浮点容差比对工具（judge_mode=1） */
    static final class FloatComparison {
        private static final double EPS = 1e-6;

        static boolean fuzzyEqual(String actual, String expected) {
            String[] a = actual.split("\\s+");
            String[] e = expected.split("\\s+");
            if (a.length != e.length) {
                return false;
            }
            for (int i = 0; i < a.length; i++) {
                try {
                    if (Math.abs(Double.parseDouble(a[i]) - Double.parseDouble(e[i])) > EPS) {
                        return false;
                    }
                } catch (NumberFormatException nfe) {
                    if (!a[i].equals(e[i])) {
                        return false;
                    }
                }
            }
            return true;
        }
    }
}
