package com.codejudge.ai.domain.dto;

import lombok.Data;

/**
 * 点评请求体（{@code POST /ai/review} 与 {@code POST /ai/review/stream}）。
 *
 * <p>为什么 SSE 端点同时提供 GET 与 POST 两种形态：
 * <ul>
 *   <li><b>GET + 查询参数</b>：符合 SSE 的经典形态，便于用 {@code EventSource} /
 *       {@code curl -N} / 浏览器直接打开调试；缺点是 URL 长度受限（追问文本可能较长）且
 *       自定义请求头不友好（{@code EventSource} 无法加 {@code Authorization}，
 *       只能把 token 放查询串，会进日志）。</li>
 *   <li><b>POST + JSON body</b>：本平台前端实际采用的形态。用 {@code fetch} +
 *       {@code ReadableStream} 手工解析 SSE，就能自由携带 {@code Authorization}
 *       与 {@code Last-Event-ID} 头，且 body 无长度顾虑。</li>
 * </ul>
 * 两者最终都调用同一个 {@code ReviewService#streamReview}，不存在两套逻辑。
 */
@Data
public class ReviewRequest {

    /** 提交 id（必填） */
    private Long submissionId;

    /** 点评类型码：1=错误诊断（默认） 2=主动点评 3=相似题推荐 */
    private Integer reviewType;

    /** 学员追问（首轮为空；多轮追问时携带新问题） */
    private String question;
}
