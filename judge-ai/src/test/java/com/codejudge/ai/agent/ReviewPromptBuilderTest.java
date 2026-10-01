package com.codejudge.ai.agent;

import com.codejudge.ai.config.ReviewProperties;
import com.codejudge.ai.domain.ChunkHit;
import com.codejudge.ai.domain.ReviewContext;
import com.codejudge.ai.domain.ReviewType;
import com.codejudge.api.dto.submission.SubmissionReviewContextDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ReviewPromptBuilder 单测（Prompt 契约：结构、硬约束开关、RAG 分区、截断）。
 *
 * <p>运行：mvn -pl judge-ai -am test
 *
 * <p>前端按固定 Markdown 锚点解析点评正文，Prompt 的输出结构就是契约的一部分 ——
 * 这些断言挂了，解析就会挂，所以逐条固化。
 */
class ReviewPromptBuilderTest {

    private ReviewProperties props;
    private ReviewPromptBuilder builder;

    @BeforeEach
    void setUp() {
        props = new ReviewProperties();
        builder = new ReviewPromptBuilder(props);
    }

    private ReviewContext baseContext() {
        ReviewContext ctx = new ReviewContext();
        ctx.setProblemTitle("两数之和");
        ctx.setDifficulty(2);
        ctx.setLanguage("JAVA");
        ctx.setStatus("Judged");
        ctx.setVerdict("WA");
        ctx.setScore(60);
        ctx.setTimeMs(120);
        ctx.setMemoryKb(38400);
        ctx.setCaseTotal(10);
        ctx.setCaseAcCount(6);
        ctx.setCode("public class Main { public static void main(String[] args) {} }");
        return ctx;
    }

    @Nested
    @DisplayName("messages 结构")
    class Structure {

        @Test
        @DisplayName("system 在首位、user 在末位、历史插在中间")
        void messageOrdering() {
            ReviewContext ctx = baseContext();
            List<Map<String, String>> history = List.of(
                    Map.of("role", "user", "content", "上一轮问题"),
                    Map.of("role", "assistant", "content", "上一轮回答"));

            List<Map<String, String>> messages = builder.build(ctx, history);

            assertThat(messages).hasSize(4);
            assertThat(messages.get(0)).containsEntry("role", "system");
            assertThat(messages.get(1)).containsEntry("role", "user").containsEntry("content", "上一轮问题");
            assertThat(messages.get(2)).containsEntry("role", "assistant");
            assertThat(messages.get(3)).containsEntry("role", "user");
        }

        @Test
        @DisplayName("history 为 null（首轮）→ 仅 system + user 两条")
        void nullHistory() {
            List<Map<String, String>> messages = builder.build(baseContext(), null);
            assertThat(messages).hasSize(2);
        }
    }

    @Nested
    @DisplayName("硬约束开关")
    class HardConstraints {

        @Test
        @DisplayName("默认不允许完整题解：prompt 含「不要给出完整可提交的代码」")
        void defaultNoFullSolution() {
            String system = builder.build(baseContext(), null).get(0).get("content");
            assertThat(system).contains("不要给出完整可提交的代码");
        }

        @Test
        @DisplayName("allowFullSolution=true：切换为允许给完整代码")
        void fullSolutionAllowed() {
            props.setAllowFullSolution(true);
            String system = builder.build(baseContext(), null).get(0).get("content");
            assertThat(system).contains("允许给出完整的修改后代码");
            assertThat(system).doesNotContain("不要给出完整可提交的代码");
        }

        @Test
        @DisplayName("禁止编造用例的硬约束恒在")
        void noFabricationAlwaysPresent() {
            String system = builder.build(baseContext(), null).get(0).get("content");
            assertThat(system).contains("严禁编造测试用例数据");
        }
    }

    @Nested
    @DisplayName("输出结构（前端解析锚点）")
    class OutputFormat {

        @Test
        @DisplayName("三种点评类型产出各自固定章节标题")
        void perTypeHeadings() {
            assertThat(systemOfType(ReviewType.ERROR_DIAGNOSIS)).contains("## 错因定位");
            assertThat(systemOfType(ReviewType.CODE_REVIEW)).contains("## 复杂度分析");
            assertThat(systemOfType(ReviewType.SIMILAR_RECOMMEND)).contains("## 推荐练习方向");
        }

        private String systemOfType(ReviewType type) {
            ReviewContext ctx = baseContext();
            ctx.setReviewType(type);
            return builder.build(ctx, null).get(0).get("content");
        }
    }

    @Nested
    @DisplayName("RAG 材料分区")
    class RagContext {

