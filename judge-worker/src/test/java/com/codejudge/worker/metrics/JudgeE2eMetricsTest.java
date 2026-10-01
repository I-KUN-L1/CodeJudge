package com.codejudge.worker.metrics;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * JudgeE2eMetrics 单测（SLO S3 埋点的样本准入与 verdict 分列）。
 *
 * <p>运行：mvn -pl judge-worker -am test
 *
 * <p>覆盖：起点/verdict 缺失跳过、时钟回拨（e2e<0）丢弃、正常样本按 verdict
 * 分列计数、同 verdict 复用同一 Timer 不抛重复注册异常。
 * 用 SimpleMeterRegistry 验证暴露形态（cj_judge_e2e + verdict 标签）。
 */
class JudgeE2eMetricsTest {

    private SimpleMeterRegistry registry;
    private JudgeE2eMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new JudgeE2eMetrics(registry);
    }

    @Nested
    @DisplayName("样本准入")
    class Admission {

        @Test
        @DisplayName("起点缺失（老数据）→ false，不产生任何指标")
        void nullSubmitTime() {
            assertThat(metrics.recordTerminal(null, "AC")).isFalse();
            assertThat(registry.find("cj_judge_e2e").timers()).isEmpty();
        }

        @Test
        @DisplayName("verdict 缺失 → false")
        void nullVerdict() {
            assertThat(metrics.recordTerminal(LocalDateTime.now().minusSeconds(1), null)).isFalse();
            assertThat(registry.find("cj_judge_e2e").timers()).isEmpty();
        }

        @Test
        @DisplayName("时钟回拨（提交时刻在未来）→ 丢弃，不污染 P99")
        void clockBackwards() {
            assertThat(metrics.recordTerminal(LocalDateTime.now().plusMinutes(5), "AC")).isFalse();
            assertThat(registry.find("cj_judge_e2e").timers()).isEmpty();
        }

        @Test
        @DisplayName("正常样本 → true 且计数 1")
        void normalSample() {
            assertThat(metrics.recordTerminal(LocalDateTime.now().minusSeconds(2), "AC")).isTrue();
            Timer timer = registry.find("cj_judge_e2e").tag("verdict", "AC").timer();
            assertThat(timer).isNotNull();
            assertThat(timer.count()).isEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("verdict 分列")
    class PerVerdict {

        @Test
        @DisplayName("不同 verdict 各自一条 Timer")
        void separateTimers() {
            metrics.recordTerminal(LocalDateTime.now().minusSeconds(1), "AC");
            metrics.recordTerminal(LocalDateTime.now().minusSeconds(1), "WA");
            metrics.recordTerminal(LocalDateTime.now().minusSeconds(1), "SE");

            assertThat(registry.find("cj_judge_e2e").tag("verdict", "AC").timer().count()).isEqualTo(1L);
            assertThat(registry.find("cj_judge_e2e").tag("verdict", "WA").timer().count()).isEqualTo(1L);
            assertThat(registry.find("cj_judge_e2e").tag("verdict", "SE").timer().count()).isEqualTo(1L);
        }

        @Test
        @DisplayName("同 verdict 重复记录复用 Timer，不抛重复注册异常")
        void reusesTimer() {
            assertThatCode(() -> {
                metrics.recordTerminal(LocalDateTime.now().minusSeconds(1), "TLE");
                metrics.recordTerminal(LocalDateTime.now().minusSeconds(2), "TLE");
            }).doesNotThrowAnyException();

            assertThat(registry.find("cj_judge_e2e").tag("verdict", "TLE").timer().count())
                    .isEqualTo(2L);
        }
    }
}
