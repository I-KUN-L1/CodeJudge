package com.codejudge.worker.sandbox;

/**
 * 一次沙箱执行请求（编译或单用例运行）。
 *
 * @param image        判题镜像（judge-java21 等）
 * @param innerCommand 容器内执行的命令（编译命令或运行命令）
 * @param sourceFile   源码文件名（挂载后位于 /src/{sourceFile}）
 * @param sourceCode   源码全文
 * @param stdin        标准输入内容；null 表示无 stdin（编译阶段）
 * @param timeLimitMs  内层 timeout 时限（容器内精确计时）
 * @param memoryMb     容器内存限额（--memory 与 --memory-swap 同值，禁 swap）
 * @param wallClockMs  worker 侧墙钟兜底（超过强杀容器进程）
 * @param pidsLimit    容器进程数上限（--pids-limit）。**编译与运行必须分开取值**：
 *                     编译器自身要 fork 大量子进程（Go 工具链尤甚），而运行阶段要防 fork 炸弹，
 *                     用同一个值两边必有一边不达标（见 {@code WorkerProperties#compilePidsLimit}）。
 * @param artifactKey  **编译产物共享键**（一般取 {@code submissionId-taskId}）：
 *                     <ul>
 *                       <li>非空 —— {@code /work} 挂到宿主上按该键持久化的目录，编译阶段写入、
 *                           后续每个用例读取，实现「一次编译、多用例复用」；全部用例跑完后
 *                           由调用方调 {@link SandboxExecutor#releaseArtifactDir(String)} 释放；</li>
 *                       <li>null —— {@code /work} 用容器内 tmpfs，随容器销毁
 *                           （PYTHON 无编译阶段，无需复用）。</li>
 *                     </ul>
 *                     历史缺陷：本字段缺位时编译与运行各起一个 {@code --rm} 容器、各用一份
 *                     临时 tmpfs，产物在编译容器退出时即被销毁 ⇒ 编译型语言全部判不出 AC
 *                     （JAVA: Could not find or load main class / CPP: /work/main: not found）。
 */
public record SandboxSpec(String image,
                          String innerCommand,
                          String sourceFile,
                          String sourceCode,
                          String stdin,
                          int timeLimitMs,
                          int memoryMb,
                          long wallClockMs,
                          int pidsLimit,
                          String artifactKey) {
}
