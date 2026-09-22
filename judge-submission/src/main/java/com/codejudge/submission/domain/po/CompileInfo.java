package com.codejudge.submission.domain.po;

import com.baomidou.mybatisplus.annotation.TableName;
import com.codejudge.common.domain.BasePO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 编译信息（每个提交至多一条，uk_compile_info_submission 唯一）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("compile_info")
public class CompileInfo extends BasePO {

    /** 提交 id */
    private Long submissionId;

    /** 编译是否成功：0否/1是 */
    private Integer success;

    /** 编译标准输出（截断保护） */
    private String stdoutLog;

    /** 编译错误输出（CE 时用于前端展示与后续 AI 诊断） */
    private String stderrLog;

    /** 编译耗时 */
    private Integer durationMs;
}
