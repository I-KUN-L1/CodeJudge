package com.codejudge.ai.domain.dto;

import lombok.Data;

/**
 * 知识检索请求（{@code POST /ai/knowledge/search}）。
 *
 * <p>它的存在意义是**可观测性**：RAG 的效果问题（「为什么 AI 说不知道」
 * 「为什么引用了不相关的资料」）几乎都出在检索侧，而不是生成侧。
 * 提供一个能直接看到「给定 query 召回哪几条、相似度多少」的端点，
 * 是把 RAG 从「黑盒玄学」变成「可调参数」的前提。
 */
@Data
public class KnowledgeSearchRequest {

    /** 检索文本（必填） */
    private String query;

    /** 限定题目；null 表示跨题检索（通用算法知识） */
    private Long problemId;

    /** 召回条数；≤0 时取配置默认值 cj.rag.top-k */
    private Integer topK;

    /** 是否同时检索历史点评 */
    private Boolean includeHistory;
}
