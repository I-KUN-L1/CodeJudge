package com.codejudge.ai.agent;

import com.codejudge.ai.config.ReviewProperties;
import com.codejudge.ai.domain.ChunkHit;
import com.codejudge.ai.domain.ReviewContext;
import com.codejudge.ai.domain.ReviewType;
import com.codejudge.api.dto.submission.SubmissionReviewContextDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 点评 Prompt 构造器。
 *
 * <p>对应底座 {@code zx-aigc} 的 {@code AbstractAgent#buildMessages} + {@code systemPrompt()}：
 * 底座的 Prompt 分散在 4 个教育域 Agent（咨询/推荐/知识/购买）里，本项目只有一个领域，
 * 因此把「角色设定 + 输出结构 + 硬约束」与「用户材料拼装」收在一个类里，不保留 Agent 继承树
 * —— 一个实现类的抽象层是纯粹的负债。
 *
 * <h3>三条硬约束（都是为了「有用」而不是「好看」）</h3>
 * <ol>
 *   <li><b>不臆造用例</b>：隐藏用例的输入输出对模型不可见（{@code maskHidden} 已遮蔽摘要），
 *       因此必须明令禁止猜测「第 3 个用例的输入是 xxx」。否则模型会为了显得具体而编造数据，
 *       而学员无法分辨哪句是事实、哪句是编的。</li>
 *   <li><b>默认不给完整可提交代码</b>（{@code cj.review.allow-full-solution}）：
 *       点评平台的核心价值是讲清「错在哪、该怎么想」。默认给可直接粘贴的完整题解，
 *       会把刷题场变成答案库 —— 学员故意提交一次 WA 再让 AI 补全即可。</li>
 *   <li><b>引用要落到材料上</b>：明确要求指出结论依据的是哪一段资料，
 *       让「有据可查」成为输出习惯，而不是让模型自由发挥。</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class ReviewPromptBuilder {

    private final ReviewProperties reviewProperties;

    /** 构造 LLM 的 messages（system + [历史] + user） */
    public List<Map<String, String>> build(ReviewContext ctx, List<Map<String, String>> historyMessages) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt(ctx)));
        if (historyMessages != null) {
            messages.addAll(historyMessages);
        }
        messages.add(Map.of("role", "user", "content", userPrompt(ctx)));
        return messages;
    }

    // ==========================================================================
    // System Prompt：角色 + 输出结构 + 硬约束 + RAG 材料
    // ==========================================================================

    private String systemPrompt(ReviewContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是 CodeJudge 的资深算法竞赛教练与代码评审专家，负责对学员的提交给出**可执行**的点评。\n")
                .append("你的读者是正在刷题的学员：他需要知道「错在哪、为什么错、下一次怎么想」，而不是泛泛的鼓励。\n\n")
                .append("【输出格式】严格使用以下 Markdown 章节（保留标题文字，不要增删章节）：\n")
                .append(outputFormat(ctx.getReviewType()))
                .append("\n【硬约束】\n")
                .append("1. 只依据【题目信息】【判题结果】与【参考资料】中给出的事实下结论。\n")
                .append("2. **严禁编造测试用例数据**。隐藏用例的输入与期望输出对你不可见（仅有结论与耗时/内存），")
                .append("请描述「哪一类数据会触发该问题」（如「最大值附近的溢出」），")
                .append("绝不要写出具体的输入值或期望输出。\n")
                .append("3. ");
        if (reviewProperties.isAllowFullSolution()) {
            sb.append("允许给出完整的修改后代码，请放在 Markdown 代码块中并标注语言。\n");
        } else {
            sb.append("**不要给出完整可提交的代码**。请描述修改思路，并给出**关键片段**")
                    .append("（不超过 10 行、且不足以直接提交通过）。目标是教会思路，不是替代作答。\n");
        }
        sb.append("4. 若材料不足以支撑某个结论，直接写「材料不足，无法判断」，不要用推测填空。\n")
                .append("5. 用简体中文，语气专业克制，不使用表情符号。\n");

        appendRetrievedContext(sb, ctx);
        return sb.toString();
    }

    /** 按点评类型给出结构（标题固定 → 前端可稳定解析锚点，也是可回归测试的一部分） */
    private String outputFormat(ReviewType type) {
        return switch (type) {
            case ERROR_DIAGNOSIS -> """
                    ## 结论
                    （一句话说明这次提交的核心问题）
                    ## 错因定位
                    （指向代码中的具体位置与判题结论的对应关系）
                    ## 逐项分析
                    （结合逐用例结论/编译信息展开，说明为什么会导致这个结论）
                    ## 修改建议
                    （可落地的改法，含关键片段；说明改完后预期能过哪类用例）
                    ## 相关知识点
                    （可迁移的算法/语言知识点，2~4 条）
                    """;
            case CODE_REVIEW -> """
                    ## 总体评价
                    ## 正确性与鲁棒性
                    ## 复杂度分析
                    （时间/空间复杂度，并结合本题限制说明是否有超限风险）
                    ## 可读性与风格
                    ## 优化建议
                    """;
            case SIMILAR_RECOMMEND -> """
                    ## 错因归类
                    ## 可迁移知识点
                    ## 推荐练习方向
                    （给出题型/知识点标签，而不是编造具体题目编号）
                    """;
        };
    }

    /**
     * 注入 RAG 召回内容。
     *
     * <p>题目知识与历史点评**分区呈现且标注来源**：模型能据此区分「平台权威资料」与
     * 「过去某次点评的旧输出」，从而在冲突时以权威资料为准。
     * 历史点评额外标注其原始判题结论 —— 一条来自 WA 提交的旧点评，
     * 其可信度显然低于来自 AC 提交的。
     */
    private void appendRetrievedContext(StringBuilder sb, ReviewContext ctx) {
        List<ChunkHit> knowledge = ctx.getKnowledge();
        List<ChunkHit> history = ctx.getHistory();
        if ((knowledge == null || knowledge.isEmpty()) && (history == null || history.isEmpty())) {
            sb.append("\n【参考资料】\n（本次未检索到相关资料，请仅依据题目信息与判题结果分析，")
                    .append("并在结论中说明分析依据有限。）\n");
            return;
        }
        if (knowledge != null && !knowledge.isEmpty()) {
            sb.append("\n【参考资料 · 题目知识库（权威内容，冲突时以此为准）】\n");
            for (int i = 0; i < knowledge.size(); i++) {
                ChunkHit h = knowledge.get(i);
                sb.append("[").append(i + 1).append("] ")
                        .append(nullToEmpty(h.getSourceType())).append(" · ")
                        .append(nullToEmpty(h.getTitle()))
                        .append("（相似度 ").append(String.format("%.2f", h.getScore())).append("）\n")
                        .append(h.getContent()).append("\n");
            }
        }
        if (history != null && !history.isEmpty()) {
            sb.append("\n【参考资料 · 历史点评（模型此前的旧输出，仅供参考，不代表正确结论）】\n");
            for (int i = 0; i < history.size(); i++) {
                ChunkHit h = history.get(i);
                sb.append("[H").append(i + 1).append("] ")
                        .append(nullToEmpty(h.getTitle()))
                        .append("（相似度 ").append(String.format("%.2f", h.getScore())).append("）\n")
                        .append(truncate(h.getContent(), 1200)).append("\n");
            }
        }
    }

    // ==========================================================================
    // User Prompt：题目 + 判题结果 + 代码
    // ==========================================================================

    private String userPrompt(ReviewContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("【题目信息】\n")
                .append("题目：").append(nullToEmpty(ctx.getProblemTitle())).append("\n");
        if (ctx.getDifficulty() != null) {
            sb.append("难度：").append(ctx.getDifficulty()).append(" / 5\n");
        }
        sb.append("提交语言：").append(nullToEmpty(ctx.getLanguage())).append("\n\n");

        sb.append("【判题结果】\n")
                .append("结论：").append(nullToEmpty(ctx.getVerdict()))
                .append("（状态 ").append(nullToEmpty(ctx.getStatus())).append("）\n");
        if (ctx.getScore() != null) {
            sb.append("得分：").append(ctx.getScore()).append("\n");
        }
        if (ctx.getTimeMs() != null) {
            sb.append("最大耗时：").append(ctx.getTimeMs()).append(" ms\n");
        }
        if (ctx.getMemoryKb() != null) {
            sb.append("最大内存：").append(ctx.getMemoryKb()).append(" KB\n");
        }
        if (ctx.getCaseTotal() != null) {
            sb.append("用例通过：").append(ctx.getCaseAcCount()).append(" / ").append(ctx.getCaseTotal()).append("\n");
        }
        sb.append("\n");

        appendCompileInfo(sb, ctx);
        appendCaseSamples(sb, ctx);

        sb.append("【学员代码】\n```").append(languageFence(ctx.getLanguage())).append("\n")
                .append(truncateCode(ctx.getCode()))
                .append("\n```\n");

        if (ctx.getQuestion() != null && !ctx.getQuestion().isBlank()) {
            sb.append("\n【学员追问】\n").append(ctx.getQuestion()).append("\n");
        }
        return sb.toString();
    }

    /** CE 时编译日志是唯一有效线索，单独成节并放在用例之前 */
    private void appendCompileInfo(StringBuilder sb, ReviewContext ctx) {
        SubmissionReviewContextDTO.CompileInfoBrief ci = ctx.getCompileInfo();
        if (ci == null) {
            return;
        }
        sb.append("【编译结果】").append(Boolean.TRUE.equals(ci.getSuccess()) ? "编译通过" : "编译失败").append("\n");
        if (ci.getDurationMs() != null) {
            sb.append("编译耗时：").append(ci.getDurationMs()).append(" ms\n");
        }
        if (ci.getStderrLog() != null && !ci.getStderrLog().isBlank()) {
            sb.append("编译器错误输出：\n```text\n").append(truncate(ci.getStderrLog(), 2000)).append("\n```\n");
        }
        sb.append("\n");
    }

    /**
     * 逐用例样本。
     *
     * <p>隐藏用例的输出摘要在上游已被遮蔽（{@code maskHidden}），此处只呈现
     * 「序号 + 结论 + 耗时/内存 + 有无摘要」。这恰恰是让模型学会
     * 「只描述数据类别、不编造具体值」的语境来源。
     */
    private void appendCaseSamples(StringBuilder sb, ReviewContext ctx) {
        List<SubmissionReviewContextDTO.CaseSample> samples = ctx.getCaseSamples();
        if (samples == null || samples.isEmpty()) {
            return;
        }
        int limit = Math.min(samples.size(), reviewProperties.getMaxCaseSamples());
        sb.append("【逐用例结果】");
        if (ctx.isCaseDigestMasked()) {
            sb.append("（提示：本题存在隐藏用例，其输出内容对本次点评不可见，仅有结论）");
        }
        sb.append("\n");
        for (int i = 0; i < limit; i++) {
            SubmissionReviewContextDTO.CaseSample c = samples.get(i);
            sb.append("  #").append(c.getSeq()).append(" ").append(nullToEmpty(c.getVerdict()));
            if (Boolean.TRUE.equals(c.getHidden())) {
                sb.append("（隐藏用例）");
            }
            if (c.getTimeMs() != null) {
                sb.append(" ").append(c.getTimeMs()).append("ms");
            }
            if (c.getMemoryKb() != null) {
                sb.append(" ").append(c.getMemoryKb()).append("KB");
            }
            if (c.getOutputDigest() != null && !c.getOutputDigest().isBlank()) {
                sb.append("\n      实际输出摘要：").append(truncate(c.getOutputDigest(), 200));
            }
            if (c.getStderrDigest() != null && !c.getStderrDigest().isBlank()) {
                sb.append("\n      错误输出摘要：").append(truncate(c.getStderrDigest(), 200));
            }
            sb.append("\n");
        }
        if (samples.size() > limit) {
            sb.append("  …（仅列出前 ").append(limit).append(" 条，共 ").append(samples.size()).append(" 条）\n");
        }
        sb.append("\n");
    }

    /** 代码块语言标记：LLM 对正确的高亮标记更敏感，也能避免代码块内出现裸 ``` */
    private String languageFence(String language) {
        if (language == null) {
            return "text";
        }
        return switch (language.toUpperCase()) {
            case "JAVA" -> "java";
            case "PYTHON" -> "python";
            case "CPP" -> "cpp";
            case "GO" -> "go";
            default -> "text";
        };
    }

    private String truncateCode(String code) {
        if (code == null) {
            return "";
        }
        int max = reviewProperties.getMaxCodeLength();
        if (max > 0 && code.length() > max) {
            // 保留头尾：这类超长代码基本都是「大段模板 + 中间实现」，
            // 只截头部会把关键实现段落丢掉，头尾各留一半更实用
            int head = max / 2;
            int tail = max - head;
            return code.substring(0, head)
                    + "\n/* … 中间省略 " + (code.length() - max) + " 字符（代码过长，已截断）… */\n"
                    + code.substring(code.length() - tail);
        }
        return code;
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
