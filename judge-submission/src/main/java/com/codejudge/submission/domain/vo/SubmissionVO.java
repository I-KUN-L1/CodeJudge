package com.codejudge.submission.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 提交列表项 / 提交受理返回。
 */
@Data
public class SubmissionVO {

    private Long id;

    private Long userId;

    private Long problemId;

    private Long contestId;

    private String language;

    /** 状态：PENDING/JUDGING/SUCCESS/FAILED */
    private String status;

    /** 判题结论：AC/WA/TLE/MLE/RE/CE/SE */
    private String verdict;

    private Integer score;

    private Integer timeMs;

    private Integer memoryKb;

    private LocalDateTime submitTime;

    /** 本次请求是否命中幂等（true=返回的是已有提交，未新建） */
    private Boolean idempotent;
}
