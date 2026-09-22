package com.codejudge.worker.domain.po;

import com.baomidou.mybatisplus.annotation.TableName;
import com.codejudge.common.domain.BasePO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 编译信息（worker 视角映射）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("compile_info")
public class CompileInfo extends BasePO {

    private Long submissionId;

    /** 0否/1是 */
    private Integer success;

    private String stdoutLog;

    private String stderrLog;

    private Integer durationMs;
}
