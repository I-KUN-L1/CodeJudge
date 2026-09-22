package com.codejudge.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG（检索增强生成）与 SSE 流式配置。
 *
 * <p>复用自底座 {@code zx-aigc} 的 {@code RagProperties}，改造点：
 * <ul>
 *   <li>前缀 {@code zx.rag} → {@code cj.rag}；</li>
 *   <li>新增 {@code historyTopK} —— 历史点评召回与题目知识召回**分开计数**。
 *       共用同一个 topK 时，同一道题的历史点评会挤掉题目知识片段，
 *       而「题目知识」才是抑制幻觉的锚点，优先级高于「历史点评参考」。</li>
 *   <li>新增 {@code historyEnabled} —— 排障开关：历史点评库为空时不必每轮都查一次 PG。</li>
 * </ul>
 */
@Data
@Component
@ConfigurationProperties(prefix = "cj.rag")
public class RagProperties {

    /** 文本切片窗口大小（近似 token） */
    private int chunkSize = 500;

    /** 相邻切片重叠（近似 token） */
    private int chunkOverlap = 50;

    /** 题目知识召回条数 */
    private int topK = 4;

    /** 历史点评召回条数（与 {@link #topK} 独立计数） */
    private int historyTopK = 3;

    /** 是否启用历史点评召回 */
    private boolean historyEnabled = true;

    /** 相似度最低阈值，低于该值视为「未命中」（宁可不注入，也不硬凑 TopK） */
    private double minScore = 0.0;

    /** SSE 心跳间隔（秒），0 表示关闭 */
    private int heartbeatSeconds = 15;

    /** 断线重连时可回放的最近事件条数 */
    private int replayCount = 200;

    /** 同时活跃的 SSE 流式连接上限；超限返回降级提示，防止长连接打满连接容量（0=不限制） */
    private int maxConcurrentStreams = 200;
}
