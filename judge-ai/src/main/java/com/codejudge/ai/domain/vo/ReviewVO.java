package com.codejudge.ai.domain.vo;

import com.codejudge.ai.domain.AiReview;
import com.codejudge.ai.domain.ReviewType;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 点评记录对外视图（非流式接口与历史查询的返回体）。
 *
 * <p>不含 embedding（向量对客户端毫无意义，且体积是正文的数百倍）。
 */
@Data
public class ReviewVO {

    private Long id;
    private Long submissionId;
    private Long problemId;
    private String language;

    /** 点评类型码（1错误诊断 2主动点评 3相似题推荐） */
    private Integer reviewType;
    /** 点评类型中文名，省去前端再维护一份映射表 */
    private String reviewTypeLabel;

    /** 触发时的判题结论 */
    private String verdict;

    private String model;

    /** 点评正文（Markdown） */
    private String content;

    private Integer tokensIn;
    private Integer tokensOut;

    /** 0=生成中 1=完成 2=失败 */
    private Integer status;
    private String errorMsg;

    /** 是否降级结果（LLM 未配置时由模板生成） */
    private Boolean degraded;

    private OffsetDateTime createTime;
    private OffsetDateTime updateTime;

    public static ReviewVO of(AiReview r, String language) {
        if (r == null) {
            return null;
        }
        ReviewVO vo = new ReviewVO();
        vo.setId(r.getId());
        vo.setSubmissionId(r.getSubmissionId());
        vo.setProblemId(r.getProblemId());
        vo.setLanguage(language);
        vo.setReviewType(r.getReviewType());
        ReviewType t = ReviewType.of(r.getReviewType());
        vo.setReviewTypeLabel(t.getLabel());
        vo.setVerdict(r.getVerdict());
        vo.setModel(r.getModel());
        vo.setContent(r.getContent());
        vo.setTokensIn(r.getTokensIn());
        vo.setTokensOut(r.getTokensOut());
        vo.setStatus(r.getStatus());
        vo.setErrorMsg(r.getErrorMsg());
        vo.setDegraded(r.getModel() != null && r.getModel().startsWith("builtin"));
        vo.setCreateTime(r.getCreateTime());
        vo.setUpdateTime(r.getUpdateTime());
        return vo;
    }
}
