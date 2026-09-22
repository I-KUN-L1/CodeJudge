package com.codejudge.api.dto.problem;

import lombok.Data;

import java.io.Serializable;

/**
 * 题目摘要（judge-problem 内部端点 {@code /internal/problems/summaries} 返回）。
 *
 * <p>与 {@link JudgeInfoDTO} 的区别：<b>不含任何测试用例</b>。
 * 竞赛编排只需确认「题目存在且已发布」，顺手拿个标题用于展示；
 * 若复用 judge-info 端点，就会为了校验一个 id 而把隐藏用例（题目答案）在网络上传一遍
 * —— 数据最小化原则在这里是硬要求，而不是风格偏好。
 */
@Data
public class ProblemSummaryDTO implements Serializable {

    private Long problemId;

    private String title;

    /** 0-草稿 1-已发布 2-已下线 */
    private Integer status;

    private Integer difficulty;

    /** 归属教师 id（竞赛建题时用于提示越权编排） */
    private Long ownerId;
}
