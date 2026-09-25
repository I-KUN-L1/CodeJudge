package com.codejudge.ai.service;

import com.codejudge.ai.config.LlmProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM 客户端：调用 OpenAI 兼容接口（复用自底座 {@code zx-aigc} 的 {@code LlmClient}）。
 *
 * <p>改造点：
 * <ul>
 *   <li>配置类换成 {@link LlmProperties}（前缀 {@code cj.llm}）；</li>
 *   <li>降级文案从「智能助教」改为判题域的中性提示。注意：
 *       <b>点评的正常降级路径不经过本类的 mock 分支</b> ——
 *       {@code ReviewService} 会在 {@code available()==false} 时走**结构化降级**，
 *       本类的 mock 仅作为「被直接调用」时的兜底，避免返回空串让上层拿到空白点评；</li>
 *   <li>去掉 Function Calling（{@code chatWithTools} / {@code ToolRunner}）——
 *       底座用它做「课程推荐 / 下单」工具调用，判题点评不需要工具链。
 *       保留它等于留一段永不执行的死代码，且会牵着 {@code ToolRunner} 一起迁移。</li>
 * </ul>
 *
 * <p><b>流式解析的关键坑（沿用底座注释，已被实测验证）</b>：
 * {@code bodyToFlux(String.class)} 消费 {@code text/event-stream} 时，
 * WebFlux 的 {@code ServerSentEventHttpMessageReader} 会自动解析事件并仅返回 <b>data 部分</b>
 * （不带 {@code data:} 前缀）。因此必须直接解析 JSON，
 * 不可按原始行 {@code startsWith("data:")} 过滤 —— 那会把所有事件过滤为空，导致回答空白。
 * 这个坑的表现是「接口 200、有事件流、但 content 全为空」，极难从日志看出来。
 */
@Slf4j
@Service
public class LlmClient {

    private final LlmProperties properties;
    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LlmClient(LlmProperties properties) {
        this.properties = properties;
        // responseTimeout：LLM 网关挂起时若无上限，MQ 消费线程与 boundedElastic 会被无限占用
        reactor.netty.http.client.HttpClient httpClient = reactor.netty.http.client.HttpClient.create()
                .responseTimeout(Duration.ofSeconds(120));
        this.webClient = WebClient.builder()
                .baseUrl(properties.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + properties.getApiKey())
                // 显式声明接受 SSE 流，避免部分厂商按普通 JSON 聚合响应
                .defaultHeader("Accept", "text/event-stream")
                .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(httpClient))
                .build();
    }

    /** 非流式对话（用于异步预生成：整段拿回后一次性落库） */
    public Mono<String> chat(List<Map<String, String>> messages) {
        if (!properties.available()) {
            return Mono.just(mockReply());
        }
        Map<String, Object> body = new HashMap<>();
        body.put("model", properties.getModel());
        body.put("messages", messages);
        body.put("stream", false);
        applyParams(body);
        return webClient.post()
                .uri(chatUri())
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .map(this::extractContent)
                .onErrorResume(e -> {
                    log.warn("LLM 非流式调用失败：{}", e.getMessage());
                    return Mono.just(fallbackOnError());
                });
    }

    /**
     * 流式对话（SSE），逐增量返回 {@code choices[0].delta.content}。
     *
     * <p>上游失败时**不抛异常**，而是返回一条可读的降级文本 ——
     * SSE 场景下把异常抛给客户端只会得到一个断掉的连接；
     * 给出「AI 服务暂时不可用」的正文能让前端把它当成正常的点评内容渲染出来，
     * 用户至少知道发生了什么。
     */
    public Flux<String> chatStream(List<Map<String, String>> messages) {
        if (!properties.available()) {
            String reply = mockReply();
            return Flux.fromArray(reply.split("(?<=\\G.{8})"));
        }
        Map<String, Object> body = new HashMap<>();
        body.put("model", properties.getModel());
        body.put("messages", messages);
        body.put("stream", true);
        applyParams(body);
        return webClient.post()
                .uri(chatUri())
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class)
                // data 已由 SSE reader 解出（无 data: 前缀）：仅过滤结束标记与非 JSON 心跳行
                .filter(data -> data != null && !data.isBlank() && !"[DONE]".equals(data.trim()))
                .mapNotNull(this::extractDelta)
                .filter(content -> !content.isEmpty())
                .onErrorResume(e -> {
                    log.warn("流式调用失败，降级为错误提示：{}", e.getMessage());
                    return Flux.just("\n\n> ⚠️ AI 服务在生成过程中中断（" + e.getClass().getSimpleName()
                            + "），以上为已生成的部分内容。");
                });
    }

    /** 拼接补全接口地址：跳过头尾多余的斜杠，避免产生形如 {@code v4//chat/completions} 的双斜杠路径 */
    private String chatUri() {
        String path = properties.getChatPath() == null ? "chat/completions" : properties.getChatPath();
        return "/" + path.replaceAll("^/+|/+$", "");
    }

    /** 填充模型生成参数（temperature / top_p / max_tokens） */
    private void applyParams(Map<String, Object> body) {
        if (properties.getTemperature() != null) {
            body.put("temperature", properties.getTemperature());
        }
        if (properties.getTopP() != null) {
            body.put("top_p", properties.getTopP());
        }
        if (properties.getMaxTokens() != null) {
            body.put("max_tokens", properties.getMaxTokens());
        }
    }

    private String extractContent(Map<?, ?> resp) {
        try {
            List<?> choices = (List<?>) resp.get("choices");
            Map<?, ?> choice = (Map<?, ?>) choices.get(0);
            Map<?, ?> message = (Map<?, ?>) choice.get("message");
            Object content = message.get("content");
            return content == null ? "" : String.valueOf(content);
        } catch (Exception e) {
            log.warn("LLM 响应结构异常，无法提取 content：{}", e.getMessage());
            return fallbackOnError();
        }
    }

    /**
     * 解析流式增量：提取 {@code choices[0].delta.content}。
     * content 缺失或为 null（如首 chunk 仅含 role）时返回 null，由调用方跳过。
     */
    private String extractDelta(String data) {
        try {
            JsonNode root = objectMapper.readTree(data);
            JsonNode content = root.path("choices").path(0).path("delta").path("content");
            if (content.isMissingNode() || content.isNull()) {
                return null;
            }
            return content.asText("");
        } catch (Exception e) {
            // 心跳/注释等非 JSON 行：跳过
            return null;
        }
    }

    private String mockReply() {
        return "【CodeJudge AI 代码点评】当前未配置大模型 API Key（CJ_LLM_ENABLED=false 或 CJ_LLM_API_KEY 为空），"
                + "本段为占位输出。\n\n"
                + "请在 .env 中配置 CJ_LLM_API_KEY 并将 CJ_LLM_ENABLED 置为 true 后重试，"
                + "即可获得基于题目知识库与历史点评的真实代码点评。";
    }

    private String fallbackOnError() {
        return "AI 服务暂时不可用，请稍后再试。若持续失败，请联系管理员检查 LLM 网关连通性与配额。";
    }
}
