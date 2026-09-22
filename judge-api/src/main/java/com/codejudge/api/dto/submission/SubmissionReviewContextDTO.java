package com.codejudge.api.dto.submission;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 点评所需的提交上下文（judge-submission 内部端点
 * {@code /internal/submissions/{id}/review-context} 返回）。
 *
 * <p>消费方：judge-ai。点评的输入侧完全依赖本对象 —— 没有代码与判题结论，
 * 「代码点评」就退化成泛泛而谈的代码风格建议。
 *
 * <p><b>安全设计（关键）</b>：{@link CaseSample#getOutputDigest()} 对隐藏用例
 * <b>按请求方角色</b>决定是否下发。理由：AI 点评面向学员开放，
 * 而隐藏用例的期望输出正是「防止用摘要做逐字节猜测攻击（oracle attack）」的对象。
 * 若不加区分地把隐藏用例摘要灌进 Prompt，学员只需问 AI
 * 「第 3 个用例期望输出是什么」即可绕过 P3 建立的隔离 —— 一条典型的
 * 「通过新增功能侧信道绕开既有安全控制」的路径。
 * 因此该端点接收 {@code maskHidden} 参数，由 judge-ai 依据调用方角色（教师/员工 vs 学员）传入。
 */
@Data
public class SubmissionReviewContextDTO implements Serializable {

    private Long submissionId;

    /** 提交人 id（judge-ai 据此校验「学员只能点评自己的提交」） */
    private Long userId;

    private Long problemId;

    /** 竞赛 id，0=非竞赛提交 */
    private Long contestId;

    /** 语言：JAVA/PYTHON/CPP/GO */
    private String language;

    /** 代码全文 */
    private String code;

    /** 状态：PENDING/JUDGING/SUCCESS/FAILED */
    private String status;

    /** 判题结论：AC/WA/TLE/MLE/RE/CE/SE */
    private String verdict;

    private Integer score;

    private Integer timeMs;

    private Integer memoryKb;

    private LocalDateTime submitTime;

    /** 用例总数 */
    private Integer caseTotal;

    /** 通过用例数 */
    private Integer caseAcCount;

    /** 逐用例样本（最多若干条，含隐藏用例的结论但遮蔽其输出摘要） */
    private List<CaseSample> caseSamples;

    /** 编译信息（CE 时是点评的核心依据） */
    private CompileInfoBrief compileInfo;

    /**
     * 逐用例结果样本。
     *
     * <p>注意 {@code verdict} 与 {@code outputDigest} 的可见性策略不同：
     * 用例结论（AC/WA/TLE…）对学员本就可见（{@code SubmissionDetailVO} 亦如此），
     * 只有**输出摘要**才需要按隐藏用例遮蔽。
     */
    @Data
    public static class CaseSample implements Serializable {
        /** 用例执行序号 */
        private Integer seq;
        /** 该用例结论 */
        private String verdict;
        private Integer timeMs;
        private Integer memoryKb;
        /** 实际输出摘要（隐藏用例且 maskHidden 时为 null） */
        private String outputDigest;
        /** 错误输出摘要（隐藏用例且 maskHidden 时为 null） */
        private String stderrDigest;
        /** 是否为隐藏用例 */
        private Boolean hidden;
    }

    /** 编译信息摘要 */
    @Data
    public static class CompileInfoBrief implements Serializable {
        private Boolean success;
        private String stdoutLog;
        private String stderrLog;
        private Integer durationMs;
    }
}
