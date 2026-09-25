package com.codejudge.problem.domain.vo;

import com.codejudge.problem.domain.po.TestCase;
import lombok.Data;

/**
 * 测试用例视图
 *
 * <p>下发给**学员**时只会出现 {@code isHidden = 0} 的样例（由 Service 过滤），
 * 隐藏用例的 stdin / expectedStdout 绝不能出现在非归属教师或学员的响应体里。
 */
@Data
public class TestCaseVO {

    private Long id;
    private Long problemId;

    /** 执行顺序 */
    private Integer seq;

    /** 标准输入 */
    private String stdin;

    /** 期望输出 */
    private String expectedStdout;

    /** 是否隐藏：0-可见(样例) 1-隐藏 */
    private Integer isHidden;

    /** 该用例分值 */
    private Integer score;

    /** 用例级时间限制(ms)，NULL 表示沿用题目限制 */
    private Integer timeLimitMs;

    /** 比对模式：0-精确 1-浮点容差 2-特判 */
    private Integer judgeMode;

    public static TestCaseVO of(TestCase c) {
        TestCaseVO vo = new TestCaseVO();
        vo.setId(c.getId());
        vo.setProblemId(c.getProblemId());
        vo.setSeq(c.getSeq());
        vo.setStdin(c.getStdin());
        vo.setExpectedStdout(c.getExpectedStdout());
        vo.setIsHidden(c.getIsHidden());
        vo.setScore(c.getScore());
        vo.setTimeLimitMs(c.getTimeLimitMs());
        vo.setJudgeMode(c.getJudgeMode());
        return vo;
    }
}
