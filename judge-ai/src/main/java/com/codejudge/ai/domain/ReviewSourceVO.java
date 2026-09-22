package com.codejudge.ai.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * RAG 参考来源（下发给前端做「可溯源」展示）。
 *
 * <p>与 {@link ChunkHit} 的区别：{@code ChunkHit} 是仓储层返回的内部结构（含完整切片正文，
 * 用于注入 Prompt）；本类是**对外契约**，只带标题 + 相似度 + 截断预览，
 * 不把完整切片正文回吐给客户端 —— 向量库里的题解要点属于平台资产，
 * 学员端只需知道「AI 依据了哪几条资料」，无需拿到全文。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReviewSourceVO {

    /** 来源类型：KNOWLEDGE（题目知识库）/ HISTORY（历史点评） */
    private String kind;

    /** 标题（题目知识为切片标题；历史点评为「历史点评 · 结论」） */
    private String title;

    /** 余弦相似度（0~1，越接近 1 越相似） */
    private double score;

    /** 正文预览（截断至 120 字符） */
    private String preview;
}
