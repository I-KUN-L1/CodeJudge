package com.codejudge.contest.domain.po;

import com.baomidou.mybatisplus.annotation.TableName;
import com.codejudge.common.domain.BasePO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 竞赛题目（contest ↔ problem 的关联，同时承载榜面上的题号与满分）
 *
 * <p>{@code label} 是竞赛独有的「A/B/C」题号 —— 同一道题在不同竞赛里可以是不同题号，
 * 且竞赛期间对外只应该展示题号（避免选手按原题库标题搜索题解）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("contest_problem")
public class ContestProblem extends BasePO {

    /** 竞赛 id */
    private Long contestId;

    /** 题目 id */
    private Long problemId;

    /** 题号展示：A/B/C… */
    private String label;

    /** 展示顺序 */
    private Integer displayOrder;

    /** 满分（IOI 赛制使用） */
    private Integer fullScore;

    /** 竞赛内提交次数（冗余计数） */
    private Integer submitCount;

    /** 竞赛内通过次数（冗余计数） */
    private Integer acceptedCount;
}
