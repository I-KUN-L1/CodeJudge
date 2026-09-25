package com.codejudge.worker.sandbox;

import com.codejudge.worker.config.WorkerProperties;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 基于 docker CLI 的沙箱执行器（默认实现）。
 *
 * <p><b>隔离清单（12 项要求中的运行时项）</b>：
 * <ol>
 *   <li>每次执行独立容器 + 独立 workdir（tmpfs），结束即删；</li>
 *   <li>{@code --user 1000:1000} 非 root；</li>
 *   <li>{@code --read-only} 根文件系统只读，/tmp 挂 noexec tmpfs；</li>
 *   <li>{@code --network none} 断网；</li>
 *   <li>{@code --cpus} / {@code --memory}+{@code --memory-swap}（同值禁 swap）/ {@code --pids-limit} 防 fork 炸弹；</li>
 *   <li>超时：容器内 {@code timeout} 精确判定 + worker 侧墙钟兜底强杀 → TLE；</li>
 *   <li>容器 OOM（exit 137）→ MLE；</li>
 *   <li>非零退出码 → RE（先于 WA 判定）；</li>
 *   <li>编译失败 → CE（stderr 截断落库）；</li>
 *   <li>{@code no-new-privileges} + 自定义 seccomp（禁 mount/ptrace/reboot 等）+ 宿主目录仅只读投递；</li>
 *   <li>stdout/stderr 双向截断（maxOutputKb），防输出爆炸；</li>
 *   <li>沙箱自身故障折叠为 {@code sandboxFailed=true} → SE，与用户代码结论严格区分。</li>
 * </ol>
 *
 * <p><b>输出协议</b>：run.sh 内层以哨兵行区分"程序输出"与"受控元数据"——
 * {@code __CJ_META__ {json}} 输出退出码/实测耗时/内存峰值，{@code __CJ_STDOUT__} 之后是程序输出。
 * 程序即使伪造哨兵行也只能污染展示摘要（比对仍按全文判 WA），不影响判定正确性。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DockerSandbox implements SandboxExecutor {

    private static final String META_MARK = "__CJ_META__ ";
    private static final String STDOUT_MARK = "__CJ_STDOUT__";
    private static final String STDERR_MARK = "__CJ_STDERR__";
    private static final String END_MARK = "__CJ_END__";

    /** 源码在容器内的挂载点（只读） */
    private static final String CONTAINER_SRC = "/src";

    private final WorkerProperties properties;

    // ==================== SPI 实现 ====================

    @Override
    public boolean available(String image) {
        try {
            ProcessBuilder pb = new ProcessBuilder("docker", "image", "inspect", image);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean done = p.waitFor(10, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            log.warn("沙箱可用性自检失败：image={} err={}", image, e.getMessage());
            return false;
        }
    }

    @Override
    public SandboxResult execute(SandboxSpec spec) {
        Path workDir = null;
        try {
            workDir = createWorkDir();
            // 源码投递（只读挂载）：run.sh 与 stdin 由本类写入，源码按语言档案落盘
            Files.writeString(workDir.resolve(spec.sourceFile()), spec.sourceCode(), StandardCharsets.UTF_8);
            writeRunnerScript(workDir, spec);
            if (spec.stdin() != null) {
                Files.writeString(workDir.resolve("stdin"), spec.stdin(), StandardCharsets.UTF_8);
            }
            return runContainer(workDir, spec);
        } catch (Exception e) {
            SandboxResult r = new SandboxResult();
            r.setSandboxFailed(true);
            r.setSandboxError("沙箱执行异常：" + e.getMessage());
            log.error("沙箱执行异常 image={}", spec.image(), e);
            return r;
        } finally {
            cleanup(workDir);
        }
    }

    // ==================== 容器执行 ====================

    private SandboxResult runContainer(Path workDir, SandboxSpec spec) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.add("run");
        cmd.add("--rm");
        // 命名容器：墙钟兜底强杀时 kill 的只是本机 docker CLI 进程，容器由 dockerd 继续运行
        //（--rm 只在容器自行退出后生效）。有名字才能在超时路径上 docker rm -f 清理残留
        String containerName = "cj-sbx-" + java.util.UUID.randomUUID();
        cmd.add("--name");
        cmd.add(containerName);
        // 运行时选择：runc（默认）/ runsc（gVisor，需本机安装）
        if (!"runc".equals(properties.getSandboxRuntime())) {
            cmd.add("--runtime");
            cmd.add(properties.getSandboxRuntime());
        }
        // 资源限额：CPU / 内存（swap 同值即禁用 swap）/ 进程数
        // 进程数按 spec 取值：编译阶段要放宽（Go 工具链会 fork 上百个子进程），
        //    运行阶段必须收紧防 fork 炸弹 —— 用同一个值必有一边不达标（见 SandboxSpec#pidsLimit）。
        int pidsLimit = spec.pidsLimit() > 0 ? spec.pidsLimit() : properties.getPidsLimit();
        cmd.add("--cpus"); cmd.add(String.valueOf(properties.getCpus()));
        cmd.add("--memory"); cmd.add(spec.memoryMb() + "m");
        cmd.add("--memory-swap"); cmd.add(spec.memoryMb() + "m");
        cmd.add("--pids-limit"); cmd.add(String.valueOf(pidsLimit));
        // 文件系统：只读根 + 可写区（/work 可 exec 供编译产物运行；/tmp noexec）
        cmd.add("--read-only");
        Path artifact = artifactDir(spec.artifactKey());
        if (artifact != null) {
            // 编译型语言：/work 挂宿主持久目录，编译容器写入、各用例容器读取。
            // 若改回容器内 tmpfs，产物会随编译容器退出即销毁，编译型语言全部判不出 AC。
            Files.createDirectories(artifact);
            cmd.add("-v"); cmd.add(artifact.toAbsolutePath() + ":/work:rw");
        } else {
            // 无编译阶段（PYTHON）：容器内 tmpfs 即可，随容器销毁
            cmd.add("--tmpfs"); cmd.add("/work:rw,nosuid,size=128m,mode=1777");
        }
        cmd.add("--tmpfs"); cmd.add("/tmp:rw,nosuid,nodev,noexec,size=32m");
        // 网络：完全断网
        cmd.add("--network"); cmd.add("none");
        // 用户与提权：非 root + 禁止提权 + 丢弃全部 capabilities
        cmd.add("--user"); cmd.add("1000:1000");
        cmd.add("--security-opt"); cmd.add("no-new-privileges");
        cmd.add("--cap-drop"); cmd.add("ALL");
        // seccomp 白名单外收紧：禁 mount/ptrace/reboot/kexec 等危险 syscall
        Path seccomp = resolveSeccompProfile();
        if (seccomp != null) {
            cmd.add("--security-opt"); cmd.add("seccomp=" + seccomp.toAbsolutePath());
        }
        // 宿主目录：仅只读投递（代码 + stdin + run.sh）
        cmd.add("-v"); cmd.add(workDir.toAbsolutePath() + ":" + CONTAINER_SRC + ":ro");
        cmd.add(spec.image());
        cmd.add("/bin/sh"); cmd.add(CONTAINER_SRC + "/run.sh");

        log.debug("沙箱执行：image={} cmd={}", spec.image(), spec.innerCommand());
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        Process process = pb.start();

        OutputCatcher stdoutCatcher = new OutputCatcher(process.getInputStream(), maxOutputBytes());
        OutputCatcher stderrCatcher = new OutputCatcher(process.getErrorStream(), maxOutputBytes());
        Thread t1 = Thread.startVirtualThread(stdoutCatcher);
        Thread t2 = Thread.startVirtualThread(stderrCatcher);

        long wallStart = System.currentTimeMillis();
        boolean finished = process.waitFor(spec.wallClockMs(), TimeUnit.MILLISECONDS);
        if (!finished) {
            // 墙钟兜底强杀：内层 timeout 失效（如 sh 卡死）时的最后防线
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            // destroyForcibly 杀不到容器本体（dockerd 独立进程），必须显式 rm -f，
            // 否则失控容器长期残留、持续占用 CPU/内存（fake TLE 与宿主负载升高的隐形来源）
            try {
                new ProcessBuilder("docker", "rm", "-f", containerName)
                        .start().waitFor(10, TimeUnit.SECONDS);
            } catch (Exception cleanupErr) {
                log.warn("沙箱容器清理失败（可能残留）：name={} err={}", containerName, cleanupErr.toString());
            }
            log.warn("沙箱墙钟超时强杀：image={} wallClockMs={}", spec.image(), spec.wallClockMs());
        }
        long wallMs = System.currentTimeMillis() - wallStart;
        t1.join(2000);
        t2.join(2000);

        String rawStdout = stdoutCatcher.text();
        String rawStderr = stderrCatcher.text();
        return parse(spec, rawStdout, rawStderr, process.exitValue(), wallMs, finished);
    }

    /** 解析内层协议；无 META（容器未跑起来/被 OOM 杀）时按 docker 退出码兜底判定 */
    private SandboxResult parse(SandboxSpec spec, String rawStdout, String rawStderr,
                                int processExit, long wallMs, boolean finishedInTime) {
        SandboxResult r = new SandboxResult();
        String metaJson = extractMeta(rawStdout);
        r.setStdout(extractSection(rawStdout, STDOUT_MARK, STDERR_MARK));
        r.setStderr(truncate(extractSection(rawStdout, STDERR_MARK, END_MARK) + rawStderr));

        if (metaJson != null) {
            // 正常路径：容器内脚本执行完毕
            r.setExitCode((int) intField(metaJson, "exit", processExit));
            r.setTimeMs((int) intField(metaJson, "time_ms", wallMs));
            r.setMemKb(intField(metaJson, "mem_kb", 0));
            if (r.getExitCode() == 137) {
                r.setOomKilled(true);
            }
            return r;
        }

        // META 缺失：容器级异常。区分两类：
        //   a) docker 退出码 0 —— run.sh 已执行但中途死亡（典型：fork 炸弹耗尽 pids 后
        //      shell 连 META 都无法输出），属用户代码导致，判 RE；
        //   b) docker 退出码非 0 且非 OOM —— 沙箱基础设施故障，判 SE。
        r.setExitCode(processExit);
        r.setTimeMs((int) Math.min(wallMs, Integer.MAX_VALUE));
        if (!finishedInTime) {
            r.setTimedOut(true);
            return r;
        }
        if (processExit == 137) {
            r.setOomKilled(true);
            return r;
        }
        if (processExit == 0) {
            r.setExitCode(255); // 约定：脚本中途死亡但无元数据 → 非零（RE）
            r.setStderr("容器执行中断且未产出元数据（疑似进程数/资源耗尽）");
            return r;
        }
        r.setSandboxFailed(true);
        r.setSandboxError("容器未产生执行元数据，docker 退出码 " + processExit
                + "，stderr=" + truncate(rawStderr));
        return r;
    }

    // ==================== run.sh 生成 ====================

    private void writeRunnerScript(Path workDir, SandboxSpec spec) throws IOException {
        // 内层 timeout：容器内精确计时（浮点秒），-k 1 在时限后 1s 强杀
        double secs = Math.max(0.05, spec.timeLimitMs() / 1000.0);
        boolean hasStdin = spec.stdin() != null;
        String script = """
                #!/bin/sh
                # CodeJudge sandbox runner（由 judge-worker 生成，勿手工改动）
                export HOME=/work TMPDIR=/tmp
                IN=%s
                START=$(date +%%s%%N)
                timeout -k 1 %ss sh -c '%s' < "$IN" > /work/stdout 2> /work/stderr
                RC=$?
                END=$(date +%%s%%N)
                MEM=$(cat /sys/fs/cgroup/memory.peak 2>/dev/null || echo 0)
                echo "%s{\\"exit\\":$RC,\\"time_ms\\":$(((END-START)/1000000)),\\"mem_kb\\":$((MEM/1024))}"
                echo "%s"
                cat /work/stdout 2>/dev/null
                echo "%s"
                cat /work/stderr 2>/dev/null
                echo "%s"
                """.formatted(
                hasStdin ? CONTAINER_SRC + "/stdin" : "/dev/null",
                secs,
                spec.innerCommand().replace("'", "'\\''"),
                META_MARK, STDOUT_MARK, STDERR_MARK, END_MARK);
        Path runSh = workDir.resolve("run.sh");
        Files.writeString(runSh, script, StandardCharsets.UTF_8);
        // Windows 文件系统无执行位概念，容器内以 `sh run.sh` 调用，无需 chmod
    }

    // ==================== 工具 ====================

    /**
     * 编译产物共享目录：{@code <workRoot>/artifacts/<key>}。
     *
     * <p>与每次执行即建的 {@code judge-<uuid>} 临时目录不同，它按**判题任务**持久，
     * 使编译阶段与后续每个用例容器看到同一份 {@code /work}。
     *
     * @return {@code key} 为空时返回 null（调用方据此走容器内 tmpfs）
     */
    private Path artifactDir(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        // 键由调用方用雪花 id 拼成；此处再做一次字符白名单收敛，杜绝路径穿越
        String safe = key.replaceAll("[^A-Za-z0-9_-]", "_");
        return Paths.get(properties.getWorkRoot()).toAbsolutePath().resolve("artifacts").resolve(safe);
    }

    @Override
    public void releaseArtifactDir(String artifactKey) {
        Path dir = artifactDir(artifactKey);
        if (dir == null) {
            return;
        }
        // 幂等且不抛：调用点在 finally 中（cleanup 自身已吞掉删除异常）
        cleanup(dir);
    }

    private Path createWorkDir() throws IOException {
        Path root = Paths.get(properties.getWorkRoot()).toAbsolutePath();
        Files.createDirectories(root);
        Path dir = root.resolve("judge-" + UUID.randomUUID());
        Files.createDirectories(dir);
        return dir;
    }

    private void cleanup(Path workDir) {
        if (workDir == null || !Files.exists(workDir)) {
            return;
        }
        try (var paths = Files.walk(workDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 清理失败不影响判题（磁盘残留由运维任务兜底）
                }
            });
        } catch (IOException | UncheckedIOException e) {
            log.warn("清理判题工作目录失败：{} err={}", workDir, e.getMessage());
        }
    }

    private Path resolveSeccompProfile() {
        Path p = Paths.get(properties.getSeccompProfile());
        if (Files.exists(p)) {
            return p;
        }
        Path fallback = Paths.get(".." + "/" + properties.getSeccompProfile());
        if (Files.exists(fallback)) {
            return fallback;
        }
        log.warn("seccomp profile 不存在，本次执行回退为 docker 默认 profile：{}", properties.getSeccompProfile());
        return null;
    }

    private int maxOutputBytes() {
        return properties.getMaxOutputKb() * 1024;
    }

    /** 截断到输出上限（超限尾部以标记结尾） */
    private String truncate(String s) {
        if (s == null) {
            return "";
        }
        int max = maxOutputBytes();
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "\n…[截断，上限 " + properties.getMaxOutputKb() + "KB]";
    }

    private String extractMeta(String raw) {
        if (raw == null) {
            return null;
        }
        int idx = raw.indexOf(META_MARK);
        if (idx < 0) {
            return null;
        }
        int lineEnd = raw.indexOf('\n', idx);
        return lineEnd < 0 ? raw.substring(idx + META_MARK.length()) : raw.substring(idx + META_MARK.length(), lineEnd);
    }

    /** 抽取协议段：MARK 之后到下一标记（或结尾）之间的内容 */
    private String extractSection(String raw, String startMark, String nextMark) {
        if (raw == null) {
            return "";
        }
        int start = raw.indexOf(startMark);
        if (start < 0) {
            return "";
        }
        String body = raw.substring(start + startMark.length());
        int end = body.indexOf(nextMark);
        if (end >= 0) {
            body = body.substring(0, end);
        }
        // 去掉协议本身引入的首个换行（程序输出最后的换行由比对逻辑归一）
        if (body.startsWith("\n")) {
            body = body.substring(1);
        }
        return truncate(body);
    }

    private long intField(String json, String field, long def) {
        var m = java.util.regex.Pattern.compile("\"" + field + "\":(-?\\d+)").matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : def;
    }

    @PreDestroy
    public void shutdown() {
        // docker run --rm 容器随 worker 停止由 Docker Desktop 兜底回收；此处预留运行时清理扩展点
    }

    /**
     * 进程输出异步读取器：先读入上限字节，其余持续排水丢弃 ——
     * 防输出爆炸拖垮 worker 内存，也防子进程因管道写阻塞卡死。
     */
    private static final class OutputCatcher implements Runnable {

        private final java.io.InputStream in;
        private final int maxBytes;
        private final java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();

        OutputCatcher(java.io.InputStream in, int maxBytes) {
            this.in = in;
            this.maxBytes = maxBytes;
        }

        @Override
        public void run() {
            try {
                byte[] head = in.readNBytes(maxBytes);
                synchronized (buf) {
                    buf.write(head);
                }
                in.transferTo(java.io.OutputStream.nullOutputStream());
            } catch (IOException ignored) {
                // 进程结束时的流关闭属正常
            }
        }

        String text() {
            synchronized (buf) {
                return buf.toString(StandardCharsets.UTF_8);
            }
        }
    }
}
