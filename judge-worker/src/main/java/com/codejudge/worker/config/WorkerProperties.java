package com.codejudge.worker.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 判题机参数（judge-worker 侧）。
 *
 * <p>注册方式：经 WorkerApplication 的 @EnableConfigurationProperties 装配
 * （勿再加 @Component —— 双重注册会产生两个 Bean 导致注入冲突，实测踩坑 2026-09-20）。
 */
@Data
@ConfigurationProperties(prefix = "cj.worker")
public class WorkerProperties {

    /** 4 个语言判题镜像（.env CJ_SANDBOX_IMAGE_* 注入） */
    private String imageJava = "judge-java21:latest";
    private String imagePython = "judge-python3.12:latest";
    private String imageCpp = "judge-gcc13:latest";
    private String imageGo = "judge-go1.22:latest";

    /** 运行时：runc（默认）/ runsc（gVisor，需另行安装） */
    private String sandboxRuntime = "runc";

    /** seccomp profile 路径（Docker CLI 在客户端读取内容后发给 daemon，路径只要求本机可读） */
    private String seccompProfile = "sandbox/seccomp/judge-seccomp.json";

    /** 每容器 CPU 限额 */
    private double cpus = 1.0;

    /** **运行阶段**单容器进程数上限（防 fork 炸弹；只约束用户代码） */
    private int pidsLimit = 64;

    /**
     * **编译阶段**单容器进程数上限。
     *
     * <p>为什么必须放宽：编译器自身是重度 fork 的程序 —— Go 工具链一次构建会并发调起
     * 数十个 {@code compile/asm/link} 子进程，实测 {@code --pids-limit 64} 下稳定失败：
     * <pre>go: error obtaining buildID for go tool compile:
     * fork/exec /usr/local/go/pkg/tool/linux_amd64/compile: resource temporarily unavailable</pre>
     * 实测 256 起可正常构建。放宽只作用于**编译阶段**：那时跑的是编译器而不是用户代码，
     * fork 炸弹风险远低于运行期；运行期仍用 {@link #pidsLimit}（64）严守。
     */
    private int compilePidsLimit = 256;

    /** 输出上限 KB（stdout/stderr 各自截断，防输出爆炸） */
    private int maxOutputKb = 64;

    /** 编译阶段超时 */
    private long compileTimeoutMs = 30_000;

    /** 编译阶段内存上限（MB） */
    private int compileMemoryMb = 512;

    /** worker 侧兜底墙钟 = 用例时限 + 该宽限（容器启动/调度开销），超时强杀 */
    private long wallClockGraceMs = 5_000;

    /** 心跳间隔（秒） */
    private int heartbeatIntervalSeconds = 10;

    /** 心跳 TTL（秒），过期即判定离线 */
    private int heartbeatTtlSeconds = 30;

    /** 判题工作根目录（每次判题在其下建独立 workdir，结束即删） */
    private String workRoot = "logs/worker-work";

    /**
     * 判题进度推送的最小间隔（毫秒）。
     *
     * <p>这是「推送频率控制」在**生产端**的闸门：一个 20 用例的题目会产生 20 个进度点，
     * 若逐条发 MQ，一场比赛的 MQ 消息量会被进度事件淹没（真正重要的 RESULT 反而排队）。
     * 因此进度按「距上次推送超过该间隔」才发，且**末用例强制发送**，
     * 保证客户端最终一定收到 100%。
     */
    private long progressMinIntervalMs = 800;

    /** 是否发布判题进度事件（关闭后仅保留终态 RESULT） */
    private boolean progressEnabled = true;
}
