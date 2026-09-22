package com.codejudge.worker.mq;

import com.codejudge.api.client.problem.ProblemClient;
import com.codejudge.api.dto.problem.JudgeInfoDTO;
import com.codejudge.api.dto.submission.SubmissionTaskMessage;
import com.codejudge.common.constants.JudgeRedisKeys;
import com.codejudge.common.mq.MqHandler;
import com.codejudge.common.mq.MqTopics;
import com.codejudge.worker.domain.po.JudgeTask;
import com.codejudge.worker.domain.po.Submission;
import com.codejudge.worker.engine.JudgeEngine;
import com.codejudge.worker.mapper.WorkerJudgeTaskMapper;
import com.codejudge.worker.mapper.WorkerSubmissionMapper;
import com.codejudge.worker.sandbox.SandboxException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

/**
 * 判题任务消费（topic=judge_submission，tag=CREATED/RETRY）。
 *
 * <p><b>幂等三重防线</b>（顺序执行，任一命中即丢弃消息）：
 * <ol>
 *   <li>attempt 比对：消息 attempt != 任务当前 attempt → 旧消息，丢弃；</li>
 *   <li>数据库 CAS 认领（WHERE status='PENDING' AND attempt=#{attempt}）—— 权威防线，
 *       多 worker 并发抢同一任务时只有一者 rows=1；</li>
 *   <li>Redis SETNX 任务锁 {@code judge:task:lock:{taskId}:{attempt}} —— 认领成功后补挂
 *       （TTL = 任务超时 + 30s），拦截认领后的重复处理，PLAN §4.2 契约。</li>
 * </ol>
 *
 * <p><b>异常策略</b>：handler 不向外抛异常（避免容器 RECONSUME_LATER 无限重投）。
 * 平台侧故障（沙箱不可用 / Feign / DB）统一折叠进 {@link JudgeEngine#failTask}：
 * attempt+1 延迟重投或转死信终态 —— 重试次数完全受控。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubmissionTaskHandler implements MqHandler {

    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redis;
    private final WorkerJudgeTaskMapper taskMapper;
    private final WorkerSubmissionMapper submissionMapper;
    private final ProblemClient problemClient;
    private final JudgeEngine engine;
    private final WorkerIdentity identity;

    @Override
    public Set<String> subscribeTopics() {
        return Set.of(MqTopics.TOPIC_JUDGE_SUBMISSION);
    }

    @Override
    public Set<String> subscribeTags() {
        // 判题取任务只看 CREATED / RETRY；PROGRESS（自己产的）与 RESULT（自己产的）都不必收回
        return Set.of(MqTopics.Tags.SUBMISSION_CREATED, MqTopics.Tags.SUBMISSION_RETRY);
    }

    @Override
    public boolean supports(String topic, String tag) {
        return MqTopics.TOPIC_JUDGE_SUBMISSION.equals(topic)
                && (MqTopics.Tags.SUBMISSION_CREATED.equals(tag)
                || MqTopics.Tags.SUBMISSION_RETRY.equals(tag));
    }

    @Override
    public void handle(MessageExt message) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        SubmissionTaskMessage msg;
        try {
            msg = objectMapper.readValue(body, SubmissionTaskMessage.class);
        } catch (Exception e) {
            // 反序列化失败的消息不可恢复：丢弃并告警（broker 原生 %DLQ% 兜底留档）
            log.error("判题任务消息反序列化失败，丢弃：msgId={} body={}", message.getMsgId(), body, e);
            return;
        }
        Long taskId = msg.getTaskId();
        int attempt = msg.getAttempt() == null ? 0 : msg.getAttempt();
        log.info("收到判题任务：submissionId={} taskId={} attempt={} source={} msgId={}",
                msg.getSubmissionId(), taskId, attempt, msg.getSource(), message.getMsgId());

        try {
            process(msg, taskId, attempt);
        } catch (Exception e) {
            // 一切执行期异常折叠为平台故障路径；handler 永不外抛（防 broker 无限重投）
            log.error("判题执行异常（转平台重试路径）：taskId={} attempt={}", taskId, attempt, e);
            recordPlatformFailure(taskId, e.getMessage());
        }
    }

    private void process(SubmissionTaskMessage msg, Long taskId, int attempt) {
        JudgeTask task = taskMapper.selectById(taskId);
        if (task == null) {
            log.warn("任务不存在，丢弃消息：taskId={}（提交可能已被清理）", taskId);
            return;
        }
        // 防线 2：attempt 比对（重试消息可能比新一轮投递更晚到达）
        int currentAttempt = task.getAttempt() == null ? 0 : task.getAttempt();
        if (currentAttempt != attempt) {
            log.info("旧消息丢弃：taskId={} msgAttempt={} currentAttempt={}", taskId, attempt, currentAttempt);
            return;
        }

        // 防线 3：数据库 CAS 认领（权威；租约同时生效）
        // 注意：SETNX 预检锁在认领成功后再打（若放在认领前，一次消费异常会把锁挂住、
        // TTL 内拦截合法重投 —— 实测踩坑 2026-09-20）
        int timeoutMs = task.getTimeoutMs() == null ? 120_000 : task.getTimeoutMs();
        java.time.LocalDateTime leaseExpireAt =
                java.time.LocalDateTime.now().plusNanos(timeoutMs * 1_000_000L);
        int claimed = taskMapper.claim(taskId, attempt, identity.workerId(), leaseExpireAt);
        if (claimed != 1) {
            log.info("CAS 认领失败（已被其他 worker 认领或状态漂移），丢弃：taskId={} attempt={}", taskId, attempt);
            return;
        }
        String lockKey = JudgeRedisKeys.TASK_LOCK_PREFIX + taskId + ":" + attempt;
        redis.opsForValue().setIfAbsent(lockKey, identity.workerId(), Duration.ofMillis(timeoutMs + 30_000L));
        log.info("任务认领成功：taskId={} submissionId={} attempt={} worker={}",
                taskId, task.getSubmissionId(), attempt, identity.workerId());

        Submission submission = submissionMapper.selectById(task.getSubmissionId());
        if (submission == null) {
            log.error("提交不存在：submissionId={} taskId={}", task.getSubmissionId(), taskId);
            return;
        }
        submissionMapper.markJudging(submission.getId());

        identity.taskStarted();
        try {
            // 判题依据：题目限制 + 全部用例（内部 Feign；失败抛异常 → failTask 重试）
            JudgeInfoDTO problemInfo = problemClient.getJudgeInfo(submission.getProblemId());
            if (problemInfo == null) {
                throw new SandboxException("题目信息为空：problemId=" + submission.getProblemId());
            }
            engine.judge(task, submission, identity.workerId(), problemInfo);
        } catch (Exception e) {
            log.error("判题失败（attempt={}）：taskId={} submissionId={} err={}",
                    attempt, taskId, submission.getId(), e.getMessage(), e);
            boolean willRetry = engine.failTask(task, submission, identity.workerId(),
                    abbreviate(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            if (!willRetry) {
                log.error("任务进入死信终态：taskId={} submissionId={}", taskId, submission.getId());
            }
        } finally {
            identity.taskFinished();
        }
    }

    /** 执行中途崩溃（process 捕获到的异常）时的失败记录：尽力而为，租约过期后由补偿调度接管 */
    private void recordPlatformFailure(Long taskId, String error) {
        try {
            JudgeTask task = taskMapper.selectById(taskId);
            if (task == null) {
                return;
            }
            Submission submission = submissionMapper.selectById(task.getSubmissionId());
            if (submission == null) {
                return;
            }
            engine.failTask(task, submission, identity.workerId(), abbreviate(error));
        } catch (Exception e2) {
            log.error("平台失败记录本身失败，等待租约过期由补偿调度接管：taskId={}", taskId, e2);
        }
    }

    private String abbreviate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 480 ? s : s.substring(0, 480) + "…";
    }
}
