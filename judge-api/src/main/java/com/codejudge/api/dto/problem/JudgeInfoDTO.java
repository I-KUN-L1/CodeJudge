package com.codejudge.api.dto.problem;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 判题题目信息（judge-problem 内部端点 /internal/problems/{id}/judge-info 返回）。
 *
 * <p>消费方：
 * <ul>
 *   <li>judge-submission：提交时校验题目存在且已发布（{@link #status} == 1）与语言限制；</li>
 *   <li>judge-worker：判题前拉取时间/内存限制与全部测试用例（含隐藏用例）。</li>
 * </ul>
 */
@Data
public class JudgeInfoDTO implements Serializable {

    /** 题目 id */
    private Long problemId;

    /** 题目标题 */
    private String title;

    /** 题目状态：0-草稿 1-已发布 2-已下线 */
    private Integer status;

    /** 题目归属教师 id（重判归属校验用） */
    private Long ownerId;

    /** 默认时间限制(ms) */
    private Integer timeLimitMs;

    /** 内存限制(MB) */
    private Integer memoryLimitMb;

    /** 全部测试用例（按 seq 升序，含隐藏用例） */
    private List<JudgeCaseDTO> testCases;
}
