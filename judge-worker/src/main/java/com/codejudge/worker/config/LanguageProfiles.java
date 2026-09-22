package com.codejudge.worker.config;

import com.codejudge.api.dto.submission.Language;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 语言执行档案：镜像选择、编译/运行命令模板。
 *
 * <p>沙箱约束下统一的运行前提（由 DockerSandbox 注入）：
 * <ul>
 *   <li>源码只读挂载在 {@code /src}（含 stdin 文件与 run.sh）；</li>
 *   <li>可写区仅 {@code /work}（tmpfs，可 exec）与 {@code /tmp}（tmpfs，noexec）；</li>
 *   <li>容器以 uid 1000 运行，HOME=/work。</li>
 * </ul>
 * 时间限制由 run.sh 内层 {@code timeout} 精确执行（容器内计时，剔除容器启动开销），
 * worker 侧另有墙钟兜底强杀。
 */
@Component
public class LanguageProfiles {

    /**
     * 编译命令（sh -c 执行）。PYTHON 无编译阶段。
     * 编译输出统一写到 /work，跨用例复用（一次编译，N 次运行）。
     */
    public String compileCommand(Language language) {
        return switch (language) {
            case JAVA -> "javac -J-XX:-UsePerfData -d /work /src/Main.java";
            case CPP -> "g++ -O2 -std=c++17 -pipe -o /work/main /src/main.cpp";
            // Go 单文件构建：GOFLAGS 清空、GOPROXY=off 断网编译，GOCACHE 放可写区
            case GO -> "GOCACHE=/work/.gocache GOPROXY=off GOFLAGS= go build -o /work/main /src/main.go";
            case PYTHON -> null;
        };
    }

    /**
     * 运行命令（run.sh 中由内层 timeout 包裹执行）。stdin/stdout 重定向由 run.sh 负责。
     * JVM 侧：-Xmx 按题目内存限额收紧（容器限额之上的二级保险），关闭 perf 数据文件。
     */
    public String runCommand(Language language, int memoryLimitMb) {
        long xmx = Math.max(32, memoryLimitMb - 64);
        return switch (language) {
            case JAVA -> "java -XX:-UsePerfData -XX:+UseSerialGC -Xss64m -Xmx" + xmx + "m -cp /work Main";
            case PYTHON -> "python3 -I /src/main.py";
            case CPP -> "/work/main";
            case GO -> "/work/main";
        };
    }

    public String image(WorkerProperties properties, Language language) {
        Map<Language, String> images = Map.of(
                Language.JAVA, properties.getImageJava(),
                Language.PYTHON, properties.getImagePython(),
                Language.CPP, properties.getImageCpp(),
                Language.GO, properties.getImageGo()
        );
        return images.get(language);
    }
}
