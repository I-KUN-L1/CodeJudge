package com.codejudge.worker.domain.po;

import com.baomidou.mybatisplus.annotation.TableName;
import com.codejudge.common.domain.BasePO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 提交记录（worker 视角的瘦身映射，与 judge-submission 的同名 PO 对应同一张表）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("submission")
public class Submission extends BasePO {

    private Long userId;

    private Long problemId;

    private Long contestId;

    /** JAVA/PYTHON/CPP/GO */
    private String language;

    /** 代码全文 */
    private String code;

    private String codeHash;

    private Integer submitRound;

    /** PENDING/JUDGING/SUCCESS/FAILED */
    private String status;

    /** AC/WA/TLE/MLE/RE/CE/SE */
    private String verdict;

    private Integer score;

    private Integer timeMs;

    private Integer memoryKb;

    private Long compileInfoId;

    private LocalDateTime submitTime;
}
