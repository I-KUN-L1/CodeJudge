package com.codejudge.contest.domain.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.util.List;

/**
 * 竞赛详情（概要 + 题目编排）。
 *
 * <p>只提供题号（label）与满分，**不返回题目标题**：题目标题、难度、题面属于
 * judge-problem 的数据，榜单页展示「A/B/C」本就是竞技惯例（也顺带避免选手在赛中用
 * 题库标题检索题解）。需要标题时前端拿 problemId 去 /problems/{id} 取。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ContestDetailVO extends ContestVO {

    private List<ContestProblemVO> problems;

    /** 题目条目 */
    @Data
    public static class ContestProblemVO implements Serializable {
        private Long problemId;
        private String label;
        private Integer displayOrder;
        private Integer fullScore;
        /** 题目标题（建赛时经内部 Feign 校验题目存在性时顺带取回；查不到时为 null） */
        private String title;
        /** 竞赛内提交次数 */
        private Integer submitCount;
        /** 竞赛内通过次数 */
        private Integer acceptedCount;
    }
}
