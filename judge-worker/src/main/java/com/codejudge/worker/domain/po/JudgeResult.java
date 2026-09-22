package com.codejudge.worker.domain.po;

import com.baomidou.mybatisplus.annotation.TableName;
import com.codejudge.common.domain.BasePO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 逐用例判题结果（worker 视角映射）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("judge_result")
public class JudgeResult extends BasePO {

    private Long submissionId;

    private Long taskId;

    private Long caseId;

    private Integer seq;

    private String verdict;

    private Integer timeMs;

    private Integer memoryKb;

    private String outputDigest;

    private String stderrDigest;
}