        @Test
        @DisplayName("无召回 → 明确声明材料有限（不许模型自由发挥）")
        void noHits() {
            String system = builder.build(baseContext(), null).get(0).get("content");
            assertThat(system).contains("本次未检索到相关资料");
        }

        @Test
        @DisplayName("知识命中与历史点评分区呈现且标注来源")
        void partitionedHits() {
            ReviewContext ctx = baseContext();
            ctx.setKnowledge(List.of(new ChunkHit(1L, 4001L, "STATEMENT", "题面要点", "单调性内容", 0.91)));
            ctx.setHistory(List.of(new ChunkHit(2L, 4001L, "ERROR_PATTERN", "旧点评", "上次WA分析", 0.72)));

            String system = builder.build(ctx, null).get(0).get("content");

            assertThat(system).contains("题目知识库");
            assertThat(system).contains("历史点评");
            assertThat(system).contains("题面要点");
            assertThat(system).contains("[H1]");
        }
    }

    @Nested
    @DisplayName("user prompt 内容")
    class UserPrompt {

        @Test
        @DisplayName("判题结论 / 用例 / 代码围栏按字段注入；代码围栏按语言标注")
        void fieldInjection() {
            String user = builder.build(baseContext(), null).get(1).get("content");
            assertThat(user).contains("两数之和");
            assertThat(user).contains("WA");
            assertThat(user).contains("6 / 10");
            assertThat(user).contains("```java");
        }

        @Test
        @DisplayName("未知语言 → text 围栏（避免裸 ```）")
        void unknownLanguageFence() {
            ReviewContext ctx = baseContext();
            ctx.setLanguage("RUST");
            assertThat(builder.build(ctx, null).get(1).get("content")).contains("```text");
        }

        @Test
        @DisplayName("CE 时编译日志单独成节")
        void compileInfoSection() {
            ReviewContext ctx = baseContext();
            ctx.setVerdict("CE");
            SubmissionReviewContextDTO.CompileInfoBrief ci = new SubmissionReviewContextDTO.CompileInfoBrief();
            ci.setSuccess(false);
            ci.setDurationMs(800);
            ci.setStderrLog("error: ';' expected");
            ctx.setCompileInfo(ci);

            String user = builder.build(ctx, null).get(1).get("content");
            assertThat(user).contains("【编译结果】编译失败");
            assertThat(user).contains("';' expected");
        }

        @Test
        @DisplayName("隐藏用例遮蔽提示随逐用例样本注入")
        void maskedHint() {
            ReviewContext ctx = baseContext();
            ctx.setCaseDigestMasked(true);
            SubmissionReviewContextDTO.CaseSample s = new SubmissionReviewContextDTO.CaseSample();
            s.setSeq(3);
            s.setVerdict("TLE");
            s.setHidden(true);
            s.setTimeMs(2100);
            ctx.setCaseSamples(List.of(s));

            String user = builder.build(ctx, null).get(1).get("content");
            assertThat(user).contains("隐藏用例");
            assertThat(user).contains("#3 TLE");
        }

        @Test
        @DisplayName("逐用例样本超过 maxCaseSamples → 只列前 N 条并声明总量")
        void sampleLimit() {
            props.setMaxCaseSamples(2);
            ReviewContext ctx = baseContext();
            ctx.setCaseSamples(List.of(
                    sample(1, "AC"), sample(2, "WA"), sample(3, "AC")));

            String user = builder.build(ctx, null).get(1).get("content");
            assertThat(user).contains("仅列出前 2 条，共 3 条");
        }

        @Test
        @DisplayName("学员追问追加在末尾")
        void questionAppended() {
            ReviewContext ctx = baseContext();
            ctx.setQuestion("为什么第 7 个用例会超时？");
            String user = builder.build(ctx, null).get(1).get("content");
            assertThat(user).endsWith("【学员追问】\n为什么第 7 个用例会超时？\n");
        }

        @Test
        @DisplayName("超长代码头尾保留截断，不丢中间实现")
        void longCodeTruncation() {
            props.setMaxCodeLength(40);
            String longCode = "A".repeat(30) + "B".repeat(30);
            ReviewContext ctx = baseContext();
            ctx.setCode(longCode);

            String user = builder.build(ctx, null).get(1).get("content");
            assertThat(user).contains("中间省略");
            assertThat(user).contains("AAAA");
            assertThat(user).contains("BBBB");
        }

        private SubmissionReviewContextDTO.CaseSample sample(int seq, String verdict) {
            SubmissionReviewContextDTO.CaseSample s = new SubmissionReviewContextDTO.CaseSample();
            s.setSeq(seq);
            s.setVerdict(verdict);
            return s;
        }
    }
}
