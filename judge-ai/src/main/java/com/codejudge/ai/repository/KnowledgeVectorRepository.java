package com.codejudge.ai.repository;

import com.codejudge.ai.domain.ChunkHit;
import com.codejudge.ai.domain.KnowledgeChunk;
import com.pgvector.PGvector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

/**
 * pgvector 知识切片仓储（题目知识库）。
 *
 * <p>复用自底座 {@code zx-aigc} 的同名类，
 * 改造点：{@code course_id/lesson_id} → {@code problem_id/source_type}，
 * 与 {@code deploy/pgvector/init.sql} 的 {@code knowledge_chunk} 表对齐。
 *
 * <p><b>为什么不走 MyBatis-Plus / Spring AI VectorStore</b>：
 * <ul>
 *   <li>MyBatis-Plus：{@code vector} 列需要厂商私有的二进制绑定协议，
 *       TypeHandler 反而要手写；且 {@code MybatisConfig} 的分页插件写死了 MySQL 方言。</li>
 *   <li>Spring AI VectorStore：底座 zx-aigc 就是自研实现（{@code KnowledgeVectorRepository}），
 *       引入 Spring AI 等于**新增一套并行的向量抽象**，与「复用、禁止重复开发」相悖。</li>
 * </ul>
 */
@Slf4j
@Repository
public class KnowledgeVectorRepository {

    private final JdbcTemplate jdbcTemplate;

    public KnowledgeVectorRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 在目标连接上注册 pgvector 的 vector 类型，使 {@code setObject(..., new PGvector(...))}
     * 走二进制传输而非字符串拼接。
     */
    private void registerVectorType(PreparedStatement ps) throws SQLException {
        PGvector.addVectorType(ps.getConnection());
    }

    private static final String INSERT_SQL =
            "INSERT INTO knowledge_chunk (problem_id, source_type, title, content, embedding, create_time) "
                    + "VALUES (?, ?, ?, ?, ?, now())";

    public void insert(KnowledgeChunk chunk) {
        jdbcTemplate.update(INSERT_SQL, (PreparedStatementSetter) ps -> {
            registerVectorType(ps);
            ps.setObject(1, chunk.getProblemId());
            ps.setString(2, chunk.getSourceType());
            ps.setString(3, chunk.getTitle());
            ps.setString(4, chunk.getContent());
            ps.setObject(5, new PGvector(chunk.getEmbedding()));
        });
    }

    /**
     * 向量余弦检索 TopK，可选按题目过滤。
     *
     * <p>{@code score = 1 - 余弦距离}。向量在 {@code EmbeddingService} 侧已做 L2 归一化，
     * 因此该值等同余弦相似度，取值区间 [0, 1]。
     *
     * <p>带 {@code problem_id} 过滤时，PostgreSQL 未必选择 HNSW 索引
     * （索引只建在 embedding 上）—— 计划器会按代价在「索引扫描 + 过滤」与
     * 「顺序扫描 + 精确排序」之间取舍。当前知识库规模（数十~数百切片）下两者都够快；
     * 若未来切片量上万，应改为**部分索引**（按 problem_id 分区）或
     * 「先 HNSW 取大 K，再在应用层过滤」的两段式检索。
     *
     * @param problemId null 表示不限题目（跨题检索通用算法知识）
     */
    public List<ChunkHit> searchTopK(float[] query, int topK, Long problemId) {
        if (query == null || query.length == 0 || topK <= 0) {
            return List.of();
        }
        boolean scoped = problemId != null;
        String sql = "SELECT id, problem_id, source_type, title, content, (1 - (embedding <=> ?)) AS score "
                + "FROM knowledge_chunk "
                + (scoped ? "WHERE problem_id = ? " : "")
                + "ORDER BY embedding <=> ? ASC LIMIT ?";
        PreparedStatementSetter setter = ps -> {
            registerVectorType(ps);
            PGvector vec = new PGvector(query);
            int i = 1;
            ps.setObject(i++, vec);
            if (scoped) {
                ps.setObject(i++, problemId);
            }
            // 排序键必须重新给一遍向量：JDBC 的 ? 不能复用同一个占位符
            ps.setObject(i++, vec);
            ps.setInt(i, topK);
        };
        return jdbcTemplate.query(sql, setter, (rs, rowNum) -> {
            ChunkHit hit = new ChunkHit();
            hit.setId(rs.getLong("id"));
            hit.setProblemId((Long) rs.getObject("problem_id"));
            hit.setSourceType(rs.getString("source_type"));
            hit.setTitle(rs.getString("title"));
            hit.setContent(rs.getString("content"));
            hit.setScore(rs.getDouble("score"));
            return hit;
        });
    }

    public void deleteById(Long id) {
        jdbcTemplate.update("DELETE FROM knowledge_chunk WHERE id = ?", id);
    }

    /** 按题目物理删除（重新入库前清旧切片，避免重复片段被反复召回） */
    public void deleteByProblem(Long problemId) {
        jdbcTemplate.update("DELETE FROM knowledge_chunk WHERE problem_id = ?", problemId);
    }

    public long count() {
        Long c = jdbcTemplate.queryForObject("SELECT count(*) FROM knowledge_chunk", Long.class);
        return c == null ? 0 : c;
    }

    /** 按题目统计切片数（知识库覆盖度观测） */
    public long countByProblem(Long problemId) {
        Long c = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM knowledge_chunk WHERE problem_id = ?", Long.class, problemId);
        return c == null ? 0 : c;
    }
}
