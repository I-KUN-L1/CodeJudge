package com.codejudge.problem.domain.po;

import com.baomidou.mybatisplus.annotation.TableName;
import com.codejudge.common.domain.BasePO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 测试用例
 *
 * <p>对应表 {@code test_case}。用例挂在**题目**上而非题面版本上：
 * 改题面不应导致已调好的用例集失效，反之增加用例也不必升版本。
 *
 * <p>安全要点：{@link #isHidden} = 1 的隐藏用例只在「题目归属教师」或「管理员」视角下发，
 * 学员视角的题目详情只返回 {@code is_hidden = 0} 的样例 —— 否则隐藏用例等价于答案泄露。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("test_case")
public class TestCase extends BasePO {

    /** 题目 id */
    private Long problemId;

    /** 执行顺序，从 1 开始；与 problem_id 组成唯一键 */
    private Integer seq;

    /** 标准输入 */
    private String stdin;

    /** 期望输出 */
    private String expectedStdout;

    /** 是否隐藏：0-可见(样例) 1-隐藏 */
    private Integer isHidden;

    /** 该用例分值（IOI 赛制计分用） */
    private Integer score;

    /** 用例级时间限制(ms)，NULL 表示沿用题目限制 */
    private Integer timeLimitMs;

    /** 比对模式：0-精确 1-浮点容差 2-特判 */
    private Integer judgeMode;
}
