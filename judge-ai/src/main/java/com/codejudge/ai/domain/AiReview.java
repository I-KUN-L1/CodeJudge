package com.codejudge.ai.domain;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * AI 点评记录（PG 表 {@code ai_review} 的映射）。
 *
 * <p>表结构见 {@code deploy/pgvector/init.sql}。P5 相对 P1 建表时的增补：
 * 加了 {@code embedding vector(1024)} + HNSW 索引（幂等迁移见
 * {@code deploy/pgvector/migrate-p5.sql}），使历史点评本身也能被向量检索召回
 * —— 这正是需求里「接入 pgvector 用于存储与检索代码上下文及历史点评」的后半句。
 *
 * <p><b>为什么用 JdbcTemplate 而不是 MyBatis-Plus</b>：MyBatis-Plus 的分页插件被
 * {@code MybatisConfig} 写死为 {@code DbType.MYSQL}（见 judge-common），
 * 而 judge_ai 是 PostgreSQL；且 vector 列需要 pgvector 的二进制绑定，
 * 走原生 JDBC 反而更直接。故 judge-ai 不引入 mybatis-plus（成品 jar 中亦无该依赖）。
 */
@Data
public class AiReview {

    private Long id;

    /** 提交 id（点评对象） */
    private Long submissionId;

    /** 提交人 */
    private Long userId;

    /** 题目 id */
    private Long problemId;

    /** 点评类型，见 {@link ReviewType} */
    private Integer reviewType;

    /** 模型名（降级时为 builtin-fallback） */
    private String model;

    /** 触发时的判题结论（AC/WA/TLE/MLE/RE/CE/SE） */
    private String verdict;

    /** Prompt 摘要（不落完整 prompt，避免体积膨胀） */
    private String promptDigest;

    /** 模型输出（Markdown） */
    private String content;

    /** 输入 token 数（LLM 未返回时为 null） */
    private Integer tokensIn;

    /** 输出 token 数 */
    private Integer tokensOut;

    /** 状态：0=生成中 1=完成 2=失败 */
    private Integer status;

    /** 失败原因（status=2 时） */
    private String errorMsg;

    /** 点评正文的向量（用于历史点评检索） */
    private transient float[] embedding;

    private OffsetDateTime createTime;

    private OffsetDateTime updateTime;
}
