package com.codejudge.contest.domain.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.Map;

/**
 * 榜单行。
 *
 * <p>字段刻意同时给「原始量」与「展示量」：
 * <ul>
 *   <li>{@code rank} 已按同分规则排好，前端不必自己排序（各端排序规则不一致是排行榜类需求的高发分歧点）；</li>
 *   <li>{@code weight} 是排序主关键字（ACM=通过题数 / IOI=总分），
 *       {@code penaltySeconds} 是次关键字 —— 二者同时下发，前端才能解释「为什么他排前面」。</li>
 * </ul>
 */
@Data
public class RankEntryVO implements Serializable {

    /** 名次，从 1 开始 */
    private Integer rank;

    private Long userId;

    /** 用户名（judge-user 批量反查；查不到时回落为 userId 字符串） */
    private String userName;

    /** 排序主关键字：ACM=通过题数 / IOI=总分 */
    private Integer weight;

    /** ACM：通过题数；IOI：0（用 weight 表示总分） */
    private Integer solvedCount;

    /** IOI：总分；ACM：0（用 weight 表示通过题数） */
    private Integer totalScore;

    /** 罚时（秒）。ACM=错误提交罚时 + AC 耗时；IOI=首次取得当前总分的时间偏移 */
    private Integer penaltySeconds;

    /** 最后通过时间（秒偏移），无通过为 null */
    private Integer lastAcceptedOffsetSeconds;

    /**
     * 各题状态（ICPC 记法）：{@code +}=通过，{@code +2}=2 次错误后通过，
     * {@code -3}=3 次错误未通过，{@code ""}=未提交。key=题号 label。
     */
    private Map<String, String> problemStatus;
}
