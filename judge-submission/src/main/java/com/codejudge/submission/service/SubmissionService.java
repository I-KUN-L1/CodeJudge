package com.codejudge.submission.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.codejudge.api.client.contest.ContestClient;
import com.codejudge.api.client.problem.ProblemClient;
import com.codejudge.api.dto.contest.ContestContextDTO;
import com.codejudge.api.dto.contest.ContestSubmissionDTO;
import com.codejudge.api.dto.problem.JudgeInfoDTO;
import com.codejudge.api.dto.submission.Language;
import com.codejudge.api.dto.submission.SubmissionReviewContextDTO;
import com.codejudge.common.constants.JudgeRedisKeys;
import com.codejudge.common.domain.PageDTO;
import com.codejudge.common.exceptions.BadRequestException;
import com.codejudge.common.exceptions.BizIllegalException;
import com.codejudge.common.exceptions.ForbiddenException;
import com.codejudge.common.utils.TxSupport;
import com.codejudge.common.utils.UserContext;
import com.codejudge.submission.config.JudgeProperties;
import com.codejudge.submission.domain.dto.SubmissionFormDTO;
import com.codejudge.submission.domain.dto.SubmissionQuery;
import com.codejudge.submission.domain.po.CompileInfo;
import com.codejudge.submission.domain.po.JudgeResult;
import com.codejudge.submission.domain.po.JudgeTask;
import com.codejudge.submission.domain.po.Submission;
import com.codejudge.submission.domain.vo.CaseResultVO;
import com.codejudge.submission.domain.vo.SubmissionDetailVO;
import com.codejudge.submission.domain.vo.SubmissionVO;
import com.codejudge.submission.mapper.CompileInfoMapper;
import com.codejudge.submission.mapper.JudgeResultMapper;
import com.codejudge.submission.mapper.JudgeTaskMapper;
import com.codejudge.submission.mapper.SubmissionMapper;
import com.codejudge.submission.mq.JudgeEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * 提交服务：受理、幂等、查询、重判。
 *
 * <p><b>幂等设计（双保险）</b>：
 * <ol>
 *   <li>快路径：Redis key {@code judge:submission:idempotent:{userId}:{problemId}:{contestId}:{codeHash}}，
 *       TTL 60s —— 拦截双击 / 网关重发；</li>
 *   <li>最终防线：唯一索引 {@code uk_submission_idempotent(user_id, problem_id, contest_id, code_hash, submit_round)}
 *       —— Redis 失效 / 并发穿透时由 DuplicateKeyException 兜底，回查已有记录返回。</li>
 * </ol>
 * 同码但已判完（终态）的重复提交属正常业务（刷新记录），轮次 +1 落新行。
 *
 * <p><b>接口边界</b>：本服务只落库 + 投递 MQ，<b>不执行判题</b>；
 * 题目合法性经 Feign 调 judge-problem 内部端点校验（题目信息是判题依据，
 * judge-problem 不可用时提交快速失败，不带病受理）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubmissionService {

    /** 状态机常量（与 PO 注释、DDL COMMENT 对齐） */
    public static final String ST_PENDING = "PENDING";
    public static final String ST_JUDGING = "JUDGING";
    public static final String ST_SUCCESS = "SUCCESS";
    public static final String ST_FAILED = "FAILED";

    /** 系统级失败结论：沙箱/平台故障，区别于用户代码错误 */
    public static final String VERDICT_SE = "SE";

    /** 代码长度上限：TEXT 列 64KB，业务约束 32KB（超出应转 MinIO，P3 未启用） */
    private static final int MAX_CODE_LENGTH = 32 * 1024;

    /** 榜单重建单次可拉取的提交数上限：防止一次请求把内存与 Feign 超时打爆 */
    private static final int MAX_REBUILD_ROWS = 50_000;

    /**
     * 点评上下文下发的逐用例样本上限。
     *
     * <p>点评 Prompt 需要的是「错在哪一类用例」（例如「第 3 个隐藏用例 WA」），
     * 而不是完整报表；用例数可达数百条，全量灌进 Prompt 只会稀释有效信息并推高 token 成本。
     * 全局统计由 caseTotal / caseAcCount 保留，下游（judge-ai）还会按自己的
     * {@code cj.review.max-case-samples} 再取一次前 N 条。
     */
    private static final int REVIEW_CASE_SAMPLE_LIMIT = 20;

    private static final Set<String> TERMINAL_STATUSES = Set.of(ST_SUCCESS, ST_FAILED);

    private final SubmissionMapper submissionMapper;
    private final JudgeTaskMapper judgeTaskMapper;
    private final JudgeResultMapper judgeResultMapper;
    private final CompileInfoMapper compileInfoMapper;
    private final ProblemClient problemClient;
    private final ContestClient contestClient;
    private final JudgeEventPublisher eventPublisher;
    private final StringRedisTemplate redis;
    private final JudgeProperties judgeProperties;
    /** 自代理：createSubmissionWithTask 的 @Transactional 需经代理调用才生效（避免自调用绕过事务） */
    private final ObjectProvider<SubmissionService> self;

    // ==================== 提交受理 ====================

    /**
     * 受理提交：校验 → 幂等检查 → 落库（submission + judge_task）→ 投递 MQ。
     *
     * @return 提交 VO（idempotent=true 表示命中幂等返回已有提交）
     */
    public SubmissionVO submit(SubmissionFormDTO form) {
        Long userId = UserContext.getUserId();

        Language language = Language.of(form.getLanguage());
        if (language == null) {
            throw new BadRequestException("不支持的语言：" + form.getLanguage() + "（可选 JAVA/PYTHON/CPP/GO）");
        }
        String code = form.getCode();
        if (code.length() > MAX_CODE_LENGTH) {
            throw new BadRequestException("代码过长，上限 32KB（当前 " + code.length() + " 字节）");
        }
        long contestId = form.getContestId() == null ? 0L : form.getContestId();

        checkRateLimit(userId);

        // 题目校验：存在且已发布才可提交（内部 Feign，题目服务不可用则快速失败）
        JudgeInfoDTO problem;
        try {
            problem = problemClient.getJudgeInfo(form.getProblemId());
        } catch (Exception e) {
            log.error("拉取题目信息失败，problemId={}", form.getProblemId(), e);
            throw new BadRequestException("题目服务暂不可用，请稍后再试");
        }
        if (problem == null || problem.getStatus() == null || problem.getStatus() != 1) {
            throw new BizIllegalException("题目不存在或未发布，不可提交");
        }

        // 竞赛合法性校验（竞赛服务不可用时快速失败，宁可拒绝也不写入污染榜单的数据）
        if (contestId != 0L) {
            validateContest(contestId, userId, form.getProblemId());
        }

        String codeHash = sha256Hex(code);
        String idemKey = JudgeRedisKeys.SUBMISSION_IDEMPOTENT_PREFIX + userId + ":"
                + form.getProblemId() + ":" + contestId + ":" + codeHash;

        // 1) 快路径：Redis 幂等 key
        String hit = redis.opsForValue().get(idemKey);
        if (hit != null) {
            Submission existing = submissionMapper.selectById(Long.valueOf(hit));
            if (existing != null) {
                log.info("提交命中 Redis 幂等 key，返回已有提交 submissionId={}", existing.getId());
                return toVO(existing, true);
            }
        }

        // 2) DB 兜底：同码且仍在途（PENDING/JUDGING）→ 幂等返回；已终态 → 轮次+1 落新行
        Submission last = selectLastSameCode(userId, form.getProblemId(), contestId, codeHash);
        if (last != null && !TERMINAL_STATUSES.contains(last.getStatus())) {
            redis.opsForValue().set(idemKey, String.valueOf(last.getId()),
                    Duration.ofSeconds(judgeProperties.getIdempotentTtlSeconds()));
            log.info("提交命中在途同码记录，幂等返回 submissionId={} status={}", last.getId(), last.getStatus());
            return toVO(last, true);
        }
        int round = (last == null) ? 0 : last.getSubmitRound() + 1;

        // 3) 落库（事务内 submission + judge_task 同生共死）；
        //    MQ 投递挂在事务 afterCommit，发送失败由补偿调度器扫描滞留任务重发
        Submission submission = self.getObject()
                .createSubmissionWithTask(userId, form.getProblemId(), contestId, language, code, codeHash, round);

        redis.opsForValue().set(idemKey, String.valueOf(submission.getId()),
                Duration.ofSeconds(judgeProperties.getIdempotentTtlSeconds()));
        // 待判队列 ZSet：/workers 的队列积压指标
        redis.opsForZSet().add(JudgeRedisKeys.JUDGE_QUEUE_ZSET, String.valueOf(submission.getId()),
                System.currentTimeMillis());
        return toVO(submission, false);
    }

    /** 落库：submission 与 judge_task 同事务；唯一索引冲突时回查已有记录幂等返回 */
    @Transactional(rollbackFor = Exception.class)
    public Submission createSubmissionWithTask(Long userId, Long problemId, long contestId,
                                               Language language, String code, String codeHash, int round) {
        Submission submission = new Submission();
        submission.setUserId(userId);
        submission.setProblemId(problemId);
        submission.setContestId(contestId);
        submission.setLanguage(language.name());
        submission.setCode(code);
        submission.setCodeHash(codeHash);
        submission.setSubmitRound(round);
        submission.setStatus(ST_PENDING);
        submission.setScore(0);
        submission.setSubmitTime(LocalDateTime.now());
        try {
            submissionMapper.insert(submission);
        } catch (DuplicateKeyException e) {
            // 并发同码穿透 Redis 快路径：唯一索引兜底，回查返回
            Submission existing = selectLastSameCode(userId, problemId, contestId, codeHash);
            if (existing != null) {
                log.info("并发提交被唯一索引拦截，幂等返回 submissionId={}", existing.getId());
                return existing;
            }
            throw e;
        }

        JudgeTask task = new JudgeTask();
        task.setSubmissionId(submission.getId());
        task.setStatus(ST_PENDING);
        task.setAttempt(0);
        task.setMaxAttempt(judgeProperties.getMaxAttempt());
        task.setTimeoutMs((int) judgeProperties.getTaskTimeoutMs());
        judgeTaskMapper.insert(task);

        submission.setTaskId(task.getId());
        log.info("提交落库成功 submissionId={} taskId={} user={} problem={} language={} round={}",
                submission.getId(), task.getId(), userId, problemId, language, round);
        return submission;
    }

    // ==================== 竞赛校验（P4 新增） ====================

    /**
     * 校验竞赛提交合法性：竞赛存在且在赛程内、题目属于该竞赛、需要报名时已报名。
     *
     * <p>为什么必须在受理端拦截：排行榜的数据源就是「带 contestId 的提交」，
     * 一旦允许任意 contestId 直通，任何人（包括非参赛者、竞赛结束后的迟到者）
     * 都能往榜里写记录，而榜单的权威性正是整个竞赛功能的前提。
     * P3 的提交接口对 contestId 只做透传未做校验，属本次补齐的边界缺口。
     */
    private void validateContest(long contestId, Long userId, Long problemId) {
        ContestContextDTO ctx;
        try {
            ctx = contestClient.getContext(contestId, userId);
        } catch (Exception e) {
            log.error("拉取竞赛上下文失败：contestId={}", contestId, e);
            throw new BadRequestException("竞赛服务暂不可用，请稍后再试");
        }
        if (ctx == null) {
            throw new BizIllegalException("竞赛不存在：" + contestId);
        }
        Integer status = ctx.getStatus();
        if (status == null || status != 1) {
            throw new BizIllegalException(status != null && status == 0
                    ? "竞赛尚未开始，无法提交" : "竞赛已结束，无法提交");
        }
        if (ctx.getProblemIds() == null || !ctx.getProblemIds().contains(problemId)) {
            throw new BizIllegalException("题目 " + problemId + " 不属于竞赛 " + contestId + "，不可作为竞赛提交");
        }
        if (Boolean.TRUE.equals(ctx.getNeedRegister()) && Boolean.FALSE.equals(ctx.getRegistered())) {
            throw new ForbiddenException("请先报名参加竞赛后再提交");
        }
    }

    /**
     * 竞赛终态提交列表（内部接口，供 judge-contest 重建榜单）。
     *
     * <p>只返回重建必需的列：不含代码、不含用例输出摘要 —— 榜单重建没有任何理由触碰这些数据。
     * 按提交时间升序返回，重建端必须按序回放，否则「首次 AC 才计分」的语义会被写坏。
     */
    public List<ContestSubmissionDTO> listContestResults(Long contestId, Integer limit) {
        int cap = (limit == null || limit <= 0) ? MAX_REBUILD_ROWS : Math.min(limit, MAX_REBUILD_ROWS);
        List<Submission> list = submissionMapper.selectList(new LambdaQueryWrapper<Submission>()
                .eq(Submission::getContestId, contestId)
                // 只回放判完的提交：在途提交此刻还没有结论，重建结束后由增量事件补上
                .in(Submission::getStatus, TERMINAL_STATUSES)
                .orderByAsc(Submission::getSubmitTime)
                .orderByAsc(Submission::getId)
                .last("LIMIT " + cap));
        return list.stream().map(s -> {
            ContestSubmissionDTO dto = new ContestSubmissionDTO();
            dto.setSubmissionId(s.getId());
            dto.setUserId(s.getUserId());
            dto.setProblemId(s.getProblemId());
            dto.setVerdict(s.getVerdict());
            dto.setScore(s.getScore());
            dto.setSubmitTimeEpochMs(s.getSubmitTime() == null ? null
                    : s.getSubmitTime().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
            return dto;
        }).toList();
    }

    // ==================== 查询 ====================

    /** 详情：本人 / 教师 / 管理员可见；学员视角隐藏用例的摘要被屏蔽 */
    public SubmissionDetailVO queryDetail(Long id) {
        Long userId = UserContext.getUserId();
        Integer role = UserContext.getRole();
        Submission submission = requireSubmission(id);
        boolean privileged = role != null && (role == 1 || role == 3);
        if (!privileged && !submission.getUserId().equals(userId)) {
            throw new ForbiddenException("无权查看他人提交");
        }

        SubmissionDetailVO vo = new SubmissionDetailVO();
        vo.setId(submission.getId());
        vo.setUserId(submission.getUserId());
        vo.setProblemId(submission.getProblemId());
        vo.setContestId(submission.getContestId());
        vo.setLanguage(submission.getLanguage());
        vo.setStatus(submission.getStatus());
        vo.setVerdict(submission.getVerdict());
        vo.setScore(submission.getScore());
        vo.setTimeMs(submission.getTimeMs());
        vo.setMemoryKb(submission.getMemoryKb());
        vo.setSubmitTime(submission.getSubmitTime());

        // 隐藏用例集合：仅学员视角需要屏蔽摘要（教师/管理员可见全部）
        //   hiddenCaseIds == null 表示 fail-closed（题目服务不可用，全部摘要屏蔽）
        Set<Long> hiddenCaseIds = Set.of();
        if (!privileged) {
            try {
                JudgeInfoDTO info = problemClient.getJudgeInfo(submission.getProblemId());
                if (info != null && info.getTestCases() != null) {
                    hiddenCaseIds = info.getTestCases().stream()
                            .filter(c -> c.getIsHidden() != null && c.getIsHidden() == 1)
                            .map(c -> c.getCaseId())
                            .collect(java.util.stream.Collectors.toSet());
                }
            } catch (Exception e) {
                // fail-closed：宁可少展示，不泄露隐藏用例答案
                log.warn("拉取隐藏用例集合失败，本次详情屏蔽全部摘要 submissionId={}", id);
                hiddenCaseIds = null;
            }
        }

        List<JudgeResult> results = judgeResultMapper.selectList(new LambdaQueryWrapper<JudgeResult>()
                .eq(JudgeResult::getSubmissionId, id)
                .orderByAsc(JudgeResult::getSeq));
        final Set<Long> hidden = hiddenCaseIds;
        vo.setCaseResults(results.stream().map(r -> {
            CaseResultVO c = new CaseResultVO();
            c.setCaseId(r.getCaseId());
            c.setSeq(r.getSeq());
            c.setVerdict(r.getVerdict());
            c.setTimeMs(r.getTimeMs());
            c.setMemoryKb(r.getMemoryKb());
            boolean isHiddenCase = (hidden == null) || hidden.contains(r.getCaseId());
            c.setOutputDigest(isHiddenCase ? null : r.getOutputDigest());
            c.setStderrDigest(isHiddenCase ? null : r.getStderrDigest());
            c.setHidden(isHiddenCase);
            return c;
        }).toList());

        CompileInfo compileInfo = compileInfoMapper.selectOne(new LambdaQueryWrapper<CompileInfo>()
                .eq(CompileInfo::getSubmissionId, id)
                .last("LIMIT 1"));
        if (compileInfo != null) {
            SubmissionDetailVO.CompileInfoVO ci = new SubmissionDetailVO.CompileInfoVO();
            ci.setSuccess(compileInfo.getSuccess() != null && compileInfo.getSuccess() == 1);
            ci.setStdoutLog(compileInfo.getStdoutLog());
            ci.setStderrLog(compileInfo.getStderrLog());
            ci.setDurationMs(compileInfo.getDurationMs());
            vo.setCompileInfo(ci);
        }
        return vo;
    }

    /**
     * AI 点评上下文（供 judge-ai 组装点评 Prompt）。
     *
     * <p>与 {@link #queryDetail} 的区别：
     * <ul>
     *   <li><b>不做当前请求者的归属校验</b> —— 本方法只被内部端点调用，
     *       调用方（judge-ai）拿到 {@code userId} 后自行鉴权；</li>
     *   <li>逐用例结果只取前 {@value #REVIEW_CASE_SAMPLE_LIMIT} 条样本
     *       （点评 Prompt 需要的是"错在哪一类用例"，不是完整报表），
     *       并通过 {@code caseTotal}/{@code caseAcCount} 保留全局统计；</li>
     *   <li>隐藏用例摘要是否下发由 {@code maskHidden} 决定，**默认遮蔽**。</li>
     * </ul>
     *
     * @param id         提交 id
     * @param maskHidden 为 true 时遮蔽隐藏用例的 outputDigest / stderrDigest
     */
    public SubmissionReviewContextDTO reviewContext(Long id, boolean maskHidden) {
        Submission submission = requireSubmission(id);
        SubmissionReviewContextDTO dto = new SubmissionReviewContextDTO();
        dto.setSubmissionId(submission.getId());
        dto.setUserId(submission.getUserId());
        dto.setProblemId(submission.getProblemId());
        dto.setContestId(submission.getContestId());
        dto.setLanguage(submission.getLanguage());
        // 代码原样下发：judge-ai 侧按 cj.review.max-code-length 截断，本处不再二次裁剪，
        // 避免两处上限不一致导致「点评说代码被截断，实际上游已截断」的排查困惑。
        dto.setCode(submission.getCode());
        dto.setStatus(submission.getStatus());
        dto.setVerdict(submission.getVerdict());
        dto.setScore(submission.getScore());
        dto.setTimeMs(submission.getTimeMs());
        dto.setMemoryKb(submission.getMemoryKb());
        dto.setSubmitTime(submission.getSubmitTime());

        // 隐藏用例集合：拉取失败时 fail-closed（视为「全部隐藏」），与 queryDetail 的策略一致
        Set<Long> hiddenCaseIds = resolveHiddenCaseIds(submission.getProblemId(), maskHidden);

        List<JudgeResult> results = judgeResultMapper.selectList(new LambdaQueryWrapper<JudgeResult>()
                .eq(JudgeResult::getSubmissionId, id)
                .orderByAsc(JudgeResult::getSeq));
        dto.setCaseTotal(results.size());
        dto.setCaseAcCount((int) results.stream().filter(r -> "AC".equals(r.getVerdict())).count());
        dto.setCaseSamples(results.stream()
                .limit(REVIEW_CASE_SAMPLE_LIMIT)
                .map(r -> {
                    SubmissionReviewContextDTO.CaseSample c = new SubmissionReviewContextDTO.CaseSample();
                    c.setSeq(r.getSeq());
                    c.setVerdict(r.getVerdict());
                    c.setTimeMs(r.getTimeMs());
                    c.setMemoryKb(r.getMemoryKb());
                    boolean hidden = (hiddenCaseIds == null) || hiddenCaseIds.contains(r.getCaseId());
                    c.setOutputDigest(hidden ? null : r.getOutputDigest());
                    c.setStderrDigest(hidden ? null : r.getStderrDigest());
                    c.setHidden(hidden);
                    return c;
                })
                .toList());

        CompileInfo compileInfo = compileInfoMapper.selectOne(new LambdaQueryWrapper<CompileInfo>()
                .eq(CompileInfo::getSubmissionId, id)
                .last("LIMIT 1"));
        if (compileInfo != null) {
            SubmissionReviewContextDTO.CompileInfoBrief ci = new SubmissionReviewContextDTO.CompileInfoBrief();
            ci.setSuccess(compileInfo.getSuccess() != null && compileInfo.getSuccess() == 1);
            ci.setStdoutLog(compileInfo.getStdoutLog());
            ci.setStderrLog(compileInfo.getStderrLog());
            ci.setDurationMs(compileInfo.getDurationMs());
            dto.setCompileInfo(ci);
        }
        return dto;
    }

    /**
     * 解析题目的隐藏用例 id 集合。
     *
     * @param maskHidden false 表示调用方是教师/员工，直接返回空集合（不遮蔽）
     * @return 隐藏用例 id 集合；<b>返回 null 表示 fail-closed</b>（题目服务不可用，全部视为隐藏）
     */
    private Set<Long> resolveHiddenCaseIds(Long problemId, boolean maskHidden) {
        if (!maskHidden) {
            return Set.of();
        }
        try {
            JudgeInfoDTO info = problemClient.getJudgeInfo(problemId);
            if (info != null && info.getTestCases() != null) {
                return info.getTestCases().stream()
                        .filter(c -> c.getIsHidden() != null && c.getIsHidden() == 1)
                        .map(c -> c.getCaseId())
                        .collect(java.util.stream.Collectors.toSet());
            }
            return Set.of();
        } catch (Exception e) {
            // fail-closed：宁可少展示，不泄露隐藏用例答案
            log.warn("拉取隐藏用例集合失败，本次点评上下文遮蔽全部用例摘要 problemId={}", problemId);
            return null;
        }
    }

    /** 分页：学员强制只看自己；教师/管理员可看全部（可按 userId 过滤） */
    public PageDTO<SubmissionVO> page(SubmissionQuery query) {
        Integer role = UserContext.getRole();
        Long userId = UserContext.getUserId();
        boolean privileged = role != null && (role == 1 || role == 3);

        LambdaQueryWrapper<Submission> wrapper = new LambdaQueryWrapper<Submission>()
                // 学员视角强制 user_id = 自己（查询参数里的 userId 被忽略，防越权翻他人记录）
                .eq(!privileged, Submission::getUserId, userId)
                .eq(privileged && query.getUserId() != null, Submission::getUserId, query.getUserId())
                .eq(query.getProblemId() != null, Submission::getProblemId, query.getProblemId())
                .eq(query.getStatus() != null, Submission::getStatus, query.getStatus())
                .eq(query.getVerdict() != null, Submission::getVerdict, query.getVerdict())
                .orderByDesc(Submission::getSubmitTime);

        Page<Submission> page = submissionMapper.selectPage(query.toMpPage(), wrapper);
        return PageDTO.of(page, s -> toVO(s, false));
    }

    // ==================== 重判 ====================

    /**
     * 重判：教师须为题目归属人，管理员不受限。
     * 复位提交/任务/清空历史结果，attempt 归零后投递 RETRY 消息。
     */
    @Transactional(rollbackFor = Exception.class)
    public void rejudge(Long submissionId) {
        Long operator = UserContext.getUserId();
        Integer role = UserContext.getRole();
        Submission submission = requireSubmission(submissionId);

        JudgeInfoDTO problem = problemClient.getJudgeInfo(submission.getProblemId());
        if (problem == null) {
            throw new BizIllegalException("题目不存在，无法重判");
        }
        if (role != null && role == 3 && !operator.equals(problem.getOwnerId())) {
            throw new ForbiddenException("仅可重判本人题目下的提交");
        }

        JudgeTask task = judgeTaskMapper.selectOne(new LambdaQueryWrapper<JudgeTask>()
                .eq(JudgeTask::getSubmissionId, submissionId)
                .orderByDesc(JudgeTask::getCreateTime)
                .last("LIMIT 1"));
        if (task == null) {
            throw new BizIllegalException("判题任务不存在，无法重判");
        }

        // 复位任务（含 JUDGING 中的任务 —— 管理员强制重判）
        task.setStatus(ST_PENDING);
        task.setAttempt(0);
        task.setWorkerId(null);
        task.setLeaseOwner(null);
        task.setLeaseExpireAt(null);
        task.setNextRetryAt(null);
        task.setErrorMsg("管理员/教师触发重判");
        judgeTaskMapper.updateById(task);

        // 清空历史结果与编译信息（逻辑删除）
        judgeResultMapper.delete(new LambdaQueryWrapper<JudgeResult>()
                .eq(JudgeResult::getSubmissionId, submissionId));
        compileInfoMapper.delete(new LambdaQueryWrapper<CompileInfo>()
                .eq(CompileInfo::getSubmissionId, submissionId));

        // 复位提交
        submission.setStatus(ST_PENDING);
        submission.setVerdict(null);
        submission.setScore(0);
        submission.setTimeMs(null);
        submission.setMemoryKb(null);
        submission.setCompileInfoId(null);
        submissionMapper.updateById(submission);

        redis.opsForZSet().add(JudgeRedisKeys.JUDGE_QUEUE_ZSET, String.valueOf(submissionId),
                System.currentTimeMillis());

        // 事务提交后投递 —— 通过 TransactionSynchronization 保证
        com.codejudge.common.utils.TxSupport.afterCommit(() ->
                eventPublisher.publishRetry(submissionId, task.getId(), 0, "manual-rejudge"));
        log.info("重判已受理 submissionId={} operator={} role={}", submissionId, operator, role);
    }

    // ==================== 内部工具 ====================

    private Submission selectLastSameCode(Long userId, Long problemId, long contestId, String codeHash) {
        return submissionMapper.selectOne(new LambdaQueryWrapper<Submission>()
                .eq(Submission::getUserId, userId)
                .eq(Submission::getProblemId, problemId)
                .eq(Submission::getContestId, contestId)
                .eq(Submission::getCodeHash, codeHash)
                .orderByDesc(Submission::getSubmitRound)
                .last("LIMIT 1"));
    }

    private Submission requireSubmission(Long id) {
        Submission submission = submissionMapper.selectById(id);
        if (submission == null) {
            throw new BizIllegalException(404, "提交不存在：" + id);
        }
        return submission;
    }

    private void checkRateLimit(Long userId) {
        String key = JudgeRedisKeys.SUBMISSION_RATE_PREFIX + userId;
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1) {
            redis.expire(key, Duration.ofSeconds(60));
        }
        if (count != null && count > judgeProperties.getSubmitRateLimitPerMin()) {
            throw new BadRequestException("提交过于频繁，每分钟最多 " + judgeProperties.getSubmitRateLimitPerMin() + " 次");
        }
    }

    private SubmissionVO toVO(Submission s, boolean idempotent) {
        SubmissionVO vo = new SubmissionVO();
        vo.setId(s.getId());
        vo.setUserId(s.getUserId());
        vo.setProblemId(s.getProblemId());
        vo.setContestId(s.getContestId());
        vo.setLanguage(s.getLanguage());
        vo.setStatus(s.getStatus());
        vo.setVerdict(s.getVerdict());
        vo.setScore(s.getScore());
        vo.setTimeMs(s.getTimeMs());
        vo.setMemoryKb(s.getMemoryKb());
        vo.setSubmitTime(s.getSubmitTime());
        vo.setIdempotent(idempotent);
        return vo;
    }

    static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
