package com.codejudge.api.dto.problem;

import lombok.Data;

import java.io.Serializable;

/**
 * 判题用例信息（judge-problem → judge-submission / judge-worker 内部传输）。
 *
 * <p>安全边界：本 DTO 仅经 <b>内部 Feign</b>（{@code InternalOnlyGuard} 保护的端点）流转，
 * 严禁出现在任何对外接口的返回值中 —— 隐藏用例的 {@code stdin}/{@code expectedStdout}
 * 就是题目的"答案"，一旦从对外接口泄露，隐藏用例机制即失效。
 */
@Data
public class JudgeCaseDTO implements Serializable {

    /** 用例 id（对应 judge_problem.test_case.id） */
    private Long caseId;

    /** 执行顺序，从 1 开始 */
    private Integer seq;

    /** 标准输入 */
    private String stdin;

    /** 期望输出 */
    private String expectedStdout;

    /** 是否隐藏用例：0-可见 1-隐藏 */
    private Integer isHidden;

    /** 该用例分值 */
    private Integer score;

    /** 用例级时间限制(ms)覆盖；null 沿用题目限制 */
    private Integer timeLimitMs;

    /** 比对模式：0-精确 1-浮点容差 2-特判 */
    private Integer judgeMode;
}
