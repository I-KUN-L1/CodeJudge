package com.codejudge.ai.domain;

import lombok.Data;

/**
 * 知识切片（写入 pgvector 的实体）。
 *
 * <p>复用自底座 {@code zx-aigc} 的同名类，字段按判题域改造：
 * {@code courseId/lessonId} → {@code problemId/sourceType}，与
 * {@code deploy/pgvector/init.sql} 的 {@code knowledge_chunk} 表一一对应。
 */
@Data
public class KnowledgeChunk {

    private Long id;

    /** 关联题目 id（可空：通用算法知识可不对应具体题目） */
    private Long problemId;

    /** 来源类型：STATEMENT / EDITORIAL / ERROR_PATTERN / TAG_NOTE */
    private String sourceType;

    private String title;

    private String content;

    /** 向量（维度 = cj.llm.embedding-dimension），transient 避免被 Jackson 序列化 */
    private transient float[] embedding;
}
