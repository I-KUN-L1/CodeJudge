package com.codejudge.common.mq;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RocketMQ 连接配置（通用，供生产者/消费者使用）。
 * <p>
 * 通过 {@code rocketmq.name-server} 触发自动装配，未配置该属性的模块（如响应式 judge-ai）不初始化 MQ。
 * 前缀与底座（zx-learn）原 {@code MQProperties} 对齐，确保迁移无感。
 */
@Data
@ConfigurationProperties(prefix = "rocketmq")
public class RocketMQProperties {

    /** NameServer 地址，例如 127.0.0.1:9876 */
    private String nameServer;
    /** 生产者组 */
    private String producerGroup;
    /** 消费者组 */
    private String consumerGroup;

    /**
     * 消费线程下限；{@code <=0} 表示**不设置**，沿用 RocketMQ 默认（20）。
     *
     * <p>为什么需要这个旋钮（2026-09-22 实测，见 docs/PERF.md §3.7）：
     * 判题机每个任务要起 1 个编译容器 + N 个用例容器（都是 {@code docker run}），
     * RocketMQ 默认 20 个消费线程 → 单实例即有 20 个并发 {@code docker run}；
     * 三实例并发 60 个时，**容器冷启动本身**就超过了沙箱墙钟预算
     * （{@code wallClockMs = 时限 + CJ_SANDBOX_WALL_GRACE_MS}，实测 6000ms），
     * 于是**正确解被墙钟兜底强杀判成 TLE**（实测 48 次强杀 / 150 条提交，抽样 18/25 假 TLE）。
     * 结论：多实例判题必须同时约束「实例数 × 每实例并发沙箱数 ≤ 宿主机 CPU 预算」。
     *
     * <p>默认 0 = 保持原行为不变（该旋钮只在你显式配置时生效）。
     */
    private int consumeThreadMin = 0;

    /**
     * 消费线程上限；{@code <=0} 表示**不设置**，沿用 RocketMQ 默认（64）。语义与配套说明见
     * {@link #consumeThreadMin}。
     */
    private int consumeThreadMax = 0;
}