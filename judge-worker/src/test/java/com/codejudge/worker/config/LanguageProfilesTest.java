package com.codejudge.worker.config;

import com.codejudge.api.dto.submission.Language;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LanguageProfiles 单测（沙箱命令模板与镜像映射契约）。
 *
 * <p>运行：mvn -pl judge-worker -am test
 *
 * <p>覆盖：各语言编译/运行命令模板（DockerSandbox 与 run.sh 依赖这些字面量拼接，
 * 改动必须显式过测）、PYTHON 无编译阶段（null）、JVM -Xmx 收紧公式、镜像映射。
 */
class LanguageProfilesTest {

    private LanguageProfiles profiles;
    private WorkerProperties properties;

    @BeforeEach
    void setUp() {
        profiles = new LanguageProfiles();
        properties = new WorkerProperties();
    }

    @Nested
    @DisplayName("编译命令")
    class Compile {

        @Test
        @DisplayName("JAVA：javac 编译到 /work，源码在 /src")
        void java() {
            assertThat(profiles.compileCommand(Language.JAVA))
                    .isEqualTo("javac -J-XX:-UsePerfData -d /work /src/Main.java");
        }

        @Test
        @DisplayName("CPP：g++ C++17 输出 /work/main")
        void cpp() {
            assertThat(profiles.compileCommand(Language.CPP))
                    .isEqualTo("g++ -O2 -std=c++17 -pipe -o /work/main /src/main.cpp");
        }

        @Test
        @DisplayName("GO：断网编译（GOPROXY=off），GOCACHE 在可写区")
        void go() {
            assertThat(profiles.compileCommand(Language.GO))
                    .isEqualTo("GOCACHE=/work/.gocache GOPROXY=off GOFLAGS= go build -o /work/main /src/main.go");
        }

        @Test
        @DisplayName("PYTHON：无编译阶段 → null")
        void pythonHasNoCompile() {
            assertThat(profiles.compileCommand(Language.PYTHON)).isNull();
        }
    }

    @Nested
    @DisplayName("运行命令与 -Xmx 收紧公式")
    class Run {

        @Test
        @DisplayName("JAVA：xmx = max(32, 限额-64)；限额 256 → 192m")
        void javaXmx() {
            assertThat(profiles.runCommand(Language.JAVA, 256))
                    .isEqualTo("java -XX:-UsePerfData -XX:+UseSerialGC -Xss64m -Xmx192m -cp /work Main");
        }

        @Test
        @DisplayName("JAVA：限额很低（96）→ xmx 取下限 32m")
        void javaXmxLowerBound() {
            assertThat(profiles.runCommand(Language.JAVA, 96)).contains("-Xmx32m");
        }

        @Test
        @DisplayName("PYTHON / CPP / GO 的解释执行或二进制直跑")
        void others() {
            assertThat(profiles.runCommand(Language.PYTHON, 256)).isEqualTo("python3 -I /src/main.py");
            assertThat(profiles.runCommand(Language.CPP, 256)).isEqualTo("/work/main");
            assertThat(profiles.runCommand(Language.GO, 256)).isEqualTo("/work/main");
        }
    }

    @Nested
    @DisplayName("镜像映射")
    class Images {

        @Test
        @DisplayName("默认镜像与配置一致，4 语言全覆盖")
        void defaultImages() {
            assertThat(profiles.image(properties, Language.JAVA)).isEqualTo("judge-java21:latest");
            assertThat(profiles.image(properties, Language.PYTHON)).isEqualTo("judge-python3.12:latest");
            assertThat(profiles.image(properties, Language.CPP)).isEqualTo("judge-gcc13:latest");
            assertThat(profiles.image(properties, Language.GO)).isEqualTo("judge-go1.22:latest");
        }

        @Test
        @DisplayName("配置覆盖后返回覆盖值")
        void overriddenImages() {
            properties.setImageJava("registry.local/java21:_digest");
            assertThat(profiles.image(properties, Language.JAVA)).isEqualTo("registry.local/java21:_digest");
        }
    }
}
