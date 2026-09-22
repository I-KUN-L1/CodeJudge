package com.codejudge.ai.service;

import com.codejudge.ai.config.ReviewProperties;
import com.codejudge.ai.domain.ChunkHit;
import com.codejudge.ai.domain.ReviewContext;
import com.codejudge.ai.domain.ReviewType;
import com.codejudge.api.client.problem.ProblemClient;
import com.codejudge.api.client.submission.SubmissionClient;
import com.codejudge.api.dto.problem.ProblemSummaryDTO;
import com.codejudge.api.dto.submission.SubmissionReviewContextDTO;
import com.codejudge.common.constants.UserRole;
import com.codejudge.common.exceptions.BadRequestException;
import com.codejudge.common.exceptions.BizIllegalException;
import com.codejudge.common.exceptions.ForbiddenException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 点评上下文装配：把「提交/判题（judge-submission）+ 题目（judge-problem）+ RAG 召回」聚合为
 * {@link ReviewContext}，并在此处完成**归属鉴权**。
 *
 * <h3>为什么鉴权放在这里而不是 Controller</h3>
 * 归属信息（{@code submission.userId}）只能从 judge-submission 拿到，而这是一次阻塞 Feign 调用。
 * WebFlux 的 Controller 运行在 Netty 事件循环上，在 Controller 里同步调用 Feign 会阻塞事件循环
 * （高并发下直接表现为整个服务卡死）。因此鉴权必须和拉取数据一起在
 * {@code boundedElastic} 线程池上完成 —— 这就是本类的方法被 {@code ReviewService}
 * 包在 {@code Mono.fromCallable(...).subscribeOn(boundedElastic)} 里调用的原因。
 *
 * <h3>安全要点</h3>
 * 学员只能点评**自己的**提交；教师/员工可点评任意提交。这个判断不能省：
 * 提交里含完整代码，若允许任意学员按 submissionId 触发点评，等于开了一个
 * 「输入 id 即可读出他人代码」的侧信道 —— 比直接读 {@code GET /submissions/{id}}
 * 更隐蔽（后者有 {@code OwnerAccessGuard} 挡着）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewContextService {

    private final SubmissionClient submissionClient;
    private final ProblemClient problemClient;
    private final KnowledgeService knowledgeService;
    private final ReviewProperties reviewProperties;

    /**
     * 装配点评上下文。
     *
     * @param submissionId 提交 id
     * @param callerId     调用方用户 id（网关注入的 user-info）
     * @param callerRole   调用方角色（网关注入的 role-info，1员工/2学员/3教师）
     * @param reviewType   点评类型
     * @param question     多轮追问（可为空）
     */
    public ReviewContext assemble(Long submissionId, Long callerId, Integer callerRole,
                                  ReviewType reviewType, String question) {
        if (submissionId == null) {
            throw new BadRequestException("submissionId 不能为空");
        }
        boolean privileged = isPrivileged(callerRole);

        // 拉取提交上下文并完成归属鉴权（数据级权限，见类注释）
        SubmissionReviewContextDTO dto = fetchOwnedSubmission(submissionId, callerId, callerRole, "只能点评自己的提交");

        ReviewContext ctx = new ReviewContext();
        ctx.setSubmissionId(dto.getSubmissionId());
        ctx.setUserId(dto.getUserId());
        ctx.setProblemId(dto.getProblemId());
        ctx.setContestId(dto.getContestId());
        ctx.setLanguage(dto.getLanguage());
        ctx.setCode(dto.getCode());
        ctx.setStatus(dto.getStatus());
        ctx.setVerdict(dto.getVerdict());
        ctx.setScore(dto.getScore());
        ctx.setTimeMs(dto.getTimeMs());
        ctx.setMemoryKb(dto.getMemoryKb());
        ctx.setCaseTotal(dto.getCaseTotal());
        ctx.setCaseAcCount(dto.getCaseAcCount());
        ctx.setCaseSamples(dto.getCaseSamples() == null ? new ArrayList<>() : dto.getCaseSamples());
        ctx.setCompileInfo(dto.getCompileInfo());
        ctx.setReviewType(reviewType);
        ctx.setQuestion(question);
        // 学员视角 + 存在隐藏用例 ⇒ 摘要已被遮蔽，告知模型「不要猜用例内容」
        ctx.setCaseDigestMasked(!privileged && ctx.getCaseSamples().stream()
                .anyMatch(c -> Boolean.TRUE.equals(c.getHidden())));

        fillProblemBrief(ctx);
        fillRetrieval(ctx);
        return ctx;
    }

    private boolean isPrivileged(Integer role) {
        return role != null && (role == UserRole.STAFF.getCode() || role == UserRole.TEACHER.getCode());
    }

    /**
     * 只做「拉取 + 归属校验」的轻量入口，供**点评历史/详情**这类不需要 RAG 检索的场景使用。
     *
     * <p>为什么不复用 {@link #assemble}：assemble 会顺带做题目摘要 Feign 调用与两路向量检索，
     * 而查历史点评只需要 {@code submission.userId} 与 {@code submission.language} 两个字段。
     * 为一次列表查询触发 Embedding 调用（外部 HTTP，可能数百毫秒）是明显的浪费，
     * 也会让「查历史」这个高频只读操作的失败面扩大到 Embedding 服务。
     *
     * @param forbiddenMsg 权限不足时的提示文案（点评与查看历史的语义不同，故由调用方指定）
     */
    public SubmissionReviewContextDTO requireOwnedSubmission(Long submissionId, Long callerId,
                                                             Integer callerRole, String forbiddenMsg) {
        if (submissionId == null) {
            throw new BadRequestException("submissionId 不能为空");
        }
        return fetchOwnedSubmission(submissionId, callerId, callerRole, forbiddenMsg);
    }

    /**
     * 拉取提交上下文并校验归属。
     *
     * <p>鉴权三态，缺一不可：
     * <ol>
     *   <li>上游不可用 → 503（服务端问题，客户端重试有意义，**不能伪装成 404**）；</li>
     *   <li>提交不存在 → 404；</li>
     *   <li>非特权角色且 userId 不匹配 → 403。</li>
     * </ol>
     * 注意第 3 条的判断顺序：必须在**做任何检索之前**完成。
     * 若先检索再鉴权，一次越权请求也会真实消耗向量检索与 Embedding 配额。
     */
    private SubmissionReviewContextDTO fetchOwnedSubmission(Long submissionId, Long callerId,
                                                            Integer callerRole, String forbiddenMsg) {
        boolean privileged = isPrivileged(callerRole);
        // 学员请求时遮蔽隐藏用例摘要 —— 必须在**请求上游时**就传下去，
        // 而不是拿到数据后再本地擦除：数据一旦进入本服务的堆内存，就多了一处泄漏面。
        SubmissionReviewContextDTO dto;
        try {
            dto = submissionClient.getReviewContext(submissionId, !privileged);
        } catch (Exception e) {
            // Feign 异常（含 RDecoder 解包出的业务异常）统一转成可读提示；
            // 原始异常记日志，避免把内部 URL/堆栈透给客户端
            log.warn("拉取提交上下文失败 submissionId={}：{}", submissionId, e.toString());
            throw new BizIllegalException(503, "无法读取该提交的判题信息，请稍后重试");
        }
        if (dto == null) {
            throw new BizIllegalException(404, "提交不存在：" + submissionId);
        }
        if (!privileged && (callerId == null || !callerId.equals(dto.getUserId()))) {
            throw new ForbiddenException(forbiddenMsg);
        }
        return dto;
    }

    /**
     * 题目摘要（标题/难度）。
     *
     * <p>刻意只取摘要（{@code /internal/problems/summaries}）而**不取题面全文与用例**：
     * 题面全文由知识库的 {@code STATEMENT} 切片经向量检索按需注入，
     * 用例（含答案）则完全不需要 —— 点评的依据是「学员代码 vs 判题结论」，
     * 不是「正确答案是什么」。少取一份敏感数据，就少一条泄漏路径。
     *
     * <p>失败不阻断：题目标题缺失只影响 Prompt 的表现力，不影响点评是否可行。
     */
    private void fillProblemBrief(ReviewContext ctx) {
        if (ctx.getProblemId() == null) {
            return;
        }
        try {
            List<ProblemSummaryDTO> list = problemClient.listSummaries(List.of(ctx.getProblemId()));
            if (list != null && !list.isEmpty()) {
                ProblemSummaryDTO p = list.get(0);
                ctx.setProblemTitle(p.getTitle());
                ctx.setDifficulty(p.getDifficulty());
            }
        } catch (Exception e) {
            log.warn("拉取题目摘要失败 problemId={}，点评将在缺少题目标题的情况下继续：{}",
                    ctx.getProblemId(), e.toString());
        }
    }

    /** 向量检索：题目知识 + 历史点评双路召回 */
    private void fillRetrieval(ReviewContext ctx) {
        String query = buildRetrievalQuery(ctx);
        try {
            ctx.setKnowledge(knowledgeService.search(query, 0, ctx.getProblemId()));
        } catch (Exception e) {
            // PG 不可用：降级为「无参考资料」的点评，而不是让整次点评失败
            log.warn("题目知识检索失败，本次不注入知识片段：{}", e.toString());
            ctx.setKnowledge(List.of());
        }
        ctx.setHistory(knowledgeService.searchHistory(query, ctx.getProblemId(), ctx.getSubmissionId()));
    }

    /**
     * 构造检索 query。
     *
     * <p>不是直接把代码丢进去检索：代码里的标识符（{@code i}, {@code arr}, {@code solve}）
     * 对向量模型几乎是噪声，会稀释真正有区分度的信号。真正该检索的是
     * 「哪道题 + 什么结论 + 什么语言 + 学员在问什么」—— 这才是与知识库内容对齐的语义。
     * 学员追问文本（若有）权重最高，放在最后。
     */
    private String buildRetrievalQuery(ReviewContext ctx) {
        StringBuilder q = new StringBuilder();
        if (ctx.getProblemTitle() != null && !ctx.getProblemTitle().isBlank()) {
            q.append(ctx.getProblemTitle()).append(" ");
        }
        if (ctx.getVerdict() != null) {
            q.append(verdictKeyword(ctx.getVerdict())).append(" ");
        }
        if (ctx.getLanguage() != null) {
            q.append(languageKeyword(ctx.getLanguage())).append(" ");
        }
        q.append(reviewTypeKeyword(ctx.getReviewType()));
        if (ctx.getQuestion() != null && !ctx.getQuestion().isBlank()) {
            q.append(" ").append(ctx.getQuestion());
        }
        return q.toString().trim();
    }

    /** 把判题结论展开成自然语言关键词：知识库里写的是「超时」，不是「TLE」 */
    private String verdictKeyword(String verdict) {
        return switch (verdict) {
            case "AC" -> "通过 优化建议";
            case "WA" -> "答案错误 边界 溢出";
            case "TLE" -> "超时 时间复杂度过高";
            case "MLE" -> "内存超限 空间复杂度";
            case "RE" -> "运行时错误 越界 空指针 除零";
            case "CE" -> "编译错误 语法";
            case "SE" -> "判题系统异常";
            default -> verdict;
        };
    }

    private String languageKeyword(String language) {
        return switch (language.toUpperCase()) {
            case "JAVA" -> "Java";
            case "PYTHON" -> "Python";
            case "CPP" -> "C++";
            case "GO" -> "Go";
            default -> language;
        };
    }

    private String reviewTypeKeyword(ReviewType type) {
        return switch (type) {
            case ERROR_DIAGNOSIS -> "错因分析";
            case CODE_REVIEW -> "代码质量 复杂度";
            case SIMILAR_RECOMMEND -> "知识点 题型";
        };
    }
}
