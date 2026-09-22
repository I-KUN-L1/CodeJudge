package com.codejudge.ai.repository;

import com.codejudge.ai.domain.AiReview;
import com.codejudge.ai.domain.ChunkHit;
import com.pgvector.PGvector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;

/**
 * AI 点评记录仓储（PG 表 {@code ai_review}）。
 *
 * <p>承担两件事：
 * <ol>
 *   <li><b>落库</b>：每次点评的输入摘要、模型输出、token 用量与状态（供历史回看与效果评估）；</li>
 *   <li><b>向量检索</b>：把历史点评正文的向量化结果存进 {@code ai_review.embedding}
 *       （HNSW 索引），使「同一道题过去的高质量点评」能作为 few-shot 参考被召回
 *       —— 这正是需求里「接入 pgvector 用于存储与检索代码上下文<b>及历史点评</b>」的后半句。</li>
 * </ol>
 *
 * <p><b>为什么 embedding 与正文同表而不再建一张 review_chunk</b>：
 * 一条点评就是一个天然语义单元（不像题面需要切片），单独建表只会多一张表、
 * 多一次 JOIN，换不来任何收益。切片只对「长文档」有意义。
 */
@Slf4j
@Repository
public class AiReviewRepository {

    private final JdbcTemplate jdbcTemplate;

    public AiReviewRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private void registerVectorType(PreparedStatement ps) throws java.sql.SQLException {
        PGvector.addVectorType(ps.getConnection());
    }

    private static final String INSERT_SQL =
            "INSERT INTO ai_review (submission_id, user_id, problem_id, review_type, model, verdict, "
                    + "prompt_digest, content, status, create_time, update_time) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now()) "
                    + "RETURNING id";

    /**
     * 插入点评记录（status=0 生成中），返回自增主键。
     *
     * <p>⚠️ <b>必须用 PG 的 {@code RETURNING id}，不能用
     * {@code Statement.RETURN_GENERATED_KEYS} + {@code KeyHolder.getKey()}。</b>
     *
     * <p>PG 驱动在后者模式下会把<b>整行所有列</b>都当作返回的 key，{@code getKey()}
     * 随即抛 {@code InvalidDataAccessApiUsageException: ... contains multiple keys}。
     * MySQL 驱动只返回自增主键列 —— 所以同一段代码从 MySQL 项目照搬过来，
     * 会表现为「<b>INSERT 成功但拿不到 id</b>」：记录写进表却永远停在 status=0，
     * START 事件不带 reviewId，非流式接口返回空 body。这是一个<b>不抛错、只丢数据</b>的
     * 静默故障，编译期与常规单测都发现不了。（P5 端到端验收实测捕获，见 docs/P5-REPORT.md。）
     *
     * <p>这里不追求「跨库可移植」：本服务只连 PG（RAG 依赖 pgvector），该诉求不存在，
     * 用 PG 惯用法最直白可靠。
     */
    public Long insert(AiReview review) {
        return jdbcTemplate.queryForObject(INSERT_SQL, Long.class,
                review.getSubmissionId(), review.getUserId(), review.getProblemId(),
                review.getReviewType(), review.getModel(), review.getVerdict(),
                review.getPromptDigest(), review.getContent(), review.getStatus());
    }

    /**
     * 写入生成结果（正文 + 向量 + 状态）。
     *
     * <p>向量写入单独一条 SQL：pgvector 的二进制绑定与普通参数绑定在同一个
     * PreparedStatement 上混用虽然可行，但「正文写入失败」和「向量写入失败」
     * 是两类完全不同的故障（前者影响回看，后者只影响后续召回），
     * 拆开后可以让正文本地事务化、向量失败仅告警 —— 点评不该因为
     * 一次 embedding 调用失败而丢掉整篇内容。
     */
    public void updateContent(Long id, String content, Integer tokensIn, Integer tokensOut,
                              int status, String errorMsg) {
        jdbcTemplate.update(
                "UPDATE ai_review SET content = ?, tokens_in = ?, tokens_out = ?, status = ?, "
                        + "error_msg = ?, update_time = now() WHERE id = ?",
                content, tokensIn, tokensOut, status, errorMsg, id);
    }

    /** 更新点评正文的向量（用于历史点评召回）。失败由调用方告警并忽略。 */
    public void updateEmbedding(Long id, float[] embedding) {
        if (embedding == null || embedding.length == 0) {
            return;
        }
        jdbcTemplate.update("UPDATE ai_review SET embedding = ? WHERE id = ?",
                (PreparedStatementSetter) ps -> {
                    registerVectorType(ps);
                    ps.setObject(1, new PGvector(embedding));
                    ps.setObject(2, id);
                });
    }

