package com.codejudge.api.dto.submission;

import lombok.Data;

import java.io.Serializable;

/**
 * 判题结果事件（topic=judge_submission，Tag=RESULT）。
 *
 * <p>生产方：judge-worker（结果落库成功后发布）。
 * <p>消费方：P4 的 judge-contest（榜单更新）与 WebSocket 推送；P3 仅落库 + 事件发布，
 * 无消费者时消息在 broker 中无订阅即过期，无副作用。
 */
@Data
public class SubmissionResultMessage implements Serializable {

    /** 提交 id */
    private Long submissionId;

    /** 判题任务 id */
    private Long taskId;

    /** 提交人 */
    private Long userId;

    /** 题目 id */
    private Long problemId;

    /** 竞赛 id（0=非竞赛提交） */
    private Long contestId;

    /** 判题结论：AC/WA/TLE/MLE/RE/CE/SE */
    private String verdict;

    /** 得分（AC 用例分值之和） */
    private Integer score;

    /** 全部用例中最大耗时(ms) */
    private Integer timeMs;

    /** 全部用例中最大内存(KB) */
    private Integer memoryKb;

    /** 通过用例数 */
    private Integer passedCount;

    /** 总用例数 */
    private Integer totalCount;

    /**
     * 提交时间（epoch 毫秒）。
     *
     * <p>为什么必须带它：竞赛榜的 ACM 罚时要以**提交时刻**为准（ICPC 规则：AC 时间 =
     * 该次 AC 提交的提交时间），而不是判题完成时刻。判题耗时（尤其是排在队列里等了几分钟）
     * 若被算进罚时，同一份代码在负载高时会被多罚几分钟，排行就不公平了。
     *
     * <p>老消息可能没有该字段，消费端回落到「当前时间」并记录告警。
     */
    private Long submitTimeEpochMs;
}
