package com.codejudge.ai.domain;

import lombok.Getter;

/**
 * 点评类型（与 {@code ai_review.review_type} 的取值对齐）。
 *
 * <p>用枚举而非散落魔数，是为了让「Prompt 侧重」与「落库取值」同源：
 * 新增类型时只要补一个枚举项 + 一处 Prompt 侧重点，编译期即可发现遗漏。
 */
@Getter
public enum ReviewType {

    /** 错误诊断：紧扣判题结论，解释为什么错（默认类型，也是 MQ 预生成采用的类型） */
    ERROR_DIAGNOSIS(1, "错误诊断"),

    /** 主动点评：不看结论，全面评审代码质量与优化空间 */
    CODE_REVIEW(2, "主动点评"),

    /** 相似题推荐：从错因出发推荐可迁移的知识点与练题方向 */
    SIMILAR_RECOMMEND(3, "相似题推荐");

    private final int code;
    private final String label;

    ReviewType(int code, String label) {
        this.code = code;
        this.label = label;
    }

    /** 容错解析：非法/缺省值一律落回 {@link #ERROR_DIAGNOSIS}（默认类型必须确定，不能抛异常中断流） */
    public static ReviewType of(Integer code) {
        if (code == null) {
            return ERROR_DIAGNOSIS;
        }
        for (ReviewType t : values()) {
            if (t.code == code) {
                return t;
            }
        }
        return ERROR_DIAGNOSIS;
    }
}
