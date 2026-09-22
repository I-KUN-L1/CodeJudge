package com.codejudge.ai.service.memory;

import com.codejudge.ai.constants.AiRedisKeys;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 基于 Redis 的点评会话记忆（复用自底座 {@code zx-aigc} 的 {@code ChatMemory}）。
 *
 * <p>改造点：
 * <ul>
 *   <li>Key 前缀 {@code aigc:memory:} → {@code judge:ai:memory:}（集中声明在 {@link AiRedisKeys}）；</li>
 *   <li>保留 20 条上限与 7 天 TTL —— 点评的追问链路通常只有 2~3 轮，
 *       20 条足以覆盖，且能防止用户把会话当成无限上下文导致 Prompt 持续膨胀。</li>
 * </ul>
 *
 * <p><b>降级策略（有意）</b>：Redis 不可用时返回空历史、写入静默失败，
 * 绝不让「记不住上文」演变成「点评功能 500」。点评的核心价值是单次分析，
 * 多轮追问是增强项，增强项不该拖垮主干。
 */
@Component
@RequiredArgsConstructor
public class ChatMemory {

    private static final Duration TTL = Duration.ofDays(7);
    private static final int MAX_HISTORY = 20;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public void saveMessage(String sessionId, String role, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        List<RedisMessage> messages = load(sessionId);
        messages.add(new RedisMessage(role, content));
        if (messages.size() > MAX_HISTORY) {
            messages = messages.subList(messages.size() - MAX_HISTORY, messages.size());
        }
        try {
            redisTemplate.opsForValue()
                    .set(AiRedisKeys.REVIEW_MEMORY_PREFIX + sessionId,
                            objectMapper.writeValueAsString(messages), TTL);
        } catch (Exception ignored) {
            // Redis 不可用：降级为无记忆，不影响主流程
        }
    }

    public List<RedisMessage> load(String sessionId) {
        String json;
        try {
            json = redisTemplate.opsForValue().get(AiRedisKeys.REVIEW_MEMORY_PREFIX + sessionId);
        } catch (Exception e) {
            return new ArrayList<>();
        }
        if (json == null) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<RedisMessage>>() {
            });
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public List<Map<String, String>> loadAsMap(String sessionId) {
        List<Map<String, String>> result = new ArrayList<>();
        for (RedisMessage m : load(sessionId)) {
            result.add(Map.of("role", m.getRole(), "content", m.getContent()));
        }
        return result;
    }

    public void clear(String sessionId) {
        try {
            redisTemplate.delete(AiRedisKeys.REVIEW_MEMORY_PREFIX + sessionId);
        } catch (Exception ignored) {
        }
    }
}
