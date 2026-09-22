package com.codejudge.problem.domain.dto;

import lombok.Data;

/**
 * 测试用例表单（新增 / 修改共用）
 */
@Data
public class TestCaseFormDTO {

    /**
     * 执行顺序，从 1 开始。
     * <p>新增时可不传，由服务端取当前题目用例最大 seq + 1 补齐；
     * 修改时以路径上的用例 id 为准，本字段可空。
     */
    private Integer seq;

    /** 标准输入 */
    private String stdin;

    /** 期望输出 */
    private String expectedStdout;

    /** 是否隐藏：0-可见(样例) 1-隐藏；缺省 0 */
    private Integer isHidden;

    /** 该用例分值，缺省 0 */
    private Integer score;

    /** 用例级时间限制(ms)，NULL 表示沿用题目限制 */
    private Integer timeLimitMs;

    /** 比对模式：0-精确 1-浮点容差 2-特判，缺省 0 */
    private Integer judgeMode;
}
