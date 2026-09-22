package com.codejudge.ai.constants;

/**
 * AI 点评域 Redis Key 全局约定。
 *
 * <p>与判题域的 {@code com.codejudge.common.constants.JudgeRedisKeys} 分开放置：
 * judge-common 是跨服务的公共模块，把「AI 点评」这个单一服务的内部键塞进去
 * 会让公共模块耦合上具体业务语义（且 judge-common 并不依赖 Redis）。
 * 约定一致（前缀集中声明、禁止在业务代码里散落硬编码）在本服务内部同样成立。
 */
public interface AiRedisKeys {

    /**
     * 点评会话记忆：{@code judge:ai:memory:{sessionId}}（String/JSON，TTL 7 天）。
     *
     * <p>sessionId 采用 {@code review:{submissionId}} —— 以「提交」而非「用户」为粒度，
     * 因为点评是围绕一次提交展开的多轮追问（"那我的边界情况该怎么改？"），
     * 换一道题就应该是一段新记忆。按 userId 建会话会把不同题目的上下文搅在一起。
     */
    String REVIEW_MEMORY_PREFIX = "judge:ai:memory:";

    /**
     * SSE 增量事件缓冲：{@code judge:ai:sse:{sessionId}}（String List，仅保留最近 N 条）。
     *
     * <p>用途：客户端断线重连时携带 {@code Last-Event-ID}，服务端回放未送达的增量，
     * 避免"重连即从头生成"——后者既浪费 token，又会让前端出现内容重复。
     */
    String SSE_EVENT_PREFIX = "judge:ai:sse:";

    /** sessionId 构造规则：点评会话 = 一次提交。集中在此，避免各处拼串不一致。 */
    static String reviewSessionId(Long submissionId) {
        return "review:" + submissionId;
    }
}
