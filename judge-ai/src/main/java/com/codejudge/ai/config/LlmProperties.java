package com.codejudge.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * LLM 服务配置（OpenAI 兼容协议，默认对接智谱清言 ChatGLM）。
 *
 * <p>复用自底座 {@code zx-aigc} 的 {@code LlmProperties}，仅做两处改造：
 * 配置前缀 {@code zx.llm} → {@code cj.llm}；新增 {@code embeddingDimension} 的默认值说明。
 *
 * <p>智谱清言官方接口（与 OpenAI 兼容）：
 * <pre>
 * 鉴权：Authorization: Bearer {apiKey}
 * 端点：POST {base-url}/{chat-path} → https://open.bigmodel.cn/api/paas/v4/chat/completions
 * 请求体：{model, messages, temperature, top_p, max_tokens, stream}
 * </pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "cj.llm")
public class LlmProperties {

    /** OpenAI 兼容接口地址（智谱清言默认） */
    private String baseUrl = "https://open.bigmodel.cn/api/paas/v4";

    /** 智谱开放平台 API Key；为空时 {@link #enabled} 即使为 true 也走降级 */
    private String apiKey = "";

    /** 模型名 */
    private String model = "glm-4.5-air";

    /** 相对 base-url 的补全接口路径（智谱为 chat/completions，OpenAI 为 v1/chat/completions） */
    private String chatPath = "chat/completions";

    /** 采样温度（0~1）。点评任务比闲聊更需要稳定复现，yml 中可下调至 0.3~0.5 */
    private Double temperature = 0.8D;

    /** 核采样 */
    private Double topP = 0.95D;

    /** 单次回复的最大 token 数 */
    private Integer maxTokens = 1024;

    /**
     * 是否启用真实 LLM。
     * <p>为 {@code false} 时 {@link com.codejudge.ai.service.LlmClient} 不会发起网络调用，
     * 由 {@link com.codejudge.ai.service.ReviewService} 输出**结构化降级点评**（含 RAG 命中结果），
     * 而不是抛 500 —— 这是 P5 的硬验收要求（本机 {@code .env} 当前即为 false）。
     */
    private boolean enabled = false;

    /** Embedding 模型名（如 embedding-3 / text-embedding-3-small） */
    private String embeddingModel = "embedding-3";

    /**
     * 相对 {@link #baseUrl} 的向量化接口路径。
     *
     * <p>必须与厂商实际的 base-url 组合后成立，否则会拼出「多一段前缀」的 404 地址：
     * <ul>
     *   <li>{@code baseUrl=https://api.openai.com} + {@code v1/embeddings} → /v1/embeddings（正确）</li>
     *   <li>{@code baseUrl=https://open.bigmodel.cn/api/paas/v4} + {@code embeddings} → /v4/embeddings（正确）</li>
     *   <li>智谱 base-url 配 {@code v1/embeddings} → /v4/v1/embeddings，404（历史缺陷：路径被写死，
     *       Embedding 恒定失败并**静默降级**为伪向量，RAG 检索仅剩演示意义且无任何报错）</li>
     * </ul>
     */
    private String embeddingPath = "embeddings";

    /** Embedding 向量维度，必须与 pgvector 列（knowledge_chunk.embedding / ai_review.embedding）一致 */
    private int embeddingDimension = 1024;

    /** 是否具备真实可用的 LLM 能力（enabled 且 apiKey 非空） */
    public boolean available() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }
}
