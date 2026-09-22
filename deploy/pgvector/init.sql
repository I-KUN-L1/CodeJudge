-- ============================================================
-- CodeJudge RAG 向量库初始化（docker-entrypoint-initdb.d）
-- 容器：pgvector/pgvector:pg16，数据库 judge_ai（由 POSTGRES_DB 指定）
--
-- 说明：judge_ai 与其余 5 个库不同 —— 它是 **PostgreSQL** 而非 MySQL，
-- 因为知识库检索依赖 pgvector 扩展。ai_review（点评记录）与 judge-ai
-- 服务同库，避免跨库联表。
-- ============================================================

-- 启用 pgvector 扩展
CREATE EXTENSION IF NOT EXISTS vector;

-- ------------------------------------------------------------
-- 知识切片表：每行一个 embeddings 向量 + 原文
-- 用途：RAG 检索题目知识点（算法标签、复杂度、常见错误模式、题解要点）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS knowledge_chunk (
    id          BIGSERIAL PRIMARY KEY,
    problem_id  BIGINT,                    -- 关联 judge_problem.problem.id（逻辑关联，不建外键）
    source_type VARCHAR(32),               -- 来源类型：STATEMENT / EDITORIAL / ERROR_PATTERN / TAG_NOTE
    title       VARCHAR(255),
    content     TEXT      NOT NULL,
    -- 维度需与应用侧 cj.llm.embedding-dimension（默认 1024）**严格一致**：
    --   · 智谱 embedding-3 合法维度为 256/512/1024/2048（不含 1536）；
    --   · pgvector 的 HNSW 索引对 vector 类型上限 2000 维，故 2048 维无法建索引；
    --   两者取交集后统一使用 1024。
    -- 若已存在旧数据卷（列维度不同），需执行维度迁移脚本（幂等）。
    embedding   vector(1024),
    create_time TIMESTAMPTZ DEFAULT now()
);

-- HNSW 向量索引：加速 topK 余弦相似检索（embedding <=> ?）
CREATE INDEX IF NOT EXISTS idx_knowledge_chunk_embedding
    ON knowledge_chunk USING hnsw (embedding vector_cosine_ops);

-- 按题目过滤检索时使用的普通索引
CREATE INDEX IF NOT EXISTS idx_knowledge_chunk_problem
    ON knowledge_chunk (problem_id, source_type);

-- ------------------------------------------------------------
-- AI 点评记录表
-- 用途：保存每次代码点评的输入摘要与模型输出，供历史回看与效果评估
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_review (
    id            BIGSERIAL PRIMARY KEY,
    submission_id BIGINT,
    user_id       BIGINT,
    problem_id    BIGINT,
    review_type   SMALLINT,                -- 1=错误诊断 2=主动点评 3=相似题推荐
    model         VARCHAR(64),
    verdict       VARCHAR(8),              -- 触发时的判题结论（AC/WA/TLE/MLE/RE/CE/SE）
    prompt_digest TEXT,                    -- Prompt 摘要（不落完整 prompt，避免体积膨胀）
    content       TEXT,                    -- 模型输出（Markdown）
    -- 点评正文的向量：用于「同题历史点评」召回（few-shot 参考）。
    -- 维度必须与 cj.llm.embedding-dimension 一致（默认 1024，理由同 knowledge_chunk）。
    embedding     vector(1024),
    tokens_in     INT,
    tokens_out    INT,
    status        SMALLINT DEFAULT 0,      -- 0=生成中 1=完成 2=失败
    error_msg     VARCHAR(512),
    create_time   TIMESTAMPTZ DEFAULT now(),
    update_time   TIMESTAMPTZ DEFAULT now()
);

-- ------------------------------------------------------------
-- 幂等迁移：兼容「已存在的旧数据卷」
-- CREATE TABLE IF NOT EXISTS 对已存在的表是空操作，不会补列。
-- 因此这里显式 ADD COLUMN IF NOT EXISTS —— 新库（列已在 CREATE 里）与旧库（补列）
-- 都收敛到同一结构，不需要人工判断该执行哪一段。
-- ------------------------------------------------------------
ALTER TABLE ai_review ADD COLUMN IF NOT EXISTS embedding vector(1024);

CREATE INDEX IF NOT EXISTS idx_ai_review_submission
    ON ai_review (submission_id);
CREATE INDEX IF NOT EXISTS idx_ai_review_user_time
    ON ai_review (user_id, create_time DESC);

-- HNSW 向量索引：加速「同题历史点评」的相似度检索（embedding <=> ?）。
-- 只在 status=1 且 embedding 非空的子集上有意义，故建部分索引 —— 既省索引体积，
-- 也避免半成品（生成中/失败）记录进入检索候选。
CREATE INDEX IF NOT EXISTS idx_ai_review_embedding
    ON ai_review USING hnsw (embedding vector_cosine_ops)
    WHERE status = 1 AND embedding IS NOT NULL;
