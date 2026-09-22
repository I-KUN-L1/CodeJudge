package com.codejudge.ai.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 点评流式事件（SSE 推送体）。
 *
 * <p>沿用底座 {@code ChatEventVO} 的 {@code START / DELTA / END} 三段式契约
 * （前端既有的 SSE 事件机可以零改动复用），并按点评场景扩展三类事件与三个字段：
 *
 * <table border="1">
 *   <caption>事件类型</caption>
 *   <tr><th>type</th><th>语义</th><th>content</th><th>其他字段</th></tr>
 *   <tr><td>START</td><td>开始生成，携带 reviewId</td><td>空</td><td>reviewId / degraded / model</td></tr>
 *   <tr><td>RETRIEVAL</td><td>RAG 检索完成，**先于生成推送**</td><td>检索摘要文案</td><td>sources</td></tr>
 *   <tr><td>DELTA</td><td>增量正文（逐字/逐块）</td><td>增量文本</td><td>seq</td></tr>
 *   <tr><td>ERROR</td><td>失败（含越权/上下文缺失），**不抛 HTTP 5xx**</td><td>错误提示</td><td>code</td></tr>
 *   <tr><td>END</td><td>正常结束</td><td>空</td><td>finishReason</td></tr>
 * </table>
 *
 * <p><b>为什么错误走 SSE 事件而不是 HTTP 状态码</b>：点评链路要在生成前先做
 * 「拉取判题上下文 + 归属校验 + 向量检索」这些阻塞操作（Feign/JDBC），
 * 它们必然发生在 Netty 事件循环之外（boundedElastic）。等结果回来时 HTTP 响应头
 * 早已随首个事件 `200 OK` 发出，此刻再改状态码已无意义。
 * 这也是主流流式 API（OpenAI/SSE 规范实践）的通行做法 —— 客户端必须处理 ERROR 事件。
 * 非流式端点（{@code POST /ai/review}）则照常返回业务错误码。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReviewEventVO {

    /** 事件类型：START / RETRIEVAL / DELTA / ERROR / END */
    private String type;

    /** 增量文本（DELTA）或提示文案（RETRIEVAL / ERROR） */
    private String content;

    /** 事件序号，与 SSE 的 id 一致，便于前端对齐与去重 */
    private Integer seq;

    /** 点评记录 id（START 事件携带；未落库时为 null） */
    private Long reviewId;

    /** 提交 id */
    private Long submissionId;

    /** RAG 命中的参考来源（RETRIEVAL 事件携带，供前端在生成期间就展示"依据"） */
    private List<ReviewSourceVO> sources;

    /** 是否处于降级模式（LLM 未配置）—— 前端据此显示醒目提示，避免把模板文案误当成 AI 结论 */
    private Boolean degraded;

    /** 实际使用的模型名 */
    private String model;

    /** ERROR 事件的业务码（沿用 R 的 code 口径：401/403/404/500…） */
    private Integer code;

    /** END 事件的结束原因：STOP（正常）/ DEGRADED（降级）/ ERROR */
    private String finishReason;

    public static ReviewEventVO start(Long reviewId, Long submissionId, boolean degraded, String model) {
        return new ReviewEventVO("START", "", null, reviewId, submissionId, null,
                degraded, model, null, null);
    }

    public static ReviewEventVO retrieval(String summary, Long submissionId, List<ReviewSourceVO> sources) {
        return new ReviewEventVO("RETRIEVAL", summary, null, null, submissionId, sources,
                null, null, null, null);
    }

    public static ReviewEventVO delta(String content, Integer seq) {
        return new ReviewEventVO("DELTA", content, seq, null, null, null,
                null, null, null, null);
    }

    public static ReviewEventVO error(int code, String message, Long submissionId) {
        return new ReviewEventVO("ERROR", message, null, null, submissionId, null,
                null, null, code, null);
    }

    public static ReviewEventVO end(String finishReason) {
        return new ReviewEventVO("END", "", null, null, null, null,
                null, null, null, finishReason);
    }
}
