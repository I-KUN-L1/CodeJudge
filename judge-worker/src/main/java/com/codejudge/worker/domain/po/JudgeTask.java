package com.codejudge.worker.domain.po;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.codejudge.common.domain.BasePO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 判题任务（worker 视角的瘦身映射，与 judge-submission 的同名 PO 对应同一张表）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("judge_task")
public class JudgeTask extends BasePO {

    private Long submissionId;

    private String workerId;

    /** PENDING/JUDGING/SUCCESS/FAILED/DEAD */
    private String status;

    private Integer attempt;

    private Integer maxAttempt;

    private Integer timeoutMs;

    private String leaseOwner;

    private LocalDateTime leaseExpireAt;

    private LocalDateTime nextRetryAt;

    private String errorMsg;
}
