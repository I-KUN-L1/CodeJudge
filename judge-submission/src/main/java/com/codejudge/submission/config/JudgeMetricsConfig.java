package com.codejudge.submission.config;

import com.codejudge.submission.service.WorkerViewService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.ToDoubleFunction;

/**
 * 判题链路的业务指标（P6 新增）。
 *
 * <h3>为什么需要它</h3>
 * actuator 自带的 HTTP / JVM / Hikari 指标只能回答"进程健不健康"，
 * 回答不了"**判题有没有在正常推进**"。对一个判题平台来说，真正要告警的是：
 * <ul>
 *   <li>{@code judge_queue_backlog} —— 待判队列积压。持续上涨说明判题机不够或卡住，
 *       此时用户侧的表现是"提交后一直排队中"，而 HTTP 指标完全正常；</li>
 *   <li>{@code judge_dead_tasks} —— 死信任务。**只要大于 0 就是事故**：
 *       说明有提交在重试耗尽后仍未判成功，需要人工介入；</li>
 *   <li>{@code judge_workers_online} —— 在线判题机数。掉到 0 意味着集群空了。</li>
 * </ul>
 *
 * <h3>为什么用 Gauge 而不是定时上报</h3>
 * Gauge 的取值函数**在每次抓取时才求值**（延迟计算），因此不需要后台线程，
 * 也不会在没人看的时候白跑 Redis/DB 查询。代价是抓取间隔内无法保留历史峰值 ——
 * 对"当前积压量"这类瞬时量级的语义，这正是想要的。
 *
 * <h3>与 REST 接口的关系</h3>
 * 取值走的是 {@link WorkerViewService} 的 **Raw 版本**（无鉴权）。
 * 因为 Prometheus 抓取时没有登录用户，带 {@code requireAdmin()} 的公开方法会抛
 * ForbiddenException 并让整个 scrape 失败（表现为"指标忽有忽无"，极难定位）。
 */
@Slf4j
@Configuration
public class JudgeMetricsConfig {

    @Bean
    public MeterBinder judgeQueueMetrics(WorkerViewService workerViewService) {
        return registry -> {
            safeGauge(registry, "judge_queue_backlog",
                    "待判队列积压（judge:judge:queue:zset 的 member 数）",
                    workerViewService, WorkerViewService::queueBacklogRaw);

            safeGauge(registry, "judge_dead_tasks",
                    "死信任务数（judge_task.status = DEAD）；大于 0 即为需要人工介入的事故",
                    workerViewService, WorkerViewService::deadTaskCountRaw);

            safeGauge(registry, "judge_workers_online",
                    "在线判题机数量（Redis 心跳 key 数，TTL 30s）",
                    workerViewService, WorkerViewService::onlineWorkerCountRaw);
        };
    }

    /**
     * 注册一个不会把 scrape 打挂的 Gauge。
     *
     * <p>取值函数一旦抛异常，Micrometer 会把异常冒泡到抓取端点，导致**整个** /actuator/prometheus
     * 返回 500 —— 所有指标一起消失（Redis 抖一下就全指标失联，是典型的"越修越糟"设计）。
     * 因此这里统一兜底：失败记 debug 日志并返回 {@code NaN}。
     * Prometheus 对 NaN 的处理是"该样本不参与比较"（`> 0` 之类不会命中），
     * 既不误报告警，也不会污染其它指标。
     *
     * <p>返回值语义由被调函数保证非 null（Raw 版本均返回原始类型或已做空值兜底），
     * 因此这里只需处理异常与非有限值。
     */
    private <T> void safeGauge(MeterRegistry registry, String name, String description,
                               T target, ToDoubleFunction<T> fn) {
        Gauge.builder(name, target, obj -> {
                    try {
                        double value = fn.applyAsDouble(obj);
                        return Double.isFinite(value) ? value : Double.NaN;
                    } catch (Exception e) {
                        log.debug("指标 {} 取值失败（返回 NaN，不影响其它指标）：{}", name, e.getMessage());
                        return Double.NaN;
                    }
                })
                .description(description)
                .register(registry);
    }
}
