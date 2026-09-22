package com.codejudge.ai.service;

import com.codejudge.ai.agent.ReviewPromptBuilder;
import com.codejudge.ai.config.LlmProperties;
import com.codejudge.ai.config.RagProperties;
import com.codejudge.ai.config.ReviewProperties;
import com.codejudge.ai.constants.AiRedisKeys;
import com.codejudge.ai.domain.AiReview;
import com.codejudge.ai.domain.ChunkHit;
import com.codejudge.ai.domain.ReviewContext;
import com.codejudge.ai.domain.ReviewEventVO;
import com.codejudge.ai.domain.ReviewSourceVO;
import com.codejudge.ai.domain.ReviewType;
import com.codejudge.ai.domain.vo.ReviewVO;
import com.codejudge.ai.repository.AiReviewRepository;
import com.codejudge.ai.service.memory.ChatMemory;
import com.codejudge.common.exceptions.BizIllegalException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AI 点评服务：SSE 流式生成 + 非流式生成 + 历史查询。
 *
 * <p>对应底座 {@code zx-aigc} 的 {@code ChatService}，沿用其全部 SSE 工程化设计：
 * <ol>
 *   <li><b>事件自增 id</b>：客户端断线后携带 {@code Last-Event-ID} 重连，服务端从 Redis 缓冲
 *       回放未送达的增量，不重新生成 —— 既省 token，也避免前端出现重复内容；</li>
 *   <li><b>心跳</b>：生成期间周期性发送 {@code :ping} 注释，防止中间代理/网关按空闲超时掐断连接
 *       （网关侧另有 {@code metadata.response-timeout: 900000} 放宽）。心跳在 END 后由
 *       {@code takeUntil} 一并终止，否则连接会滞留并持续占用并发配额；</li>
 *   <li><b>并发上限</b>：超出 {@code cj.rag.max-concurrent-streams} 时返回降级提示而非无限堆积长连接。</li>
 * </ol>
 *
 * <h3>相对底座的四处改造（均为缺陷修复或语义对齐，不是风格偏好）</h3>
 * <ol>
 *   <li><b>事件缓冲改为整事件序列</b>。底座只缓冲 DELTA 文本，导致重连回放的 id 与
 *       首次连接的 id 体系不一致（START 的 id 在重连中消失），前端拿不到 reviewId。
 *       这里缓冲完整事件（含 {@code seq}），回放时按 {@code seq > lastEventId} 精确续传，
 *       与 Redis List 因 {@code trim} 截断无关。</li>
 *   <li><b>阻塞 IO 显式切到 boundedElastic</b>。装配上下文要调 Feign + JDBC + Redis，
 *       都是同步阻塞调用；WebFlux 的事件循环线程一旦被阻塞，整个服务的高并发能力直接归零
 *       （表现为「单条点评正常，10 条并发就全线超时」）。底座直接在事件循环上做同类操作。</li>
 *   <li><b>取消即落库</b>。客户端断开时把已生成的部分内容按「中断」入库。
 *       否则大量中途关闭页面的点评会永远停留在 {@code status=0 生成中}，
 *       既污染历史列表，也让「生成中」这个状态失去判别能力。</li>
 *   <li><b>降级是结构化输出而不是占位串</b>。LLM 未配置时仍返回判题结论、逐用例结论与
 *       RAG 命中资料的结构化摘要（并标注 {@code degraded=true}），
 *       而不是一句「AI 不可用」—— 后者对学员毫无价值，还会让人以为功能坏了。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewService {

    /** 降级生成时写入 {@code ai_review.model} 的标识（前端据此显示「非 AI 生成」） */
    private static final String DEGRADED_MODEL = "builtin-fallback";

    /** 点评正文落库状态 */
    private static final int STATUS_GENERATING = 0;
    private static final int STATUS_DONE = 1;
    private static final int STATUS_FAILED = 2;

    private final LlmClient llmClient;
    private final LlmProperties llmProperties;
    private final RagProperties ragProperties;
    private final ReviewProperties reviewProperties;
    private final ReviewContextService contextService;
    private final ReviewPromptBuilder promptBuilder;
    private final AiReviewRepository reviewRepository;
    private final ChatMemory chatMemory;
    private final EmbeddingService embeddingService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /** 当前活跃的 SSE 流式连接数（并发配额） */
    private final AtomicInteger activeStreams = new AtomicInteger(0);

    // ==========================================================================
    // 一、SSE 流式点评
    // ==========================================================================

    /**
     * 流式点评（SSE）。
     *
     * @param lastEventId 非空表示断线重连：只回放未送达事件，不重新生成
     */
    public Flux<ServerSentEvent<ReviewEventVO>> streamReview(Long submissionId, Long callerId, Integer callerRole,
                                                             ReviewType reviewType, String question, Long lastEventId) {
        if (submissionId == null) {
            return Flux.just(plainSse(0, ReviewEventVO.error(400, "submissionId 不能为空", null)));
        }
        String sessionId = AiRedisKeys.reviewSessionId(submissionId);
        if (lastEventId != null && lastEventId >= 0) {
            return replayEvents(sessionId, lastEventId);
        }

        int limit = ragProperties.getMaxConcurrentStreams();
        return Flux.defer(() -> {
            // 订阅时才真实建立 SSE 连接，故配额在此处占用，语义与连接占用对齐
            int cur = activeStreams.incrementAndGet();
            if (limit > 0 && cur > limit) {
                activeStreams.decrementAndGet();
                log.warn("SSE 并发连接数超限({})，返回降级提示 submissionId={}", limit, submissionId);
                return busyStream();
            }
            return streamOnce(submissionId, callerId, callerRole, reviewType, question)
                    .doFinally(sig -> activeStreams.decrementAndGet());
        });
    }

    private Flux<ServerSentEvent<ReviewEventVO>> streamOnce(Long submissionId, Long callerId, Integer callerRole,
                                                            ReviewType reviewType, String question) {
        String sessionId = AiRedisKeys.reviewSessionId(submissionId);
        AtomicInteger seq = new AtomicInteger(0);
        AtomicBoolean persisted = new AtomicBoolean(false);
        AtomicBoolean errored = new AtomicBoolean(false);
        StringBuilder acc = new StringBuilder();
        boolean degraded = !llmProperties.available();

        // ── 装配阶段：本次订阅开始时执行一次（断线重连不走这里，见 streamReview）──
        Mono<ReviewContext> contextMono = Mono.fromCallable(
                        () -> contextService.assemble(submissionId, callerId, callerRole, reviewType, question))
                .subscribeOn(Schedulers.boundedElastic());

        return contextMono.flatMapMany(ctx -> {
            // 落一条 status=0 的记录，让 START 事件就能携带 reviewId
            //（客户端可立即跳转到「历史点评」页；生成中途失败也有迹可循）
            Long reviewId = persistPending(ctx);
            String retrievalSummary = retrievalSummary(ctx);
            List<ReviewSourceVO> sources = toSourceVOs(ctx.allSources());

            // 会话记忆：先把历史 messages 取出来构造 Prompt，**再**写入本轮用户轮次。
            // 顺序不能反 —— loadAsMap 会把刚写入的 user 轮次一并带出，
            // 造成 Prompt 里出现两条一模一样的用户消息（模型会以为是重复强调）。
            List<Map<String, String>> messages = promptBuilder.build(ctx, chatMemory.loadAsMap(sessionId));
            chatMemory.saveMessage(sessionId, "user", userTurnText(ctx));
            // 新一轮生成：清空上一轮的重连缓冲，否则回放会串轮次
            clearBuffer(sessionId);
            // 注意：clearBuffer 必须在 START 事件发射**之前**完成，否则本轮 START 会被立刻 trim 掉

            Flux<String> deltas = Flux.defer(() -> {
                        if (degraded) {
                            return degradedDeltas(ctx);
                        }
                        return llmClient.chatStream(messages);
                    })
                    .doOnNext(acc::append)
                    .onErrorResume(e -> {
                        log.error("点评生成失败 submissionId={}：{}", submissionId, e.toString());
                        errored.set(true);
                        persistFailure(reviewId, e.getMessage(), persisted);
                        return Flux.just("\n\n> ⚠️ 点评生成中断：" + safeMessage(e) + "\n");
                    });

            Flux<ReviewEventVO> body = Flux.concat(
                    Flux.defer(() -> Flux.just(ReviewEventVO.start(
                            reviewId, submissionId, degraded, degraded ? DEGRADED_MODEL : llmProperties.getModel()))),
                    Flux.defer(() -> Flux.just(ReviewEventVO.retrieval(retrievalSummary, submissionId, sources))),
                    deltas.map(d -> ReviewEventVO.delta(d, null)),
                    Flux.defer(() -> {
                        if (!errored.get()) {
                            persistSuccess(reviewId, acc.toString(), ctx, degraded, persisted);
                            chatMemory.saveMessage(sessionId, "assistant", acc.toString());
                        }
                        return Flux.just(ReviewEventVO.end(
                                errored.get() ? "ERROR" : (degraded ? "DEGRADED" : "STOP")));
                    })
            );

            return body.map(vo -> wrap(sessionId, seq, vo))
                    // 取消（客户端断开）时落库已生成内容，避免永久停留在「生成中」
                    .doOnCancel(() -> {
                        if (!persisted.get() && reviewId != null) {
                            persistAborted(reviewId, acc.toString(), persisted);
                            log.info("SSE 连接中断，已保存部分点评 submissionId={}，已生成 {} 字",
                                    submissionId, acc.length());
                        }
                    });
        })
                // 装配阶段的异常（越权 403 / 提交不存在 404 / 上游不可用 503 / PG 不可用）
                // 统一转成 ERROR 事件下发。**不能让它冒泡**：此时 HTTP 响应头早已是
                // 200 + text/event-stream，异常冒泡只会变成「静默断连」
                //（浏览器侧 net::ERR_INCOMPLETE_CHUNKED_ENCODING），前端拿不到任何原因。
                .onErrorResume(e -> errorStream(e, submissionId))
                // 心跳保活 + END 后终止整个流（含心跳），否则连接会滞留并占用并发配额
                .mergeWith(heartbeat())
                .takeUntil(evt -> evt.data() != null && "END".equals(evt.data().getType()))
                .publishOn(Schedulers.boundedElastic());
    }

    /** 连接超限时的降级响应：以 SSE 形式返回提示，保持客户端 SSE 协议通顺 */
    private Flux<ServerSentEvent<ReviewEventVO>> busyStream() {
        return Flux.just(
                plainSse(0, ReviewEventVO.error(429, "当前点评人数较多，请稍后再试", null)),
                plainSse(1, ReviewEventVO.end("BUSY")));
    }

    /**
     * 装配失败时的 SSE 收尾：一个 ERROR 事件 + 一个 END 事件。
     *
     * <p>业务码（401/403/404/503/500）原样透出 —— 前端据此区分「你没权限」
     * 与「服务暂时不可用」，这两类的用户下一步动作完全不同（换账号 vs 稍后重试）。
     * 异常消息经 {@link #safeMessage} 收敛，避免内部 URL / 主机名 / 堆栈外泄。
     */
    private Flux<ServerSentEvent<ReviewEventVO>> errorStream(Throwable e, Long submissionId) {
        int code = 500;
        if (e instanceof BizIllegalException biz) {
            code = biz.getCode();
        } else if (e instanceof com.codejudge.common.exceptions.UnauthorizedException) {
            code = 401;
        } else if (e instanceof com.codejudge.common.exceptions.ForbiddenException) {
            code = 403;
        }
        log.warn("点评流装配失败 submissionId={}，已转为 ERROR 事件：{}", submissionId, e.toString());
        return Flux.just(
                plainSse(0, ReviewEventVO.error(code, safeMessage(e), submissionId)),
                plainSse(1, ReviewEventVO.end("ERROR")));
    }

    // ==========================================================================
    // 二、非流式点评（POST /ai/review 与 MQ 预生成共用）
    // ==========================================================================

    /**
     * 一次性生成点评并落库。
     *
     * <p>返回 {@link Mono} 而不是同步返回值：本方法内部有 Feign / JDBC / LLM 三类阻塞调用，
     * 必须由调用方决定调度线程（WebFlux 控制器用 {@code subscribeOn}，MQ 消费者直接 block）。
     */
    public Mono<ReviewVO> reviewOnce(Long submissionId, Long callerId, Integer callerRole,
                                     ReviewType reviewType, String question) {
        return Mono.fromCallable(() -> doReviewOnce(submissionId, callerId, callerRole, reviewType, question))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 同步生成（MQ 异步预生成与单测直接调用）。<b>调用方必须保证不在 Netty 事件循环线程上。</b>
     */
    public ReviewVO doReviewOnce(Long submissionId, Long callerId, Integer callerRole,
                                 ReviewType reviewType, String question) {
        ReviewContext ctx = contextService.assemble(submissionId, callerId, callerRole, reviewType, question);
        String sessionId = AiRedisKeys.reviewSessionId(submissionId);
        Long reviewId = persistPending(ctx);
        boolean degraded = !llmProperties.available();
        AtomicBoolean persisted = new AtomicBoolean(false);
        try {
            String content;
            if (degraded) {
                content = buildDegradedContent(ctx);
            } else {
                List<Map<String, String>> messages =
                        promptBuilder.build(ctx, chatMemory.loadAsMap(sessionId));
                // block() 在此是安全的：本方法已由调用方调度到 boundedElastic / MQ 消费线程
                content = llmClient.chat(messages).block();
                if (content == null) {
                    content = "";
                }
            }
            persistSuccess(reviewId, content, ctx, degraded, persisted);
            chatMemory.saveMessage(sessionId, "user", userTurnText(ctx));
            chatMemory.saveMessage(sessionId, "assistant", content);
            return ReviewVO.of(reviewRepository.findById(reviewId), ctx.getLanguage());
        } catch (Exception e) {
            log.error("非流式点评失败 submissionId={}：{}", submissionId, e.toString());
            persistFailure(reviewId, e.getMessage(), persisted);
            throw e instanceof BizIllegalException biz ? biz
                    : new BizIllegalException(500, "点评生成失败：" + safeMessage(e));
        }
    }

    // ==========================================================================
    // 三、历史点评
    // ==========================================================================

    /** 某提交的点评历史（新→旧）。</p>归属校验与点评一致：学员仅限自己的提交。 */
    public List<ReviewVO> historyBySubmission(Long submissionId, Long callerId, Integer callerRole, int limit) {
        var dto = contextService.requireOwnedSubmission(submissionId, callerId, callerRole, "只能查看自己提交的点评");
        List<AiReview> rows = reviewRepository.listBySubmission(submissionId, limit <= 0 ? 20 : limit);
        List<ReviewVO> vos = new ArrayList<>(rows.size());
        for (AiReview r : rows) {
            vos.add(ReviewVO.of(r, dto.getLanguage()));
        }
        return vos;
    }

    /** 单条点评详情（同样走归属校验） */
    public ReviewVO detail(Long reviewId, Long callerId, Integer callerRole) {
        AiReview r = reviewRepository.findById(reviewId);
        if (r == null) {
            throw new BizIllegalException(404, "点评记录不存在：" + reviewId);
        }
        var dto = contextService.requireOwnedSubmission(r.getSubmissionId(), callerId, callerRole, "只能查看自己提交的点评");
        return ReviewVO.of(r, dto.getLanguage());
    }

    // ==========================================================================
    // 四、降级内容（LLM 未配置 / 调用失败时的结构化输出）
    // ==========================================================================

    /**
     * 结构化降级正文。
     *
     * <p>设计取向：既然拿不到模型分析，就把**平台已知的事实**整理好还给学员 ——
     * 判题结论、逐用例分布、编译错误、以及知识库命中的参考资料。
     * 这些内容本身就有价值（尤其 CE 的编译日志与 RAG 命中的题解要点），
     * 比一句「AI 不可用」有用得多，也让「降级」不至于看起来像故障。
     */
    private String buildDegradedContent(ReviewContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("> ⚠️ **本内容由系统模板生成，不是 AI 点评。**\n")
                .append("> 原因：未配置大模型（`CJ_LLM_ENABLED=false` 或 `CJ_LLM_API_KEY` 为空）。\n")
                .append("> 配置后可获得基于题目知识库与历史点评的深度分析。以下为平台已知事实的整理。\n\n");

        sb.append("## 结论\n")
                .append("本次提交结论为 **").append(nullSafe(ctx.getVerdict())).append("**");
        if (ctx.getCaseTotal() != null && ctx.getCaseTotal() > 0) {
            sb.append("，通过 ").append(ctx.getCaseAcCount()).append(" / ").append(ctx.getCaseTotal()).append(" 个用例");
        }
        sb.append("。\n\n");

        sb.append("## 判题数据\n");
        sb.append("- 语言：").append(nullSafe(ctx.getLanguage())).append("\n");
        if (ctx.getTimeMs() != null) {
            sb.append("- 最大耗时：").append(ctx.getTimeMs()).append(" ms\n");
        }
        if (ctx.getMemoryKb() != null) {
            sb.append("- 最大内存：").append(ctx.getMemoryKb()).append(" KB\n");
        }
        if (ctx.getScore() != null) {
            sb.append("- 得分：").append(ctx.getScore()).append("\n");
        }

        if (ctx.getCompileInfo() != null && !Boolean.TRUE.equals(ctx.getCompileInfo().getSuccess())) {
            sb.append("\n## 编译错误\n```text\n")
                    .append(truncate(ctx.getCompileInfo().getStderrLog(), 1500))
                    .append("\n```\n");
        }

        List<com.codejudge.api.dto.submission.SubmissionReviewContextDTO.CaseSample> samples = ctx.getCaseSamples();
        if (samples != null && !samples.isEmpty()) {
            sb.append("\n## 逐用例结论\n");
            sb.append("| 序号 | 结论 | 耗时 | 内存 |\n|---|---|---|---|\n");
            for (var c : samples) {
                if (c.getSeq() == null) {
                    continue;
                }
                sb.append("| ").append(c.getSeq())
                        .append(Boolean.TRUE.equals(c.getHidden()) ? "（隐藏）" : "")
                        .append(" | ").append(nullSafe(c.getVerdict()))
                        .append(" | ").append(c.getTimeMs() == null ? "-" : c.getTimeMs() + "ms")
                        .append(" | ").append(c.getMemoryKb() == null ? "-" : c.getMemoryKb() + "KB")
                        .append(" |\n");
            }
            if (ctx.isCaseDigestMasked()) {
                sb.append("\n> 隐藏用例的输出内容对学员不可见（防止答案泄漏），因此上表只有结论。\n");
            }
        }

        sb.append("\n## 参考资料（知识库命中）\n");
        if (ctx.getKnowledge() == null || ctx.getKnowledge().isEmpty()) {
            sb.append("本次未在知识库中检索到与该题目相关的资料。\n");
        } else {
            for (int i = 0; i < ctx.getKnowledge().size(); i++) {
                ChunkHit h = ctx.getKnowledge().get(i);
                sb.append(i + 1).append(". **").append(nullSafe(h.getTitle())).append("**（")
                        .append(nullSafe(h.getSourceType())).append("，相似度 ")
                        .append(String.format("%.2f", h.getScore())).append("）\n   ")
                        .append(truncate(oneLine(h.getContent()), 200)).append("\n");
            }
        }
        if (ctx.getHistory() != null && !ctx.getHistory().isEmpty()) {
            sb.append("\n## 同题历史点评（仅供参考）\n");
            for (ChunkHit h : ctx.getHistory()) {
                sb.append("- ").append(nullSafe(h.getTitle()))
                        .append("（相似度 ").append(String.format("%.2f", h.getScore())).append("）\n");
            }
        }
        sb.append("\n## 下一步建议\n")
                .append("1. 先按「逐用例结论」定位第一个未通过的用例序号，缩小问题范围；\n")
                .append("2. 对照「参考资料」中的题解要点检查思路是否偏离；\n")
                .append("3. 配置 `CJ_LLM_API_KEY` 后重新点评，可获得针对本段代码的逐行分析。\n");
        return sb.toString();
    }

    /** 把降级正文切成小块逐个下发，让降级路径也保持真实的「流式」手感（而不是一次性 3KB 大白块） */
    private Flux<String> degradedDeltas(ReviewContext ctx) {
        String content = buildDegradedContent(ctx);
        List<String> chunks = new ArrayList<>();
        int size = 48;
        for (int i = 0; i < content.length(); i += size) {
            chunks.add(content.substring(i, Math.min(content.length(), i + size)));
        }
        return Flux.fromIterable(chunks);
    }

    // ==========================================================================
    // 五、持久化
    // ==========================================================================

    private Long persistPending(ReviewContext ctx) {
        try {
            AiReview r = new AiReview();
            r.setSubmissionId(ctx.getSubmissionId());
            r.setUserId(ctx.getUserId());
            r.setProblemId(ctx.getProblemId());
            r.setReviewType(ctx.getReviewType().getCode());
            r.setModel(llmProperties.available() ? llmProperties.getModel() : DEGRADED_MODEL);
            r.setVerdict(ctx.getVerdict());
            r.setPromptDigest(promptDigest(ctx));
            r.setContent("");
            r.setStatus(STATUS_GENERATING);
            return reviewRepository.insert(r);
        } catch (Exception e) {
            // 落库失败不该阻断点评本身：点评的价值在于「看到内容」，
            // 记录只是回看用的附属品。降级为无记录（reviewId 为 null，START 事件不携带 id）。
            log.warn("点评记录落库失败，本次点评将不落库：{}", e.toString());
            return null;
        }
    }

    /**
     * 写入成功结果。
     *
     * <p>正文与向量分两步写：向量化要调用外部 Embedding 接口，失败率与耗时都不可控，
     * 而正文是用户可见的成果。分开后「Embedding 挂了」只影响后续的历史点评召回，
     * 不会让本次点评内容丢失（对应 {@link AiReviewRepository#updateEmbedding} 的独立事务）。
     */
    private void persistSuccess(Long reviewId, String content, ReviewContext ctx,
                               boolean degraded, AtomicBoolean persisted) {
        if (reviewId == null || !persisted.compareAndSet(false, true)) {
            return;
        }
        String body = content == null ? "" : content;
        if (body.length() > reviewProperties.getMaxContentLength()) {
            body = body.substring(0, reviewProperties.getMaxContentLength());
        }
        try {
            reviewRepository.updateContent(reviewId, body, null, null, STATUS_DONE, null);
        } catch (Exception e) {
            log.error("点评正文落库失败 reviewId={}：{}", reviewId, e.toString());
            return;
        }
        // 历史点评召回用的向量：仅对真实 AI 内容建索引。
        // 降级模板内容全部雷同（都是同一套表格），入索引只会在检索时霸榜、挤掉真正有价值的历史点评。
        if (degraded || body.isBlank()) {
            return;
        }
        try {
            float[] vec = embeddingForRetrieval(ctx, body);
            reviewRepository.updateEmbedding(reviewId, vec);
        } catch (Exception e) {
            log.warn("点评向量写入失败（不影响本次点评内容）reviewId={}：{}", reviewId, e.toString());
        }
    }

    private void persistFailure(Long reviewId, String errorMsg, AtomicBoolean persisted) {
        if (reviewId == null || !persisted.compareAndSet(false, true)) {
            return;
        }
        try {
            reviewRepository.updateContent(reviewId, "", null, null, STATUS_FAILED, truncate(errorMsg, 500));
        } catch (Exception e) {
            log.warn("点评失败状态落库失败 reviewId={}：{}", reviewId, e.toString());
        }
    }

    private void persistAborted(Long reviewId, String partial, AtomicBoolean persisted) {
        if (!persisted.compareAndSet(false, true)) {
            return;
        }
        try {
            reviewRepository.updateContent(reviewId, partial == null ? "" : partial, null, null,
                    STATUS_FAILED, "客户端中断，已保存部分内容");
        } catch (Exception e) {
            log.warn("中断点评落库失败 reviewId={}：{}", reviewId, e.toString());
        }
    }

    // ==========================================================================
    // 六、SSE 事件 id / 心跳 / 断线重连
    // ==========================================================================

    /**
     * 事件的唯一出口：分配自增 id → 写重连缓冲 → 包成 SSE。
     *
     * <p>缓冲放在唯一出口而不是每个事件各自 push，是为了避免「漏缓冲某一类事件」
     * 导致重连后客户端状态错乱（例如只回放了 DELTA 没有 START，前端就永远拿不到 reviewId）。
     */
    private ServerSentEvent<ReviewEventVO> wrap(String sessionId, AtomicInteger seq, ReviewEventVO vo) {
        int id = seq.getAndIncrement();
        vo.setSeq(id);
        buffer(sessionId, vo);
        return plainSse(id, vo);
    }

    private ServerSentEvent<ReviewEventVO> plainSse(int id, ReviewEventVO vo) {
        return ServerSentEvent.<ReviewEventVO>builder()
                .id(String.valueOf(id))
                .event("message")
                .data(vo)
                .build();
    }

    private Flux<ServerSentEvent<ReviewEventVO>> heartbeat() {
        int seconds = ragProperties.getHeartbeatSeconds();
        if (seconds <= 0) {
            return Flux.empty();
        }
        return Flux.interval(Duration.ofSeconds(seconds))
                .map(i -> ServerSentEvent.<ReviewEventVO>builder().comment("ping").build())
                .onErrorResume(e -> Flux.empty());
    }

    private String evtKey(String sessionId) {
        return AiRedisKeys.SSE_EVENT_PREFIX + sessionId;
    }

    private void buffer(String sessionId, ReviewEventVO vo) {
        try {
            String key = evtKey(sessionId);
            redisTemplate.opsForList().rightPush(key, objectMapper.writeValueAsString(vo));
            redisTemplate.opsForList().trim(key, -ragProperties.getReplayCount(), -1);
        } catch (Exception e) {
            // Redis 不可用时退化为「不可重连」，不影响本次生成
            log.debug("SSE 事件缓冲写入失败：{}", e.getMessage());
        }
    }

    private void clearBuffer(String sessionId) {
        try {
            redisTemplate.delete(evtKey(sessionId));
        } catch (Exception ignored) {
        }
    }

    /**
     * 回放缓冲中 {@code seq > lastEventId} 的事件，并补一个 END 收尾。
     *
     * <p>按事件自带的 {@code seq} 过滤，而不是按下标 ——
     * 缓冲做过 {@code trim}（只留最近 N 条）后，下标与 id 的对应关系会整体偏移，
     * 按下标回放会「重放旧的、丢掉新的」，是断线重连最典型的隐性 bug。
     */
    private Flux<ServerSentEvent<ReviewEventVO>> replayEvents(String sessionId, long lastEventId) {
        List<String> raws;
        try {
            raws = redisTemplate.opsForList().range(evtKey(sessionId), 0, -1);
        } catch (Exception e) {
            return Flux.empty();
        }
        if (raws == null || raws.isEmpty()) {
            // 缓冲已过期（超过 TTL 或服务重启）且没有 END：直接收尾，让客户端重新发起
            return Flux.just(plainSse(0, ReviewEventVO.end("REPLAY_EXPIRED")));
        }
        List<ServerSentEvent<ReviewEventVO>> out = new ArrayList<>();
        int maxSeq = (int) lastEventId;
        for (String raw : raws) {
            try {
                ReviewEventVO vo = objectMapper.readValue(raw, ReviewEventVO.class);
                if (vo.getSeq() != null && vo.getSeq() > lastEventId) {
                    out.add(plainSse(vo.getSeq(), vo));
                    maxSeq = Math.max(maxSeq, vo.getSeq());
                }
            } catch (Exception ignored) {
                // 单条损坏不影响整体回放
            }
        }
        out.add(plainSse(maxSeq + 1, ReviewEventVO.end("REPLAYED")));
        return Flux.fromIterable(out);
    }

    // ==========================================================================
    // 七、辅助
    // ==========================================================================

    private String retrievalSummary(ReviewContext ctx) {
        int k = ctx.getKnowledge() == null ? 0 : ctx.getKnowledge().size();
        int h = ctx.getHistory() == null ? 0 : ctx.getHistory().size();
        if (k == 0 && h == 0) {
            return "未检索到相关参考资料，将基于题目信息与判题结果分析";
        }
        return "已检索到 " + k + " 条题目知识" + (h > 0 ? "、" + h + " 条同题历史点评" : "") + "，开始生成点评";
    }

    private List<ReviewSourceVO> toSourceVOs(List<ChunkHit> hits) {
        List<ReviewSourceVO> list = new ArrayList<>();
        if (hits == null) {
            return list;
        }
        for (ChunkHit h : hits) {
            list.add(new ReviewSourceVO(
                    "HISTORY_REVIEW".equals(h.getSourceType()) ? "HISTORY" : "KNOWLEDGE",
                    h.getTitle(),
                    h.getScore(),
                    truncate(oneLine(h.getContent()), 120)));
        }
        return list;
    }

    /** 会话记忆中的「用户轮次」文案：不复述整段 Prompt，只留可辨识的上下文标签 */
    private String userTurnText(ReviewContext ctx) {
        String head = "【题目：" + nullSafe(ctx.getProblemTitle()) + "｜结论：" + nullSafe(ctx.getVerdict())
                + "｜语言：" + nullSafe(ctx.getLanguage()) + "｜" + ctx.getReviewType().getLabel() + "】";
        if (ctx.getQuestion() != null && !ctx.getQuestion().isBlank()) {
            return head + "\n" + ctx.getQuestion();
        }
        return head + "\n请点评这次提交。";
    }

    /**
     * Prompt 摘要（落 {@code ai_review.prompt_digest}）。
     * 存摘要而非完整 Prompt：完整 Prompt 含整段代码与全部召回片段，
     * 体积是点评正文的数倍，且与 {@code submission.code} 高度重复 —— 存两份没有收益。
     */
    private String promptDigest(ReviewContext ctx) {
        return "problem=" + ctx.getProblemId()
                + " verdict=" + ctx.getVerdict()
                + " lang=" + ctx.getLanguage()
                + " type=" + ctx.getReviewType().getLabel()
                + " codeLen=" + (ctx.getCode() == null ? 0 : ctx.getCode().length())
                + " knowledge=" + (ctx.getKnowledge() == null ? 0 : ctx.getKnowledge().size())
                + " history=" + (ctx.getHistory() == null ? 0 : ctx.getHistory().size());
    }

    /** 用于向量化的文本：题目 + 结论 + 点评正文（正文是主要语义载体） */
    private float[] embeddingForRetrieval(ReviewContext ctx, String content) {
        String text = nullSafe(ctx.getProblemTitle()) + " " + nullSafe(ctx.getVerdict()) + " " + content;
        return embeddingServiceEmbed(text);
    }

    /** 单独抽出来便于将来替换实现（当前直接委托给 EmbeddingService） */
    private float[] embeddingServiceEmbed(String text) {
        return embeddingService.embed(text);
    }

    private String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private String oneLine(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    /** 异常消息可能携带内部 URL / 主机名，对客户端做一次收敛 */
    private String safeMessage(Throwable e) {
        if (e instanceof BizIllegalException biz) {
            return biz.getMessage();
        }
        return e.getClass().getSimpleName();
    }
}
