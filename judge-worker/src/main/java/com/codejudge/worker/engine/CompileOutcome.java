package com.codejudge.worker.engine;

import com.codejudge.api.dto.submission.Language;
import lombok.Data;

/**
 * 编译阶段结果。
 */
@Data
public class CompileOutcome {

    private boolean success;

    private String stdoutLog;

    private String stderrLog;

    private int durationMs;

    /** 沙箱自身故障（与 CE 区分，走 SE 重试路径） */
    private boolean sandboxFailed;

    private String sandboxError;

    public static CompileOutcome sandboxError(String error) {
        CompileOutcome o = new CompileOutcome();
        o.setSuccess(false);
        o.setSandboxFailed(true);
        o.setSandboxError(error);
        return o;
    }
}
