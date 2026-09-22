package com.codejudge.contest.domain.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 竞赛列表 / 概要视图。
 */
@Data
public class ContestVO implements Serializable {

    private Long id;
    private String title;
    private String description;
    private String rule;

    private LocalDateTime startTime;
    private LocalDateTime endTime;

    /** 封榜时刻（无封榜为 null） */
    private LocalDateTime freezeAt;
    private Integer freezeMinutes;

    private Integer penaltyMinutes;

    /** **实时推导**的状态：0未开始 1进行中 2已结束（不读库里的快照列） */
    private Integer status;

    /** 当前是否处于封榜中 */
    private Boolean frozen;

    /** 创建人 */
    private Long ownerId;

    /** 题目数量 */
    private Integer problemCount;

    /** 报名人数 */
    private Long registerCount;

    /** 当前登录用户是否已报名（未登录/未传时为 null） */
    private Boolean myRegistered;
}
