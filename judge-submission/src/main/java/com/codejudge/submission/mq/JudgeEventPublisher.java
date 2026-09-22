package com.codejudge.submission.mq;

import com.codejudge.api.dto.submission.SubmissionTaskMessage;
import com.codejudge.common.mq.MqTopics;
import com.codejudge.common.mq.RocketMQTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 判题链路 MQ 发布器（judge-submission 侧统一出口）。
 *
 * <p>所有 topic/tag 取自 {@link MqTopics}，禁止散落硬编码。发送失败不抛出 ——
 * 判题任务落库在先，发送失败由补偿调度器（JudgeCompensationService）按
 * 「PENDING 滞留」扫描重发，最终一致。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JudgeEventPublisher {

    /** RocketMQ 延迟级别 2 = 5s：重试任务的退避延迟 */
    public static final int RETRY_DELAY_LEVEL = 2;

    private final RocketMQTemplate mqTemplate;

    /** 首次投递：Tag CREATED */
    public void publishTaskCreated(Long submissionId, Long taskId, int attempt) {
        SubmissionTaskMessage msg = new SubmissionTaskMessage();
        msg.setSubmissionId(submissionId);
        msg.setTaskId(taskId);
        msg.setAttempt(attempt);
        msg.setSource(MqTopics.Tags.SUBMISSION_CREATED);
        boolean ok = mqTemplate.send(MqTopics.TOPIC_JUDGE_SUBMISSION, MqTopics.Tags.SUBMISSION_CREATED, msg);
        log.info("投递判题任务：submissionId={} taskId={} attempt={} ok={}", submissionId, taskId, attempt, ok);
    }

    /** 重试投递：Tag RETRY，5s 延迟（避免刚失败立刻重打满载 worker） */
    public void publishRetry(Long submissionId, Long taskId, int attempt, String reason) {
        SubmissionTaskMessage msg = new SubmissionTaskMessage();
        msg.setSubmissionId(submissionId);
        msg.setTaskId(taskId);
        msg.setAttempt(attempt);
        msg.setSource(MqTopics.Tags.SUBMISSION_RETRY);
        msg.setReason(reason);
        boolean ok = mqTemplate.send(MqTopics.TOPIC_JUDGE_SUBMISSION, MqTopics.Tags.SUBMISSION_RETRY, msg,
                RETRY_DELAY_LEVEL);
        log.info("投递判题重试：submissionId={} taskId={} attempt={} reason={} ok={}",
                submissionId, taskId, attempt, reason, ok);
    }
}
