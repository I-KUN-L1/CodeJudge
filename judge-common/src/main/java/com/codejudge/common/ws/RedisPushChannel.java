package com.codejudge.common.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;

/**
 * WebSocket 跨实例广播通道（Redis 发布订阅）。
 *
 * <p><b>为什么必须有这一层</b>：WS 会话是「进程内资源」，而触发推送的事件来自 MQ 消费者，
 * 在集群里落在哪个实例是不确定的。若各服务只在本地推送，一旦水平扩容就会出现
 * 「A 实例的用户收不到结果、B 实例的用户收得到」这类只在生产环境复现的缺陷。
 * 统一走 Redis 广播后，语义变为：**事件 → 广播 → 每个实例投递给自己持有的会话**，
 * 与实例数量无关。
 *
 * <p><b>为什么发布端不直接本地推送</b>：发布者本身也是订阅者，Redis 会把消息回投给
 * 自己（含发布连接）。若发布时额外做一次本地推送，本地会话就会收到**两条重复消息**。
 * 因此规定：<b>投递路径唯一</b> —— 只有 {@link #onMessage} 会调用会话注册表。
 *
 * <p><b>失败语义</b>：发布订阅是「至多一次」的即时通道，Redis 不可用/瞬时抖动会丢消息。
 * 这是可接受的：推送只是旁路通知，权威数据在 DB 与 Redis 榜单里，客户端可随时用
 * REST 重新拉取（各主题的 {@code seq} 可用于发现跳号）。
 */
@Slf4j
public class RedisPushChannel implements MessageListener {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final WsSessionRegistry registry;
    private final WsProperties properties;
    /** 本实例标识，仅用于日志与排障 */
    private final String instanceId;

    public RedisPushChannel(StringRedisTemplate redis, ObjectMapper objectMapper,
                            WsSessionRegistry registry, WsProperties properties, String instanceId) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.registry = registry;
        this.properties = properties;
        this.instanceId = instanceId;
    }

    /** 发布一条推送报文（本实例持有该主题会话时同样经由订阅回调投递） */
    public boolean publish(WsEnvelope envelope) {
        try {
            String json = objectMapper.writeValueAsString(envelope);
            redis.convertAndSend(properties.getChannel(), json);
            return true;
        } catch (Exception e) {
            log.error("WS 广播失败：topic={} type={} err={}", envelope.getTopic(), envelope.getType(), e.getMessage());
            return false;
        }
    }

    /** 订阅回调：只投递给「本实例确实持有该主题会话」的连接 */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        String json = new String(message.getBody(), StandardCharsets.UTF_8);
        String topic;
        try {
            JsonNode node = objectMapper.readTree(json);
            topic = node.path("topic").asText(null);
        } catch (Exception e) {
            log.warn("WS 广播报文解析失败，丢弃：{}", e.getMessage());
            return;
        }
        if (topic == null) {
            return;
        }
        // 大多数广播与本实例无关（例如竞赛服务收到判题进度主题），先做廉价判断再投递
        if (!registry.hasTopic(topic)) {
            return;
        }
        int sent = registry.pushRaw(topic, json);
        log.debug("WS 广播投递：instance={} topic={} sessions={}", instanceId, topic, sent);
    }
}
