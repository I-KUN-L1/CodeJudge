package com.codejudge.ai.controller;

import com.codejudge.ai.domain.ChunkHit;
import com.codejudge.ai.domain.dto.KnowledgeSearchRequest;
import com.codejudge.ai.domain.dto.KnowledgeUploadRequest;
import com.codejudge.ai.security.AiAccessGuard;
import com.codejudge.ai.security.AiIdentity;
import com.codejudge.ai.service.KnowledgeService;
import com.codejudge.common.domain.R;
import com.codejudge.common.exceptions.BadRequestException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库维护接口（pgvector）。
 *
 * <h3>权限</h3>
 * 全部要求教师/员工角色（{@link AiAccessGuard#requirePrivileged}）。
 * 理由：知识切片会被注入每一次点评的 Prompt，是**面向全体学员的输出内容**。
 * 若能由学员写入，就等于给了一个「向所有同学的 AI 点评里注入指定文本」的通道 ——
 * 这是典型的间接提示注入（indirect prompt injection）入口，危害远大于普通的越权写。
 *
 * <h3>线程模型</h3>
 * 本控制器所有端点都会触发阻塞调用（Embedding HTTP + JDBC），
 * 因此统一以 {@code Mono.fromCallable(...).subscribeOn(boundedElastic())} 包裹，
 * 绝不在 Netty 事件循环上执行。
 */
@Slf4j
@RestController
@RequestMapping("/ai/knowledge")
@RequiredArgsConstructor
@Tag(name = "AI 知识库", description = "pgvector 知识切片的入库、检索与预览（限教师/管理员）")
public class KnowledgeController {

    private final KnowledgeService knowledgeService;
    private final AiAccessGuard accessGuard;

    /**
     * 知识入库：切分 → 逐片向量化 → 写 pgvector。
     *
     * @return 入库切片数
     */
    @PostMapping("/upload")
    @Operation(summary = "知识入库",
            description = "把题面/题解/错误模式等文本切分并向量化写入 pgvector。replace=true 会先清空该题旧切片。"
                    + "需 Embedding 可用；Embedding 未配置时会退化为伪向量（见 EmbeddingService 的告警日志）")
    public Mono<R<Map<String, Object>>> upload(ServerHttpRequest request,
                                               @RequestBody KnowledgeUploadRequest body) {
        accessGuard.requirePrivileged(AiIdentity.from(request));
        if (body == null || body.getContent() == null || body.getContent().isBlank()) {
            throw new BadRequestException("content 不能为空");
        }
        return Mono.fromCallable(() -> {
                    int chunks = knowledgeService.upload(
                            body.getProblemId(),
                            body.getSourceType() == null ? "TAG_NOTE" : body.getSourceType(),
                            body.getTitle() == null ? "未命名切片" : body.getTitle(),
                            body.getContent(),
                            Boolean.TRUE.equals(body.getReplace()));
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("problemId", body.getProblemId());
                    data.put("sourceType", body.getSourceType());
                    data.put("chunks", chunks);
                    data.put("total", knowledgeService.count());
                    return R.ok(data);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 知识检索（可观测性入口）。
     *
     * <p>返回命中切片（含相似度），用于调参与排障 —— RAG 出问题绝大多数是检索侧的问题。
     */
    @PostMapping("/search")
    @Operation(summary = "知识检索",
            description = "按 query 做向量检索，返回命中的知识切片与相似度；includeHistory=true 时包含历史点评")
    public Mono<R<List<Map<String, Object>>>> search(ServerHttpRequest request,
                                                     @RequestBody KnowledgeSearchRequest body) {
        accessGuard.requirePrivileged(AiIdentity.from(request));
        if (body == null || body.getQuery() == null || body.getQuery().isBlank()) {
            throw new BadRequestException("query 不能为空");
        }
        return Mono.fromCallable(() -> {
                    int topK = body.getTopK() == null ? 0 : body.getTopK();
                    List<ChunkHit> hits = Boolean.TRUE.equals(body.getIncludeHistory())
                            ? knowledgeService.searchMixed(body.getQuery(), body.getProblemId(), null)
                            : knowledgeService.search(body.getQuery(), topK, body.getProblemId());
                    List<Map<String, Object>> out = new ArrayList<>(hits.size());
                    for (ChunkHit h : hits) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", h.getId());
                        m.put("problemId", h.getProblemId());
                        m.put("sourceType", h.getSourceType());
                        m.put("title", h.getTitle());
                        m.put("score", h.getScore());
                        m.put("content", h.getContent());
                        out.add(m);
                    }
                    return R.ok(out);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 切片预览（不落库、不向量化）：入库前自检切分粒度是否合适 */
    @PostMapping("/preview")
    @Operation(summary = "切片预览", description = "仅按 chunk-size/overlap 切分并返回，不向量化、不入库")
    public Mono<R<Map<String, Object>>> preview(ServerHttpRequest request,
                                                @RequestBody KnowledgeUploadRequest body) {
        accessGuard.requirePrivileged(AiIdentity.from(request));
        if (body == null || body.getContent() == null || body.getContent().isBlank()) {
            throw new BadRequestException("content 不能为空");
        }
        return Mono.fromCallable(() -> {
                    List<String> chunks = knowledgeService.previewChunks(body.getContent());
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("chunkCount", chunks.size());
                    data.put("chunks", chunks);
                    return R.ok(data);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 知识库规模（切片数 + 历史点评数） */
    @GetMapping("/count")
    @Operation(summary = "知识库规模", description = "返回知识切片数与历史点评数")
    public Mono<R<Map<String, Object>>> count(ServerHttpRequest request) {
        accessGuard.requirePrivileged(AiIdentity.from(request));
        return Mono.fromCallable(() -> {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("knowledgeChunks", knowledgeService.count());
                    data.put("reviews", knowledgeService.countReviews());
                    return R.ok(data);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 清空某题目的知识切片（重新灌库前的准备动作） */
    @DeleteMapping
    @Operation(summary = "清空题目知识", description = "按 problemId 物理删除该题的全部知识切片")
    public Mono<R<Map<String, Object>>> deleteByProblem(ServerHttpRequest request,
                                                        @RequestParam("problemId") Long problemId) {
        accessGuard.requirePrivileged(AiIdentity.from(request));
        return Mono.fromCallable(() -> {
                    knowledgeService.deleteByProblem(problemId);
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("problemId", problemId);
                    data.put("total", knowledgeService.count());
                    return R.ok(data);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }
}
