package com.codejudge.worker.sandbox;

/**
 * 沙箱基础设施异常（SandboxExecutor.available 失败等场景）。
 */
public class SandboxException extends RuntimeException {

    public SandboxException(String message) {
        super(message);
    }

    public SandboxException(String message, Throwable cause) {
        super(message, cause);
    }
}
