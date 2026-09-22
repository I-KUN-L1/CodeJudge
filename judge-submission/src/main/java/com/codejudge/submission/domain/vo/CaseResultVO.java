package com.codejudge.submission.domain.vo;

import lombok.Data;

/**
 * 单用例判题结果（学员视角下隐藏用例仅保留结论类字段，摘要被屏蔽）。
 */
@Data
public class CaseResultVO {

    private Long caseId;

    private Integer seq;

    /** 该用例结论：AC/WA/TLE/MLE/RE */
    private String verdict;

    private Integer timeMs;

    private Integer memoryKb;

    /** 实际输出摘要（隐藏用例对学员为 null） */
    private String outputDigest;

    /** 错误输出摘要（隐藏用例对学员为 null） */
    private String stderrDigest;

    /** 该用例是否为隐藏用例（仅教师/管理员视角为 true，学员视角不区分） */
    private Boolean hidden;
}
