package com.codejudge.worker.sandbox;

/**
 * 沙箱执行器 SPI。
 *
 * <p>同一接口下的实现可替换底层运行时（PLAN §6）：
 * <ul>
 *   <li>{@link DockerSandbox} —— 默认实现，docker CLI + 加固 runc + seccomp（本机可用）；</li>
 *   <li>未来 GvisorSandbox —— SANDBOX_RUNTIME=runsc 时切换，上层代码零改动。</li>
 * </ul>
 */
public interface SandboxExecutor {

    /**
     * 在隔离沙箱中执行一次命令。
     *
     * <p>实现必须满足的隔离底线（12 项清单的运行时子集）：
     * 独立容器/命名空间、非 root 用户、只读根文件系统、无网络、
     * CPU/内存/进程数限额、超时强杀、输出截断、宿主目录只读投递。
     *
     * @throws SandboxException 沙箱基础设施异常（docker 命令不可用等）—— 实现应捕获
     *                          一切内部异常并折叠为 {@code sandboxFailed=true} 的结果而非抛出
     */
    SandboxResult execute(SandboxSpec spec);

    /** 沙箱可用性自检（启动时与判题前调用）：docker 可达 + 指定镜像存在 */
    boolean available(String image);
}
