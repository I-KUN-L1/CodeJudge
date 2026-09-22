package com.codejudge.ai.controller;

import com.codejudge.ai.domain.ReviewEventVO;
import com.codejudge.ai.domain.ReviewType;
import com.codejudge.ai.domain.dto.ReviewRequest;
import com.codejudge.ai.domain.vo.ReviewVO;
import com.codejudge.ai.security.AiAccessGuard;
import com.codejudge.ai.security.AiIdentity;
import com.codejudge.ai.service.ReviewService;
import com.codejudge.common.domain.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * AI 代码点评对外接口。
 *
 * <h3>三类端点</h3>
 * <table border="1">
 *   <caption>接口清单</caption>
 *   <tr><th>方法</th><th>路径</th><th>返回</th><th>用途</th></tr>
 *   <tr><td>POST</td><td>/ai/review/stream</td><td>SSE</td><td><b>流式点评（主接口）</b>，前端首选用它</td></tr>
 *   <tr><td>GET</td><td>/ai/review/stream</td><td>SSE</td><td>同一逻辑的查询参数形态，便于 curl / EventSource 调试</td></tr>
 *   <tr><td>POST</td><td>/ai/review</td><td>JSON</td><td>非流式，等生成完一次返回（适合后台脚本、导出）</td></tr>
 *   <tr><td>GET</td><td>/ai/review/{submissionId}</td><td>JSON</td><td>某提交的点评历史（新→旧）</td></tr>
 *   <tr><td>GET</td><td>/ai/review/detail/{reviewId}</td><td>JSON</td><td>单条点评详情</td></tr>
 * </table>
 *
 * <h3>为什么错误走 SSE 事件而不是 HTTP 状态码</h3>
 * 见 {@link ReviewEventVO} 的类注释：装配上下文（Feign + JDBC + 向量检索）必然发生在
 * 首个事件发出之前，但一旦订阅开始，HTTP 头就已是 {@code 200 OK + text/event-stream}，
 * 此后再改状态码无意义。故本控制器**不**对流式端点做 try/catch，
 * 而是由 {@link ReviewService} 把异常转成 {@code ERROR} 事件下发 —— 客户端必须处理该事件。
 * 非流式端点则维持常规语义：异常直接抛出，由 {@code CommonExceptionAdvice} 映射为业务码。
 *
 * <h3>并发与线程</h3>
 * 所有阻塞操作（Feign/JDBC/Redis）都在 {@code Schedulers.boundedElastic()} 上执行，
 * Netty 事件循环只负责编码与写回。控制器方法本身不执行任何阻塞调用。
 */
@Slf4j
@RestController
@RequestMapping("/ai/review")
@RequiredArgsConstructor
@Tag(name = "AI 代码点评", description = "LLM 流式点评（SSE）、非流式点评与点评历史")
public class ReviewController {

    private final ReviewService reviewService;
    private final AiAccessGuard accessGuard;

    // ==========================================================================
    // 一、流式点评（SSE）
    // ==========================================================================

