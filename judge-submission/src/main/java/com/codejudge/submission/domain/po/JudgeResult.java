package com.codejudge.submission.domain.po;

import com.baomidou.mybatisplus.annotation.TableName;
import com.codejudge.common.domain.BasePO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 逐用例判题结果。
 *
 * <p>安全：{@code outputDigest}/{@code stderrDigest} 只存截断摘要（512 字符），
 * 且对学员视角下隐藏用例（case 属 is_hidden=1）不下发摘要 —— 防止用摘要做逐字节
 * 猜测攻击（oracle attack）反推隐藏用例答案。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("judge_result")
public class JudgeResult extends BasePO {

    /** 提交 id */
    private Long submissionId;

    /** 判题任务 id */
    private Long taskId;

    /** 用例 id（test_case.id；用例物理删除后审计链断裂，属 P2 已知取舍） */
    private Long caseId;

    /** 用例执行序号 */
    private Integer seq;

    /** 该用例结论：AC/WA/TLE/MLE/RE */
    private String verdict;

    /** 该用例耗时(ms) */
    private Integer timeMs;

    /** 该用例内存峰值(KB) */
    private Integer memoryKb;

    /** 实际输出摘要（截断） */
    private String outputDigest;

    /** 错误输出摘要 */
    private String stderrDigest;
}
