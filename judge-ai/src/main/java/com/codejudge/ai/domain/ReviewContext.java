package com.codejudge.ai.domain;

import com.codejudge.api.dto.submission.SubmissionReviewContextDTO;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 点评上下文聚合：一次点评所需的**全部输入**。
 *
 * <p>为什么不直接把各来源散着传进 Prompt 构造器：
 * <ul>
 *   <li>Prompt 构造需要「题目 + 判题 + 代码 + RAG」四类信息共同决策（例如
 *       CE 时编译日志才是重点，AC 时优化建议才是重点），散着传会让构造器签名膨胀到 10+ 参数；</li>
 *   <li>这个对象天然是**可缓存的单元**：同一提交的首次点评做一遍昂贵的
 *       「Feign + 向量检索」，追问轮次可以直接复用（见 {@code ReviewService} 的会话记忆）。</li>
 * </ul>
 */
@Data
public class ReviewContext {

    // ---------- 来自 judge-submission 的内部契约 ----------
    private Long submissionId;
    private Long userId;
    private Long problemId;
    private Long contestId;
    private String language;
    private String code;
    private String status;
    private String verdict;
    private Integer score;
    private Integer timeMs;
    private Integer memoryKb;
    private Integer caseTotal;
    private Integer caseAcCount;
    private List<SubmissionReviewContextDTO.CaseSample> caseSamples = new ArrayList<>();
    private SubmissionReviewContextDTO.CompileInfoBrief compileInfo;
    /** 逐用例结果因题目服务不可用而被 fail-closed 遮蔽（供 UI 说明「诊断信息不完整」） */
    private boolean caseDigestMasked;

    // ---------- 来自 judge-problem 的内部契约 ----------
    private String problemTitle;
    private Integer difficulty;

    // ---------- 本次点评请求 ----------
    private ReviewType reviewType = ReviewType.ERROR_DIAGNOSIS;
    /** 多轮追问（首轮为空） */
    private String question;

    // ---------- RAG 召回 ----------
    /** 题目知识命中（权威内容，优先） */
    private List<ChunkHit> knowledge = new ArrayList<>();
    /** 历史点评命中（模型旧输出，仅作参考） */
    private List<ChunkHit> history = new ArrayList<>();

    /** 全部召回片段（下发前端做来源展示时使用） */
    public List<ChunkHit> allSources() {
        List<ChunkHit> all = new ArrayList<>(knowledge);
        all.addAll(history);
        return all;
    }

    /** 判题结论是否属于「通过」 */
    public boolean accepted() {
        return "AC".equals(verdict);
    }
}
