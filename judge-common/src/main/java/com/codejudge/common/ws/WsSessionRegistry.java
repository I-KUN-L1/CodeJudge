package com.codejudge.common.ws;

import com.codejudge.common.constants.JudgeRedisKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WebSocket 会话注册表：按「订阅主题」索引本实例持有的会话，并负责安全投递。
 *
 * <p><b>职责边界</b>：只做注册/摘除/投递，不含任何业务语义（不解析业务报文、
 * 不判断用户权限），业务判断留给各服务的 WebSocketHandler。这样 judge-submission
 * 与 judge-contest 可以共用同一份会话管理而互不干扰。
 *
 * <p><b>线程安全</b>：注册/摘除/投递都可能并发发生（Tomcat 的 WS 线程 + Redis 订阅线程 +
 * 业务调度线程），故内部结构全部为并发容器；会话本身用
 * {@link ConcurrentWebSocketSessionDecorator} 包装，Spring 负责串行化同一会话上的发送
 * 并施加「发送超时 + 缓冲上限」的背压保护。
 *
 * <p><b>摘除策略（fail-fast）</b>：投递失败（连接已关闭 / 缓冲溢出被 Spring 关闭）立即摘除
 * 会话，不做重试 —— 推送是「尽力而为」的旁路能力，客户端应具备重连或 REST 兜底。
 */
@Slf4j
public class WsSessionRegistry {

    private final WsProperties properties;
    /** 主题 -> (sessionId -> session)；用 Map 而非 Set，便于按 id 精确摘除与幂等注册 */
    private final Map<String, Map<String, WebSocketSession>> topics = new ConcurrentHashMap<>();
    /** sessionId -> 会话元信息（主题、用户、是否全量视图）；用于摘除与观测 */
    private final Map<String, SessionMeta> sessions = new ConcurrentHashMap<>();
    /** 会话索引（Redis，跨实例观测用），可为 null（未启用时） */
    private final StringRedisTemplate redis;
    private final String instanceId;

    public WsSessionRegistry(WsProperties properties, StringRedisTemplate redis, String instanceId) {
        this.properties = properties;
        this.redis = redis;
        this.instanceId = instanceId;
    }

    /**
     * 注册会话到指定主题。
     *
     * @return true=注册成功；false=已达单主题会话上限（调用方应回 ERROR 并关闭连接）
     */
    public boolean register(String topic, WebSocketSession session, Long userId, boolean full) {
        Map<String, WebSocketSession> bucket = topics.computeIfAbsent(topic, k -> new ConcurrentHashMap<>());
        if (bucket.size() >= properties.getMaxSessionsPerTopic() && !bucket.containsKey(session.getId())) {
            log.warn("主题会话数达上限，拒绝订阅：topic={} limit={}", topic, properties.getMaxSessionsPerTopic());
            return false;
        }
        WebSocketSession wrapped = new ConcurrentWebSocketSessionDecorator(session,
                properties.getSendTimeLimitMs(), properties.getBufferSizeLimitBytes());
        // 同一连接重复订阅同一主题：覆盖旧引用，避免出现两个可发送的包装对象
        bucket.put(session.getId(), wrapped);
        sessions.put(session.getId(), new SessionMeta(topic, userId, full));
        writeSessionIndex(userId, session.getId(), true);
        log.info("WS 会话注册：topic={} sessionId={} userId={} full={} topicCount={}",
                topic, session.getId(), userId, full, bucket.size());
        return true;
    }

    /** 摘除会话（连接关闭 / 投递失败）。幂等：重复调用无副作用。 */
    public void unregister(WebSocketSession session) {
        SessionMeta meta = sessions.remove(session.getId());
        if (meta == null) {
            return;
        }
        Map<String, WebSocketSession> bucket = topics.get(meta.topic());
        if (bucket != null) {
            bucket.remove(session.getId());
            // 主题已空则移除桶，避免长期运行后累积空 Map
            topics.computeIfPresent(meta.topic(), (k, v) -> v.isEmpty() ? null : v);
        }
        writeSessionIndex(meta.userId(), session.getId(), false);
        log.info("WS 会话注销：topic={} sessionId={} userId={}", meta.topic(), session.getId(), meta.userId());
    }

