package com.codejudge.api.dto.submission;

import lombok.Data;

import java.io.Serializable;

/**
 * 判题任务消息（topic=judge_submission，Tag=CREATED/RETRY）。
 *
 * <p>生产方：judge-submission（提交落库后发 CREATED；重判/补偿调度发 RETRY）。
 * <p>消费方：judge-worker。worker 以 {@code taskId + attempt} 做数据库租约 CAS 认领，
 * 天然幂等 —— 同一消息被重复投递（MQ at-least-once）时，只有一次认领会成功。
 */
@Data
public class SubmissionTaskMessage implements Serializable {

    /** 提交 id */
    private Long submissionId;

    /** 判题任务 id */
    private Long taskId;

    /** 期望的任务重试轮次（worker 认领时与 judge_task.attempt 比对，防旧消息覆盖新状态） */
    private Integer attempt;

    /** 消息来源：CREATED-首次投递 / RETRY-重试投递 */
    private String source;

    /** 重试原因（RETRY 时有值，便于排查） */
    private String reason;
}