    private static final String COLS =
            "id, submission_id, user_id, problem_id, review_type, model, verdict, prompt_digest, "
                    + "content, tokens_in, tokens_out, status, error_msg, create_time, update_time";

    private AiReview map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        AiReview r = new AiReview();
        r.setId(rs.getLong("id"));
        r.setSubmissionId((Long) rs.getObject("submission_id"));
        r.setUserId((Long) rs.getObject("user_id"));
        r.setProblemId((Long) rs.getObject("problem_id"));
        Object rt = rs.getObject("review_type");
        r.setReviewType(rt == null ? null : ((Number) rt).intValue());
        r.setModel(rs.getString("model"));
        r.setVerdict(rs.getString("verdict"));
        r.setPromptDigest(rs.getString("prompt_digest"));
        r.setContent(rs.getString("content"));
        Object ti = rs.getObject("tokens_in");
        r.setTokensIn(ti == null ? null : ((Number) ti).intValue());
        Object to = rs.getObject("tokens_out");
        r.setTokensOut(to == null ? null : ((Number) to).intValue());
        Object st = rs.getObject("status");
        r.setStatus(st == null ? null : ((Number) st).intValue());
        r.setErrorMsg(rs.getString("error_msg"));
        r.setCreateTime(rs.getObject("create_time", java.time.OffsetDateTime.class));
        r.setUpdateTime(rs.getObject("update_time", java.time.OffsetDateTime.class));
        return r;
    }

    public AiReview findById(Long id) {
        List<AiReview> list = jdbcTemplate.query(
                "SELECT " + COLS + " FROM ai_review WHERE id = ?", this::map, id);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 某提交的点评历史（新→旧）。一次提交可以被反复点评（改完再点）。 */
    public List<AiReview> listBySubmission(Long submissionId, int limit) {
        return jdbcTemplate.query(
                "SELECT " + COLS + " FROM ai_review WHERE submission_id = ? "
                        + "ORDER BY create_time DESC LIMIT ?",
                this::map, submissionId, limit);
    }

    /**
     * 历史点评向量召回：同题目下、已生成完成、且已写入向量的点评，按相似度取 TopK。
     *
     * <p>{@code excludeSubmissionId} 用于排除**本次提交**的历史点评 ——
     * 同一提交被重复点评时，上一次的输出与本次输入高度重合，
     * 若被当成「参考资料」召回，会让模型把自己的旧结论当权威事实复述，
     * 形成自我强化的回音室（且第一次的结论若有错，会一直错下去）。
     */
    public List<ChunkHit> searchHistory(float[] query, Long problemId, int topK, Long excludeSubmissionId) {
        if (query == null || query.length == 0 || topK <= 0 || problemId == null) {
            return List.of();
        }
        String sql = "SELECT id, content, verdict, (1 - (embedding <=> ?)) AS score "
                + "FROM ai_review "
                + "WHERE problem_id = ? AND status = 1 AND embedding IS NOT NULL "
                + "  AND (? IS NULL OR submission_id <> ?) "
                + "ORDER BY embedding <=> ? ASC LIMIT ?";
        PreparedStatementSetter setter = ps -> {
            registerVectorType(ps);
            PGvector vec = new PGvector(query);
            ps.setObject(1, vec);
            ps.setObject(2, problemId, java.sql.Types.BIGINT);
            // ⚠️ 必须显式指定 JDBC 类型：`? IS NULL` 里的占位符没有可推断的列上下文，
            //    纯 setObject(i, null) 会让 PG 报 "could not determine data type of parameter"。
            ps.setObject(3, excludeSubmissionId, java.sql.Types.BIGINT);
            ps.setObject(4, excludeSubmissionId, java.sql.Types.BIGINT);
            ps.setObject(5, vec);
            ps.setInt(6, topK);
        };
        return jdbcTemplate.query(sql, setter, (rs, rowNum) -> {
            ChunkHit hit = new ChunkHit();
            hit.setId(rs.getLong("id"));
            hit.setProblemId(problemId);
            hit.setSourceType("HISTORY_REVIEW");
            hit.setTitle("历史点评 · " + rs.getString("verdict"));
            hit.setContent(rs.getString("content"));
            hit.setScore(rs.getDouble("score"));
            return hit;
        });
    }

    public long count() {
        Long c = jdbcTemplate.queryForObject("SELECT count(*) FROM ai_review", Long.class);
        return c == null ? 0 : c;
    }
}