    /**
     * 向主题下所有会话投递**已序列化**的报文。
     *
     * <p>之所以接收原始 JSON 文本：推送报文由 Redis 广播通道原样转发而来，
     * 再反序列化一次纯属浪费（且会丢失 Map/自定义对象的原始形态）。
     *
     * @return 实际投递成功的会话数
     */
    public int pushRaw(String topic, String json) {
        Map<String, WebSocketSession> bucket = topics.get(topic);
        if (bucket == null || bucket.isEmpty()) {
            return 0;
        }
        AtomicInteger sent = new AtomicInteger();
        bucket.forEach((sessionId, session) -> {
            try {
                if (!session.isOpen()) {
                    unregister(session);
                    return;
                }
                session.sendMessage(new TextMessage(json));
                sent.incrementAndGet();
            } catch (Exception e) {
                // 慢客户端/已断开：摘除即可，推送失败不影响业务主链路
                log.debug("WS 投递失败，摘除会话：topic={} sessionId={} err={}", topic, sessionId, e.getMessage());
                unregister(session);
            }
        });
        return sent.get();
    }

    /** 该主题在本实例是否有会话（Redis 广播接收端据此跳过无谓投递） */
    public boolean hasTopic(String topic) {
        Map<String, WebSocketSession> bucket = topics.get(topic);
        return bucket != null && !bucket.isEmpty();
    }

    /** 向本实例全部会话投递（心跳用）；返回投递成功数 */
    public int pushAll(String json) {
        AtomicInteger sent = new AtomicInteger();
        sessions.keySet().forEach(sessionId -> {
            SessionMeta meta = sessions.get(sessionId);
            if (meta == null) {
                return;
            }
            Map<String, WebSocketSession> bucket = topics.get(meta.topic());
            WebSocketSession session = bucket == null ? null : bucket.get(sessionId);
            if (session == null) {
                return;
            }
            try {
                if (!session.isOpen()) {
                    unregister(session);
                    return;
                }
                session.sendMessage(new TextMessage(json));
                sent.incrementAndGet();
            } catch (Exception e) {
                unregister(session);
            }
        });
        return sent.get();
    }

    /** 单会话直接投递（握手应答、ERROR 等尚未注册进主题的场景） */
    public void pushOne(WebSocketSession session, String json) {
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(json));
            }
        } catch (Exception e) {
            log.debug("WS 单会话投递失败：sessionId={} err={}", session.getId(), e.getMessage());
        }
    }

    public int sessionCount(String topic) {
        Map<String, WebSocketSession> bucket = topics.get(topic);
        return bucket == null ? 0 : bucket.size();
    }

    public int totalSessions() {
        return sessions.size();
    }

    /** 主题 -> 会话数快照（运维观测） */
    public Map<String, Integer> topicStats() {
        Map<String, Integer> stats = new ConcurrentHashMap<>();
        topics.forEach((topic, bucket) -> stats.put(topic, bucket.size()));
        return stats;
    }

    public SessionMeta metaOf(String sessionId) {
        return sessions.get(sessionId);
    }

    public Set<String> activeTopics() {
        return Collections.unmodifiableSet(topics.keySet());
    }

    public String getInstanceId() {
        return instanceId;
    }

    /**
     * 维护 Redis 会话索引（{@code judge:ws:session:{instanceId}:{userId}} → Set&lt;sessionId&gt;）。
     *
     * <p>仅用于运维观测与跨实例定位问题（“这个用户的连接在哪个实例上”），
     * **推送正确性不依赖它** —— 推送靠 Redis 广播通道。故此处异常一律吞掉。
     */
    private void writeSessionIndex(Long userId, String sessionId, boolean add) {
        if (redis == null || userId == null) {
            return;
        }
        try {
            String key = JudgeRedisKeys.WS_SESSION_PREFIX + instanceId + ":" + userId;
            if (add) {
                redis.opsForSet().add(key, sessionId);
                redis.expire(key, Duration.ofSeconds(properties.getSessionIndexTtlSeconds()));
            } else {
                redis.opsForSet().remove(key, sessionId);
            }
        } catch (Exception e) {
            log.debug("WS 会话索引写入失败（不影响推送）：err={}", e.getMessage());
        }
    }

    /** 会话元信息 */
    public record SessionMeta(String topic, Long userId, boolean full) {
    }
}
