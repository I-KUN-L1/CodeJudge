package com.codejudge.common.ws;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * WebSocket 推送参数（前缀 {@code cj.ws}）。
 *
 * <p>默认 {@code enabled=false}：只有真正提供 WS 端点的服务（judge-submission /
 * judge-contest）显式打开，避免 P1–P3 的服务被装配进多余的 Redis 订阅连接。
 */
@Data
@ConfigurationProperties(prefix = "cj.ws")
public class WsProperties {

    /** 是否启用 WS 推送基座（订阅 Redis 广播通道所需） */
    private boolean enabled = false;

    /**
     * 跨实例广播通道名。
     *
     * <p>为什么需要它：用户会话只存在于「建立连接的那一个实例」上，而触发推送的事件
     * （判题结果、榜单变更）可能落在**任意实例**。若只在本地推送，就会出现
     * 「同一服务跑两个实例时，一部分用户永远收不到推送」的隐蔽缺陷。
     * 因此所有推送统一走 Redis 发布订阅，由各实例把消息投递给**自己持有的**会话。
     */
    private String channel = "judge:ws:broadcast";

    /** 单次发送超时（毫秒）：超时即判定会话已死并摘除，防止慢客户端拖住推送线程 */
    private int sendTimeLimitMs = 5000;

    /** 会话出站缓冲上限（字节）：超过即关闭该会话（背压保护，防止慢客户端吃光内存） */
    private int bufferSizeLimitBytes = 256 * 1024;

    /** 服务端心跳间隔（毫秒），0=关闭心跳 */
    private long heartbeatIntervalMs = 30_000;

    /**
     * 单主题最大会话数（保护阈值）。
     *
     * <p>热门竞赛的榜单主题可能被大量客户端订阅；超过阈值时拒绝新订阅并回 ERROR，
     * 避免单个主题把内存吃光（生产应配合网关层连接数限制）。
     */
    private int maxSessionsPerTopic = 5000;

    /** Redis 会话索引 key 的 TTL（秒）：仅用于运维观测与跨实例定位，不影响推送正确性 */
    private long sessionIndexTtlSeconds = 7200;
}
