package com.codejudge.worker.sandbox;

import lombok.Data;

/**
 * 一次沙箱执行结果。
 */
@Data
public class SandboxResult {

    /** 用户程序退出码（来自 run.sh 内层协议；容器被 OOM 杀死时为 137） */
    private int exitCode;

    /** 程序实测耗时(ms)（容器内 date +%s%N 计时，剔除容器启动开销） */
    private int timeMs;

    /** 容器 cgroup 内存峰值(KB)（/sys/fs/cgroup/memory.peak，cgroup v2） */
    private long memKb;

    /** worker 侧墙钟兜底强杀（视为 TLE） */
    private boolean timedOut;

    /** 容器 OOM kill（exit 137，视为 MLE） */
    private boolean oomKilled;

    /** 程序 stdout（已截断） */
    private String stdout;

    /** 程序 stderr（已截断） */
    private String stderr;

    /** 沙箱自身失败（docker 不可用/镜像缺失/容器未跑起来）→ SE，与用户代码错误区分 */
    private boolean sandboxFailed;

    /** 沙箱失败原因（sandboxFailed=true 时有值） */
    private String sandboxError;
}
