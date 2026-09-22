package com.codejudge.common.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.web.socket.WebSocketSession;

/**
 * WebSocket 推送基座自动装配。
 *
 * <p>生效条件（三者同时满足）：
 * <ol>
 *   <li>{@code cj.ws.enabled=true} —— 仅 judge-submission / judge-contest 打开，
 *       P1–P3 的服务不会被装配多余的 Redis 订阅连接；</li>
 *   <li>类路径存在 spring-websocket（judge-common 里为 optional 依赖，由消费方引入）；</li>
 *   <li>类路径存在 Spring Data Redis（两个消费方均有）。</li>
 * </ol>
 *
 * <p>注意：Spring Boot 不会自动创建 {@link RedisMessageListenerContainer}
 * （RedisAutoConfiguration 只给 template），这里是唯一的声明点。
 */
@AutoConfiguration
@EnableConfigurationProperties(WsProperties.class)
@ConditionalOnClass({WebSocketSession.class, StringRedisTemplate.class, RedisConnectionFactory.class})
@ConditionalOnProperty(prefix = "cj.ws", name = "enabled", havingValue = "true")
public class WsPushAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public WsSessionRegistry wsSessionRegistry(WsProperties properties, StringRedisTemplate redis,
                                               Environment environment) {
        return new WsSessionRegistry(properties, redis, resolveInstanceId(environment));
    }

    @Bean
    @ConditionalOnMissingBean
    public WsSequencer wsSequencer() {
        return new WsSequencer();
    }

    @Bean
    @ConditionalOnMissingBean
    public WsIdentityInterceptor wsIdentityInterceptor() {
        return new WsIdentityInterceptor();
    }

    @Bean
    @ConditionalOnMissingBean
    public WsHeartbeat wsHeartbeat(WsSessionRegistry registry, WsProperties properties) {
        return new WsHeartbeat(registry, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public RedisPushChannel wsRedisPushChannel(StringRedisTemplate redis, ObjectMapper objectMapper,
                                               WsSessionRegistry registry, WsProperties properties,
                                               Environment environment) {
        return new RedisPushChannel(redis, objectMapper, registry, properties, resolveInstanceId(environment));
    }

    /** 订阅广播通道；容器随应用启停，Redis 不可用时启动失败但不阻断应用（由 Boot 记 warn） */
    @Bean
    public RedisMessageListenerContainer wsRedisMessageListenerContainer(RedisConnectionFactory connectionFactory,
                                                                        RedisPushChannel channel,
                                                                        WsProperties properties) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(channel, new ChannelTopic(properties.getChannel()));
        return container;
    }

    /** 实例标识：{@code 服务名:端口}，用于日志与 Redis 会话索引 key */
    private String resolveInstanceId(Environment environment) {
        String name = environment.getProperty("spring.application.name", "judge-app");
        String port = environment.getProperty("server.port", "0");
        return name + ":" + port;
    }
}
