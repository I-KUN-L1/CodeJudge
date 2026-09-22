package com.codejudge.ai.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 向量检索命中的知识切片（内部结构，含完整正文供 Prompt 注入）。
 *
 * <p>复用自底座 {@code zx-aigc} 的同名类，新增 {@code problemId} / {@code sourceType}
 * —— judge_ai 的知识切片按「题目」而非「课程」组织（见 deploy/pgvector/init.sql）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChunkHit {

    /** knowledge_chunk.id */
    private Long id;

    /** 关联题目 id（可空：通用算法知识不属于任何具体题目） */
    private Long problemId;

    /** 来源类型：STATEMENT / EDITORIAL / ERROR_PATTERN / TAG_NOTE */
    private String sourceType;

    /** 切片标题 */
    private String title;

    /** 切片正文 */
    private String content;

    /** 余弦相似度（0~1，越接近 1 越相似）；历史点评命中时复用该字段 */
    private double score;
}
