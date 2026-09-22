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
 */
public record SandboxSpec(String image,
                          String innerCommand,
                          String sourceFile,
                          String sourceCode,
                          String stdin,
                          int timeLimitMs,
                          int memoryMb,
                          long wallClockMs) {
}