    /**
     * 流式点评（POST，推荐）。
     *
     * <p>请求体：{@code {"submissionId":123,"reviewType":1,"question":"..."}}。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "流式点评（SSE）",
            description = "以 text/event-stream 逐段返回点评。事件序列："
                    + "START(reviewId/degraded/model) → RETRIEVAL(检索摘要+sources) → "
                    + "DELTA×N(增量正文) → END(finishReason)。"
                    + "失败以 ERROR 事件下发（HTTP 仍为 200），客户端必须处理。"
                    + "携带 Last-Event-ID 头时表示断线重连，只回放未送达事件、不重新生成。")
    public Flux<ServerSentEvent<ReviewEventVO>> streamPost(
            ServerHttpRequest request,
            @RequestBody(required = false) ReviewRequest body,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {

        AiIdentity identity = accessGuard.requireLogin(AiIdentity.from(request));
        ReviewRequest req = body == null ? new ReviewRequest() : body;
        return doStream(identity, req.getSubmissionId(), req.getReviewType(), req.getQuestion(), lastEventId);
    }

    /**
     * 流式点评（GET，调试友好）。
     *
     * <p>注意：{@code EventSource} 无法自定义请求头，因此用它调用时需要把登录态放在查询串
     * （本接口也接受 {@code token} 查询参数？—— 不，本平台统一走网关注入的
     * {@code user-info} 头，{@code EventSource} 直连网关时浏览器也不会带该头，
     * 所以生产前端请使用上面的 POST 形态）。
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "流式点评（SSE，查询参数形态）",
            description = "与 POST /ai/review/stream 完全等价，便于 curl -N 直接调试")
    public Flux<ServerSentEvent<ReviewEventVO>> streamGet(
            ServerHttpRequest request,
            @RequestParam(value = "submissionId", required = false) Long submissionId,
            @RequestParam(value = "reviewType", required = false) Integer reviewType,
            @RequestParam(value = "question", required = false) String question,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {

        AiIdentity identity = accessGuard.requireLogin(AiIdentity.from(request));
        return doStream(identity, submissionId, reviewType, question, lastEventId);
    }

    /** 两条流式入口的唯一汇合点，保证 GET/POST 的行为完全一致 */
    private Flux<ServerSentEvent<ReviewEventVO>> doStream(AiIdentity identity, Long submissionId,
                                                          Integer reviewType, String question,
                                                          String lastEventId) {
        return reviewService.streamReview(
                submissionId,
                identity.userId(),
                identity.role(),
                ReviewType.of(reviewType),
                question,
                parseLastEventId(lastEventId));
    }

    /**
     * 解析 {@code Last-Event-ID}。
     *
     * <p>容错处理：SSE 规范要求客户端重连时自动带上该头，但值可能是空串或非数字
     * （自定义客户端实现不严谨）。解析失败时返回 {@code null} = 当作全新请求处理，
     * 而不是抛异常让连接直接失败 —— 重连失败比重新生成代价更高。
     */
    private Long parseLastEventId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("Last-Event-ID 非法，按全新请求处理：{}", raw);
            return null;
        }
    }

    // ==========================================================================
    // 二、非流式点评
    // ==========================================================================

    /** 一次性生成点评（阻塞至生成完成再返回）。适合后台脚本与结果导出。 */
    @PostMapping
    @Operation(summary = "生成点评（非流式）",
            description = "等待生成完成后一次性返回。LLM 未配置时返回结构化降级内容（degraded=true），不报 500")
    public Mono<R<ReviewVO>> review(ServerHttpRequest request,
                                    @RequestBody(required = false) ReviewRequest body) {
        AiIdentity identity = accessGuard.requireLogin(AiIdentity.from(request));
        ReviewRequest req = body == null ? new ReviewRequest() : body;
        return reviewService.reviewOnce(req.getSubmissionId(), identity.userId(), identity.role(),
                        ReviewType.of(req.getReviewType()), req.getQuestion())
                .map(R::ok);
    }

    // ==========================================================================
    // 三、点评历史
    // ==========================================================================

    /** 某提交的点评历史（新→旧）。一次提交可以被反复点评（改完再点）。 */
    @GetMapping("/{submissionId}")
    @Operation(summary = "点评历史", description = "按提交 id 查历史点评；学员仅能查自己的提交")
    public Mono<R<List<ReviewVO>>> history(ServerHttpRequest request,
                                           @PathVariable("submissionId") Long submissionId,
                                           @RequestParam(value = "limit", required = false, defaultValue = "20") int limit) {
        AiIdentity identity = accessGuard.requireLogin(AiIdentity.from(request));
        return Mono.fromCallable(() -> reviewService.historyBySubmission(
                        submissionId, identity.userId(), identity.role(), limit))
                .subscribeOn(Schedulers.boundedElastic())
                .map(R::ok);
    }

    /** 单条点评详情。 */
    @GetMapping("/detail/{reviewId}")
    @Operation(summary = "点评详情", description = "按点评记录 id 查详情；同样走归属校验")
    public Mono<R<ReviewVO>> detail(ServerHttpRequest request,
                                    @PathVariable("reviewId") Long reviewId) {
        AiIdentity identity = accessGuard.requireLogin(AiIdentity.from(request));
        return Mono.fromCallable(() -> reviewService.detail(reviewId, identity.userId(), identity.role()))
                .subscribeOn(Schedulers.boundedElastic())
                .map(R::ok);
    }
}
