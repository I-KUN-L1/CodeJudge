package com.codejudge.ai.service;

import com.codejudge.ai.config.RagProperties;
import com.codejudge.ai.domain.ChunkHit;
import com.codejudge.ai.domain.KnowledgeChunk;
import com.codejudge.ai.repository.AiReviewRepository;
import com.codejudge.ai.repository.KnowledgeVectorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 知识库服务：协调「文本切片 → 向量化 → pgvector 入库/检索」。
 * <p>复用自底座 {@code zx-aigc} 的同名类，按判题域改造为「题目知识 + 历史点评」双路召回。
 *
 * <p>双路召回的**优先级**：题目知识在前、历史点评在后。原因：
 * <ul>
 *   <li>题目知识是平台人工/教师维护的权威内容，是抑制幻觉的锚点；</li>
 *   <li>历史点评是模型自己的旧输出，只能作为风格与侧重点的参考。
 *       若让历史点评排在前面，模型容易把「上次的结论」当成既定事实照抄，
 *       而上次的结论未必正确（尤其当同一道题的失败原因本就不同）。</li>
 * </ul>
 *
 * <p>{@code minScore} 阈值的作用是「宁可不注入，也不硬凑 TopK」：
 * 向量检索永远会返回 K 条（哪怕全是无关内容），把无关片段当作「参考资料」
 * 喂给模型，比不喂更糟 —— 它会给幻觉提供一个看似权威的出处。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeService {

    private final TextSplitter textSplitter;
    private final EmbeddingService embeddingService;
    private final KnowledgeVectorRepository vectorRepository;
    private final AiReviewRepository aiReviewRepository;
    private final RagProperties ragProperties;

    /**
     * 知识入库：按配置滑动窗口切分，逐片向量化后写入 pgvector。
     *
     * @param replace true 表示先清空该题目的旧切片（重新灌知识的场景，
     *                避免同一段题解被反复入库、在检索结果里霸榜）
     * @return 入库的切片数量
     */
    public int upload(Long problemId, String sourceType, String title, String content, boolean replace) {
        List<String> chunks = textSplitter.split(content, ragProperties.getChunkSize(),
                ragProperties.getChunkOverlap());
        if (chunks.isEmpty()) {
            return 0;
        }
        if (replace && problemId != null) {
            vectorRepository.deleteByProblem(problemId);
        }
        int saved = 0;
        for (String chunkText : chunks) {
            KnowledgeChunk chunk = new KnowledgeChunk();
            chunk.setProblemId(problemId);
            chunk.setSourceType(sourceType);
            // 多切片时把序号写进标题，便于召回后在前端定位「是第几段」
            chunk.setTitle(chunks.size() > 1 ? title + "（" + (saved + 1) + "/" + chunks.size() + "）" : title);
            chunk.setContent(chunkText);
            // 向量化嵌入文本时带上标题：标题通常浓缩了切片主题（如「常见 WA 成因：整型溢出」），
            // 只编码正文会让「整型溢出」这类查询词与正文的匹配度被稀释。
            chunk.setEmbedding(embeddingService.embed(title + "\n" + chunkText));
            vectorRepository.insert(chunk);
            saved++;
        }
        log.info("知识入库完成：problemId={}, sourceType={}, title={}, chunks={}",
                problemId, sourceType, title, saved);
        return saved;
    }

    /** 保留底座签名（无 problemId）的便捷重载：用于通用算法知识（TAG_NOTE） */
    public int upload(String sourceType, String title, String content) {
        return upload(null, sourceType, title, content, false);
    }

    public void deleteByProblem(Long problemId) {
        vectorRepository.deleteByProblem(problemId);
    }

    /**
     * 题目知识检索（按 minScore 阈值过滤）。
     *
     * @param problemId 题目 id；为 null 时跨题检索（通用算法知识）
     */
    public List<ChunkHit> search(String query, int topK, Long problemId) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        int k = topK > 0 ? topK : ragProperties.getTopK();
        List<ChunkHit> hits = vectorRepository.searchTopK(embeddingService.embed(query), k, problemId);
        return filterByScore(hits);
    }

    /**
     * 历史点评检索（同题目、排除本次提交）。
     *
     * <p>失败不抛出：历史点评只是「锦上添花」，PG 异常不应该让整次点评失败。
     */
    public List<ChunkHit> searchHistory(String query, Long problemId, Long excludeSubmissionId) {
        if (!ragProperties.isHistoryEnabled() || query == null || query.isBlank() || problemId == null) {
            return List.of();
        }
        try {
            List<ChunkHit> hits = aiReviewRepository.searchHistory(
                    embeddingService.embed(query), problemId, ragProperties.getHistoryTopK(), excludeSubmissionId);
            return filterByScore(hits);
        } catch (Exception e) {
            log.warn("历史点评检索失败，本次不注入历史参考：{}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 合并双路召回结果：题目知识优先，整体按类型分组但不跨组重排。
     * 之所以不做全局相似度排序，是为了保证「权威内容一定在前」这一不变式（见类注释）。
     */
    public List<ChunkHit> searchMixed(String query, Long problemId, Long excludeSubmissionId) {
        List<ChunkHit> merged = new ArrayList<>(
                search(query, ragProperties.getTopK(), problemId));
        merged.addAll(searchHistory(query, problemId, excludeSubmissionId));
        return merged;
    }

    private List<ChunkHit> filterByScore(List<ChunkHit> hits) {
        if (hits == null) {
            return List.of();
        }
        double minScore = ragProperties.getMinScore();
        if (minScore <= 0) {
            return hits;
        }
        return hits.stream()
                .filter(h -> h.getScore() >= minScore)
                .sorted(Comparator.comparingDouble(ChunkHit::getScore).reversed())
                .toList();
    }

    /** 预览切片结果（不落库），供管理端 ingest 前自检切分粒度 */
    public List<String> previewChunks(String content) {
        return new ArrayList<>(textSplitter.split(content, ragProperties.getChunkSize(),
                ragProperties.getChunkOverlap()));
    }

    public long count() {
        return vectorRepository.count();
    }

    /** 历史点评总数（知识库规模观测的另一半，与切片数一起构成「可召回内容」的全貌） */
    public long countReviews() {
        try {
            return aiReviewRepository.count();
        } catch (Exception e) {
            log.warn("历史点评计数失败：{}", e.getMessage());
            return 0;
        }
    }
}
