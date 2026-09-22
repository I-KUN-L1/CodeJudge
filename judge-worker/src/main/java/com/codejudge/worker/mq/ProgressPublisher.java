package com.codejudge.worker.mq;

import com.codejudge.api.dto.submission.SubmissionProgressMessage;
import com.codejudge.common.mq.MqTopics;
import com.codejudge.common.mq.RocketMQTemplate;
import com.codejudge.worker.config.WorkerProperties;
import com.codejudge.worker.domain.po.Submission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 判题进度事件发布器（topic=judge_submission，Tag=PROGRESS）。
 *
 * <p><b>为什么把限流放在生产端而不是消费端</b>：
 * 进度事件的价值随时间衰减 —— 晚到 2 秒的「第 3/17 个用例完成」已经没有意义。
 * 在 worker 侧直接丢弃（而不是发出去让下游合并）能同时省下 MQ 写入、网络、订阅端三处成本，
 * 这是「无用数据不入链路」与「下游缓冲」的区别。
 *
 * <p><b>限流规则</b>：
 * <ul>
 *   <li>距上次推送 &lt; {@code cj.worker.progress-min-interval-ms} 的中间态直接丢弃；</li>
 *   <li>**末用例与短路用例强制推送** —— 否则客户端会停在 90% 永远等不到 100%；</li>
 *   <li>发送失败不影响判题：进度是旁路通知，终态 RESULT 才是权威。</li>
 * </ul>
 *
 * <p>线程安全：一个 worker 会并发处理多个任务（虚拟线程），因此按 submissionId 分桶记录
 * 上次推送时间，避免不同任务互相压制。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProgressPublisher {

    private final RocketMQTemplate mqTemplate;
    private final WorkerProperties properties;

    /** submissionId -> 上次推送时间戳（毫秒） */
    private final Map<Long, Long> lastPushAt = new ConcurrentHashMap<>();

    /**
     * 发布进度。
     *
     * @param force true=忽略限流强制发送（末用例、短路、阶段切换等关键帧）
     */
    public void publish(Submission submission, Long taskId, SubmissionProgressMessage msg, boolean force) {
        if (!properties.isProgressEnabled() || submission == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastPushAt.get(submission.getId());
        if (!force && last != null && now - last < properties.getProgressMinIntervalMs()) {
            return;
        }
        lastPushAt.put(submission.getId(), now);

        msg.setSubmissionId(submission.getId());
        msg.setTaskId(taskId);
        msg.setUserId(submission.getUserId());
        msg.setProblemId(submission.getProblemId());
        msg.setContestId(submission.getContestId());
        boolean ok = mqTemplate.send(MqTopics.TOPIC_JUDGE_SUBMISSION, MqTopics.Tags.SUBMISSION_PROGRESS, msg);
        if (!ok) {
            log.debug("进度事件发送失败（不影响判题）：submissionId={} stage={}", submission.getId(), msg.getStage());
        } else {
            log.debug("进度事件已发送：submissionId={} stage={} {}/{} verdict={}",
                    submission.getId(), msg.getStage(), msg.getCaseSeq(), msg.getTotalCases(), msg.getVerdict());
        }
    }

    /**
     * 清理该提交的限流状态。
     *
     * <p>必须在终态之后调用：否则长跑 worker 的 Map 会随提交数无限增长（内存泄漏），
     * 且同一 submissionId 若被重判复用，会残留旧的限流时间戳。
     */
    public void clear(Long submissionId) {
        if (submissionId != null) {
            lastPushAt.remove(submissionId);
        }
    }
}
