package com.codejudge.ai.domain;

/**
 * 知识切片来源类型（与 {@code knowledge_chunk.source_type} 的取值对齐）。
 *
 * <p>分类的意义在于**召回时可加权**：{@code ERROR_PATTERN}（常见错误模式）对
 * 「错误诊断」的参考价值远高于 {@code STATEMENT}（题面）。后续需要调权重时，
 * 只需在 {@code KnowledgeService} 里按类型做一次重排，不必改表结构。
 */
public enum SourceType {

    /** 题面要点（题意、输入输出规格、约束） */
    STATEMENT,

    /** 题解/思路（算法、复杂度的正解要点） */
    EDITORIAL,

    /** 常见错误模式（WA/TLE/MLE/RE 的典型成因） */
    ERROR_PATTERN,

    /** 标签/知识点说明（数据结构与算法范式的通用知识） */
    TAG_NOTE
}
