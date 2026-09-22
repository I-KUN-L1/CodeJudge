package com.codejudge.ai.domain.dto;

import lombok.Data;

/**
 * 知识入库请求（{@code POST /ai/knowledge/upload}，限教师/管理员）。
 *
 * <p>{@code sourceType} 取值约定见 {@code deploy/pgvector/init.sql}：
 * {@code STATEMENT}（题面）/ {@code EDITORIAL}（题解）/ {@code ERROR_PATTERN}（错误模式）/
 * {@code TAG_NOTE}（算法标签笔记）。它同时是回答「这条依据是什么性质的内容」的元数据 ——
 * 注入 Prompt 时会连同它一起展示，模型据此决定信任程度。
 */
@Data
public class KnowledgeUploadRequest {

    /** 关联题目 id；为 null 表示通用算法知识（跨题可召回） */
    private Long problemId;

    /** 来源类型：STATEMENT / EDITORIAL / ERROR_PATTERN / TAG_NOTE */
    private String sourceType;

    /** 标题（会与正文一起做向量化，故应写成「浓缩主题」而非「文档名」） */
    private String title;

    /** 正文（按 cj.rag.chunk-size 滑动窗口切分后逐片入库） */
    private String content;

    /**
     * 是否先清空该题目的旧切片再入库。
     *
     * <p>默认 {@code false} 而非 {@code true}：默认值必须是「不破坏数据」的那个。
     * 但重新灌题解时**应当**传 true —— 否则同一段题解会被多次入库，
     * 且因为是近似重复向量，检索时会一起霸榜、挤掉其它有价值的切片。
     */
    private Boolean replace;
}
