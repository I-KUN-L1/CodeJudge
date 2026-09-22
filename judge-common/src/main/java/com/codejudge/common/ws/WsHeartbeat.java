package com.codejudge.common.ws;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * WebSocket 心跳。
 *
 * <p><b>必要性</b>：长连接在真实链路里会因三层原因被静默切断 —— 浏览器/操作系统的 NAT
 * 空闲超时、网关的响应超时（judge-gateway 对 WS 路由放宽到 15 分钟但并非无限）、
 * 以及故障网络下连接「已死但本端不知情」。定期 PING 能让：
 * <ul>
 *   <li>发送失败立即暴露死连接 → 摘除会话，避免内存泄漏；</li>
 *   <li>维持链路活跃，绕开中间设备的空闲回收。</li>
 * </ul>
 *
 * <p>刻意用独立的单线程调度器（而非 {@code @Scheduled}）：本类在 judge-common 内，
 * 无法假定各服务都开启了 {@code @EnableScheduling}，自带线程可确保心跳一定生效。
 * 线程为守护线程，随 Spring 上下文 {@link PreDestroy} 关闭。
 */
@Slf4j
public class WsHeartbeat {

    private final WsSessionRegistry registry;
    private final WsProperties properties;
    private ScheduledExecutorService executor;

    public WsHeartbeat(WsSessionRegistry registry, WsProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    @PostConstruct
    public void start() {
        long interval = properties.getHeartbeatIntervalMs();
        if (interval <= 0) {
            log.info("WS 心跳已关闭（cj.ws.heartbeat-interval-ms<=0）");
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ws-heartbeat");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(this::beat, interval, interval, TimeUnit.MILLISECONDS);
        log.info("WS 心跳启动，间隔 {}ms", interval);
    }

    private void beat() {
        try {
            int total = registry.totalSessions();
            if (total == 0) {
                return;
            }
            String json = "{\"type\":\"" + WsMessageType.PING.name() + "\",\"topic\":\"*\",\"seq\":0,"
                    + "\"full\":false,\"ts\":" + System.currentTimeMillis() + ",\"data\":null}";
            int sent = registry.pushAll(json);
            log.debug("WS 心跳：sessions={} sent={}", total, sent);
        } catch (Exception e) {
            // 心跳线程绝不可因单次异常退出（否则死连接会一直堆积）
            log.warn("WS 心跳异常：{}", e.getMessage());
        }
    }

    @PreDestroy
    public void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}
