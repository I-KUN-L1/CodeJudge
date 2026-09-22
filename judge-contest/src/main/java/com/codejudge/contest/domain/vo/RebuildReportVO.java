package com.codejudge.contest.domain.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 终榜重建报告（运维/排障用）。
 */
@Data
public class RebuildReportVO implements Serializable {

    private Long contestId;

    /** 从 judge-submission 拉到的终态提交数 */
    private Integer fetched;

    /** 实际回放（计入榜单）的提交数 */
    private Integer replayed;

    /** 导致榜单发生变化的次数（应远小于 replayed：只有首次 AC / 提高最高分才变） */
    private Integer changed;

    /** 重建后的参赛人数（ZSet 基数） */
    private Integer participants;

    /** 是否重新生成了封榜快照 */
    private Boolean refrozen;

    /** 重建耗时(ms) */
    private Long tookMs;

    /** 截断提醒：拉到的提交数达到上限时为 true，此时榜单可能不完整 */
    private Boolean truncated;
}
