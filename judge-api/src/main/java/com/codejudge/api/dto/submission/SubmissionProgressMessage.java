package com.codejudge.api.dto.submission;

import lombok.Data;

import java.io.Serializable;

/**
 * 判题进度事件（topic=judge_submission，Tag=PROGRESS）。
 *
 * <p>生产方：judge-worker（认领成功、逐用例完成时发布）。
 * <p>消费方：judge-submission 的 WS 推送（消费组与 worker 自身不同，互不影响）。
 *
 * <p><b>为什么需要它</b>：P3 只发布终态 RESULT，用户在长判题（多用例 + 编译）期间
 * 看不到任何反馈，只能反复轮询提交详情。进度事件让前端能真正「秒级」显示
 * 「正在评测第 3/17 个用例」。
 *
 * <p><b>安全约束（不可放宽）</b>：本消息**不得携带任何用例输入/期望输出/实际输出摘要**。
 * 隐藏用例的答案泄漏是判题平台的致命缺陷，而进度推送是面向提交者本人的公开通道 ——
 * 因此这里只有「序号 / 结论 / 耗时 / 内存 / 通过数」，没有 digest 字段。
 * 需要摘要只能走 REST 详情接口，那里有按角色的遮蔽逻辑。
 */
@Data
public class SubmissionProgressMessage implements Serializable {

    /** 状态：任务已认领，开始编译/评测 */
    public static final String ST_JUDGING = "JUDGING";
    /** 状态：编译完成（仅编译型语言会发） */
    public static final String ST_COMPILED = "COMPILED";
    /** 状态：某个用例执行完成 */
    public static final String ST_CASE_DONE = "CASE_DONE";
    /** 状态：判题结束（与 RESULT 同义，保留给「先到而终态尚未落库」的进度末帧） */
    public static final String ST_FINISHED = "FINISHED";

    /** 提交 id */
    private Long submissionId;

    /** 判题任务 id */
    private Long taskId;

    /** 提交人（WS 推送端据此做归属校验） */
    private Long userId;

    /** 题目 id */
    private Long problemId;

    /** 竞赛 id（0=非竞赛提交） */
    private Long contestId;

    /** 进度状态，取值见本类常量 */
    private String stage;

    /** 当前完成到第几个用例（从 1 开始；未开始为 0） */
    private Integer caseSeq;

    /** 用例总数 */
    private Integer totalCases;

    /** 已通过用例数 */
    private Integer passedCount;

    /** 本用例结论（CASE_DONE 时有值；AC/WA/TLE/MLE/RE） */
    private String verdict;

    /** 本用例耗时(ms) */
    private Integer timeMs;

    /** 本用例内存(KB) */
    private Integer memoryKb;

    /** 是否提前短路（首个非 AC 用例后不再执行剩余用例） */
    private Boolean shortCircuited;

    /** 人类可读描述（前端直接展示，不做语义解析） */
    private String message;

    /** 进度百分比 0~100（按用例数折算；编译阶段折算为 5%） */
    private Integer progress;
}
