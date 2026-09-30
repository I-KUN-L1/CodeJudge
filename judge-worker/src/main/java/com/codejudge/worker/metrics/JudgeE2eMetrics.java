package com.codejudge.worker.metrics;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 判题端到端时延埋点（SLO S3 度量，见 docs/SLO.md §1）。
 *
 * <h3>口径</h3>
 * {@code 提交成功 → 终态 verdict 落库}。在 {@code finishTerminal}（业务终态：
 * AC/WA/TLE/MLE/RE/CE）与 {@code failTask} 死信分支（平台终态：SE）两个**唯一**
 * 的终态写入点记录，CAS 丢弃路径（任务被接管/重判）不计 —— 那不是终态。
 *
 * <h3>为什么埋在 judge-worker 而不是 judge-submission</h3>
 * SLO 的度量终点是「verdict 落库」，这件事发生在 worker 的事务里；submission 侧的
 * RESULT 事件消费还要再加一段 MQ 投递延迟，且事件发送失败（仅告警不重试）会造成
 * 样本静默丢失。起点用 {@code submission.submit_time}，与事件里下发的
 * {@code submitTimeEpochMs} 同源，不引入新的时钟基准。
 *
 * <h3>Prometheus 形态</h3>
 * Micrometer Timer 命名 {@code cj_judge_e2e}，经 PrometheusMeterRegistry 自动追加
 * 单位后缀，暴露为 {@code cj_judge_e2e_seconds_bucket/_count/_sum} —— 与
 * SLO.md / 告警规则 / 错误预算看板引用的名字一致。桶范围按 SLO 目标设
 * （50ms–120s）：S3 目标 P99&lt;10s，P99 观测值落在桶覆盖区间内才能被
 * histogram_quantile 正确插值。
 */
@Slf4j
@Component
public class JudgeE2eMetrics {

    private static final String METRIC_NAME = "cj_judge_e2e";

    private final MeterRegistry registry;
    /** verdict → Timer 缓存：同一组合重复注册会抛 IllegalArgumentException */
    private final Map<String, Timer> timers = new ConcurrentHashMap<>();

    public JudgeE2eMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 记录一次终态的端到端时延。
     *
     * @param submitTime 提交时刻（来自 judge_submission 表，可能为 null —— 老数据兜底跳过）
     * @param verdict    终态 verdict（AC/WA/TLE/MLE/RE/CE/SE）
     * @return true=已记录；false=因起点缺失被跳过（调用方无需处理）
     */
    public boolean recordTerminal(LocalDateTime submitTime, String verdict) {
        if (submitTime == null || verdict == null) {
            return false;
        }
        long startEpochMs = submitTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long e2eMs = System.currentTimeMillis() - startEpochMs;
        if (e2eMs < 0 || e2eMs > TimeUnit.HOURS.toMillis(24)) {
            // 时钟回拨 / 明显脏数据：记进来会把 P99 拖到无意义，丢弃并留痕
            log.warn("e2e 时延超出合理范围，丢弃样本：verdict={} e2eMs={}", verdict, e2eMs);
            return false;
        }
        timerFor(verdict).record(Duration.ofMillis(e2eMs));
        return true;
    }

    private Timer timerFor(String verdict) {
        return timers.computeIfAbsent(verdict, v -> Timer.builder(METRIC_NAME)
                .description("判题端到端时延（提交成功 → 终态 verdict 落库），按 verdict 分列")
                .tag("verdict", v)
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(50))
                .maximumExpectedValue(Duration.ofSeconds(120))
                .register(registry));
    }
}
