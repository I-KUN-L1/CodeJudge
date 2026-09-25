package com.codejudge.submission.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.codejudge.api.dto.submission.SubmissionResultMessage;
import com.codejudge.common.constants.JudgeRedisKeys;
import com.codejudge.common.mq.MqTopics;
import com.codejudge.common.mq.RocketMQTemplate;
import com.codejudge.submission.config.JudgeProperties;
import com.codejudge.submission.domain.po.JudgeTask;
import com.codejudge.submission.domain.po.Submission;
import com.codejudge.submission.mapper.JudgeTaskMapper;
import com.codejudge.submission.mapper.SubmissionMapper;
import com.codejudge.submission.mq.JudgeEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 判题补偿调度（故障转移 + 滞留重发 + 死信投递）。
 *
 * <p>四大职责（全部以数据库 CAS / Redis SETNX 防并发重复）：
 * <ol>
 *   <li><b>故障转移</b>（每 10s）：JUDGING 且租约过期的任务 → 判定 worker 失联，
 *       attempt+1 复位 PENDING 后投递 RETRY；超限转死信。这就是
 *       「杀掉 worker 后任务被其他实例接管」的验收路径；</li>
 *   <li><b>滞留重发</b>（每 10s）：PENDING 超过 {@code pendingRescueDelayMs} 仍未被认领 →
 *       覆盖「MQ 发送失败 / 消息丢失」的场景（本地消息表思想的轻量替代）；</li>
 *   <li><b>死信投递</b>（每 30s）：DEAD 任务投递业务死信 topic（judge_submission_dlq / Tag DEAD），
 *       与 RocketMQ 原生 %DLQ% 消费组死信双轨并存；</li>
 *   <li><b>队列对账</b>（每 60s）：摘除待判 ZSet 中「对应任务已终态」的僵尸成员。
 *       <b>这一项是 judge_queue_backlog 指标可信的前提</b> —— 没有它，
 *       任何一条在「认领后、摘除前」中断的路径都会让积压数永久虚增（已实测复现）。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JudgeCompensationService {

    private static final Duration DLQ_FLAG_TTL = Duration.ofHours(24);
    private static final Duration RESCUE_FLAG_TTL = Duration.ofSeconds(30);

    /** 队列对账每轮检查的成员数（按入队时间升序取最老的一批） */
    private static final int QUEUE_RECONCILE_BATCH = 500;

    /** 队列成员的"仍在推进中"状态；不在此集合内即视为僵尸 */
    private static final Set<String> QUEUE_ACTIVE_STATUSES = Set.of("PENDING", "JUDGING");

    private final JudgeTaskMapper judgeTaskMapper;
    private final SubmissionMapper submissionMapper;
    private final JudgeEventPublisher eventPublisher;
    private final RocketMQTemplate mqTemplate;
    private final StringRedisTemplate redis;
    private final JudgeProperties properties;

    /**
     * 故障转移扫描：接管租约过期的 JUDGING 任务。
     */
    @Scheduled(fixedDelay = 10_000, initialDelay = 20_000)
    public void failoverScan() {
        List<JudgeTask> expired = judgeTaskMapper.selectList(new LambdaQueryWrapper<JudgeTask>()
                .eq(JudgeTask::getStatus, "JUDGING")
                .lt(JudgeTask::getLeaseExpireAt, LocalDateTime.now())
                .last("LIMIT 50"));
        for (JudgeTask task : expired) {
            // 数据库 CAS：多实例并发扫描时只有一次生效
            int taken = judgeTaskMapper.takeoverExpiredLease(task.getId(),
                    "租约过期，补偿调度接管（原持有者：" + task.getLeaseOwner() + "）");
            if (taken != 1) {
                continue;
            }
            log.warn("故障转移：任务租约过期被接管 taskId={} submissionId={} 原持有者={} 新 attempt={}",
                    task.getId(), task.getSubmissionId(), task.getLeaseOwner(), task.getAttempt() + 1);

            JudgeTask current = judgeTaskMapper.selectById(task.getId());
            if (current.getAttempt() >= current.getMaxAttempt()) {
                deadLetter(current, "超过最大重试次数(" + current.getMaxAttempt() + ")，最后原因：租约过期（worker 失联）");
            } else {
                // 将 submission 置回 PENDING，配合 RETRY 消息进入下一轮
                resetSubmissionToPending(current.getSubmissionId());
                eventPublisher.publishRetry(current.getSubmissionId(), current.getId(),
                        current.getAttempt(), "lease-expired-takeover");
            }
        }
    }

    /**
     * 滞留任务重发：PENDING 超时未被认领 → 重发消息（worker 侧 CAS 认领保证幂等）。
     */
    @Scheduled(fixedDelay = 10_000, initialDelay = 30_000)
    public void rescuePendingScan() {
        List<JudgeTask> stuck = judgeTaskMapper.selectList(new LambdaQueryWrapper<JudgeTask>()
                .eq(JudgeTask::getStatus, "PENDING")
                .lt(JudgeTask::getUpdateTime, LocalDateTime.now().minusNanos(properties.getPendingRescueDelayMs() * 1_000_000))
                .and(w -> w.isNull(JudgeTask::getNextRetryAt).or().le(JudgeTask::getNextRetryAt, LocalDateTime.now()))
                .last("LIMIT 50"));
        for (JudgeTask task : stuck) {
            String flag = JudgeRedisKeys.TASK_RESCUE_PREFIX + task.getId();
            Boolean first = redis.opsForValue().setIfAbsent(flag, "1", RESCUE_FLAG_TTL);
            if (!Boolean.TRUE.equals(first)) {
                continue; // 30s 内已重发过，防消息风暴
            }
            log.warn("滞留任务重发：taskId={} submissionId={} attempt={} 滞留超 {}ms",
                    task.getId(), task.getSubmissionId(), task.getAttempt(), properties.getPendingRescueDelayMs());
            eventPublisher.publishRetry(task.getSubmissionId(), task.getId(),
                    task.getAttempt(), "pending-rescue");
        }
    }

    /**
     * 死信兜底扫描：DEAD 任务补投业务死信（worker 侧标记 DEAD 但 DLQ 投递失败时的兜底）。
     */
    @Scheduled(fixedDelay = 30_000, initialDelay = 40_000)
    public void deadLetterScan() {
        List<JudgeTask> dead = judgeTaskMapper.selectList(new LambdaQueryWrapper<JudgeTask>()
                .eq(JudgeTask::getStatus, "DEAD")
                .last("LIMIT 50"));
        for (JudgeTask task : dead) {
            publishDlqOnce(task, task.getErrorMsg() == null ? "unknown" : task.getErrorMsg());
        }
    }

    /**
     * 队列对账扫描：摘除 {@code judge:judge:queue:zset} 中「对应判题任务已终态」的僵尸成员。
     *
     * <h3>为什么必须有这一层</h3>
     * 队列的每一个成员都代表「待判」。正常路径由判题完成时的 ZREM 摘除，但只要有**任何一条**
     * 路径在「认领之后、摘除之前」中断，成员就会永久滞留：worker 每轮会把它捞出来、
     * CAS 失败、再跳过，既浪费轮询，又让 {@code judge_queue_backlog} 只增不减 ——
     * 系统空闲时也报「有积压」，最终让积压告警彻底失去意义。
     *
     * <p>已实测复现：P3 阶段一次 {@code MysqlDataTruncation} 导致 2 个任务转 DEAD，
     * 其提交 id 至今仍留在 ZSet 中，空闲状态下积压指标恒为 2。
     * 死信路径已在 {@code JudgeEngine.failTask} 与本类 {@code deadLetter} 补上 ZREM，
     * 本扫描则作为**与具体失败原因无关**的兜底：无论未来从哪条路径泄漏，最多滞留一轮扫描周期。
     *
     * <h3>为什么只扫最老的一批</h3>
     * 积压的僵尸一定是「进队最早且迟迟不动」的那批，按 score（入队时间）升序取前 N 个即可命中。
     * 全量扫描在大积压下会拖长单次耗时并放大 Redis 压力，而收益并无差别。
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 50_000)
    public void queueReconcileScan() {
        Set<String> members = redis.opsForZSet()
                .range(JudgeRedisKeys.JUDGE_QUEUE_ZSET, 0, QUEUE_RECONCILE_BATCH - 1);
        if (members == null || members.isEmpty()) {
            return;
        }

        List<Long> submissionIds = new ArrayList<>(members.size());
        for (String m : members) {
            try {
                submissionIds.add(Long.parseLong(m));
            } catch (NumberFormatException e) {
                // 成员本应全部是提交 id 的十进制字符串；出现异常值说明有第三方写入，直接摘除
                log.warn("队列对账：发现非法成员，摘除 member={}", m);
                redis.opsForZSet().remove(JudgeRedisKeys.JUDGE_QUEUE_ZSET, m);
            }
        }
        if (submissionIds.isEmpty()) {
            return;
        }

        Map<Long, String> statusBySubmission = new HashMap<>(submissionIds.size());
        for (JudgeTask t : judgeTaskMapper.selectList(new LambdaQueryWrapper<JudgeTask>()
                .select(JudgeTask::getSubmissionId, JudgeTask::getStatus)
                .in(JudgeTask::getSubmissionId, submissionIds))) {
            statusBySubmission.put(t.getSubmissionId(), t.getStatus());
        }

        List<Object> orphans = new ArrayList<>();
        for (Long id : submissionIds) {
            String status = statusBySubmission.get(id);
            // 任务已终态（SUCCESS/DEAD）或压根查不到任务（提交被清理）→ 都不可能再被判题
            if (status == null || !QUEUE_ACTIVE_STATUSES.contains(status)) {
                orphans.add(String.valueOf(id));
            }
        }
        if (!orphans.isEmpty()) {
            Long removed = redis.opsForZSet().remove(JudgeRedisKeys.JUDGE_QUEUE_ZSET, orphans.toArray());
            log.warn("队列对账：摘除 {} 个僵尸成员（对应任务已终态或不存在），"
                    + "若此前积压指标持续不归零，本次摘除即为其根因 {}", removed, orphans);
        }
    }

    /**
     * 转死信：任务置 DEAD + 提交置 FAILED(SE) + 投递业务死信。
     * 供本类扫描路径调用；worker 侧超限失败有对称实现。
     */
    public void deadLetter(JudgeTask task, String reason) {
        JudgeTask probe = new JudgeTask();
        probe.setId(task.getId());
        probe.setStatus("DEAD");
        probe.setErrorMsg(reason);
        int updated = judgeTaskMapper.update(probe,
                new LambdaQueryWrapper<JudgeTask>()
                        .eq(JudgeTask::getId, task.getId())
                        .in(JudgeTask::getStatus, List.of("PENDING", "JUDGING")));
        // CAS 失败 = 任务已被 worker 判完置 SUCCESS（worker 先写提交终态再写任务）。
        // 此时绝不能把已 SUCCESS 的提交改写成 FAILED/SE 并投死信 —— 会污染榜单与前端展示。
        if (updated != 1) {
            log.warn("任务转死信跳过：CAS 未命中（任务已非 PENDING/JUDGING）taskId={} submissionId={}",
                    task.getId(), task.getSubmissionId());
            return;
        }
        log.error("任务转死信：taskId={} submissionId={} reason={}", task.getId(), task.getSubmissionId(), reason);
        Submission submission = submissionMapper.selectById(task.getSubmissionId());
        if (submission != null && !"FAILED".equals(submission.getStatus())) {
            submission.setStatus(SubmissionService.ST_FAILED);
            submission.setVerdict(SubmissionService.VERDICT_SE);
            submissionMapper.updateById(submission);
        }
        // 死信为终态 → 从待判队列摘除（与 JudgeEngine.failTask 的死信分支对称）。
        // 摘除走 SubmissionService.rejudge 会重新 add，因此不会影响重判。
        redis.opsForZSet().remove(JudgeRedisKeys.JUDGE_QUEUE_ZSET, String.valueOf(task.getSubmissionId()));
        publishDlqOnce(task, reason);
    }

    /** 死信投递（SETNX 去重：同一任务 24h 内只投一次业务 DLQ） */
    private void publishDlqOnce(JudgeTask task, String reason) {
        String flag = JudgeRedisKeys.TASK_DLQ_SENT_PREFIX + task.getId();
        Boolean first = redis.opsForValue().setIfAbsent(flag, "1", DLQ_FLAG_TTL);
        if (!Boolean.TRUE.equals(first)) {
            return;
        }
        SubmissionResultMessage msg = new SubmissionResultMessage();
        msg.setSubmissionId(task.getSubmissionId());
        msg.setTaskId(task.getId());
        msg.setVerdict(SubmissionService.VERDICT_SE);
        msg.setScore(0);
        Submission submission = submissionMapper.selectById(task.getSubmissionId());
        if (submission != null) {
            msg.setUserId(submission.getUserId());
            msg.setProblemId(submission.getProblemId());
            msg.setContestId(submission.getContestId());
        }
        msg.setPassedCount(0);
        msg.setTotalCount(0);
        // reason 附着在 verdict 之外 —— 消息体里没有专门字段，借 passedCount 之外补一个日志层追溯
        log.error("投递业务死信：topic={} tag={} taskId={} submissionId={} reason={}",
                MqTopics.TOPIC_JUDGE_DLQ, MqTopics.Tags.SUBMISSION_DEAD, task.getId(), task.getSubmissionId(), reason);
        boolean ok = mqTemplate.send(MqTopics.TOPIC_JUDGE_DLQ, MqTopics.Tags.SUBMISSION_DEAD, msg);
        if (!ok) {
            // 投递失败：删除标记，下一轮死信扫描重试
            redis.delete(flag);
            log.error("业务死信投递失败，等待下轮扫描重试 taskId={}", task.getId());
        }
    }

    private void resetSubmissionToPending(Long submissionId) {
        Submission submission = submissionMapper.selectById(submissionId);
        if (submission != null && SubmissionService.ST_JUDGING.equals(submission.getStatus())) {
            submission.setStatus(SubmissionService.ST_PENDING);
            submissionMapper.updateById(submission);
        }
    }
}
