package com.codejudge.contest.domain.po;

import com.baomidou.mybatisplus.annotation.TableName;
import com.codejudge.common.domain.BasePO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 竞赛
 *
 * <p>对应表 {@code contest}。生命周期由 {@code start_time} / {@code end_time} /
 * {@code freeze_at} 三个时间点唯一确定，{@code status} 列只是「已落库的快照」——
 * 判断某个时刻的真实状态请用 {@link #effectiveStatus(LocalDateTime)}，
 * 这样即便扫描任务尚未把状态写回，也不会把刚开始的竞赛当成未开始。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("contest")
public class Contest extends BasePO {

    /** 未开始 */
    public static final int ST_NOT_STARTED = 0;
    /** 进行中 */
    public static final int ST_RUNNING = 1;
    /** 已结束 */
    public static final int ST_FINISHED = 2;

    /** 赛制：ACM（罚时）/ IOI（按分） */
    public static final String RULE_ACM = "ACM";
    public static final String RULE_IOI = "IOI";

    /** 竞赛标题 */
    private String title;

    /** 竞赛说明 */
    private String description;

    /** 赛制：ACM / IOI */
    private String rule;

    /** 开始时间 */
    private LocalDateTime startTime;

    /** 结束时间 */
    private LocalDateTime endTime;

    /** 封榜时长(分钟)，0=不封榜（封榜时刻 = 结束时间 - 封榜时长） */
    private Integer freezeMinutes;

    /** 封榜时刻；freeze_minutes=0 时为 null */
    private LocalDateTime freezeAt;

    /** ACM 每次错误提交的罚时(分钟)，默认 20（ICPC 惯例） */
    private Integer penaltyMinutes;

    /** 状态快照：0未开始 1进行中 2已结束 */
    private Integer status;

    /** 创建人 id（归属校验用） */
    private Long ownerId;

    /**
     * 按给定时间推导真实状态（不读 status 列）。
     *
     * <p>边界语义：[start, end) 为进行中，恰好等于 end 即已结束（与快照扫描一致，
     * 避免「结束时刻的提交」在两处得到不同结论）。
     */
    public int effectiveStatus(LocalDateTime now) {
        if (now.isBefore(startTime)) {
            return ST_NOT_STARTED;
        }
        return now.isBefore(endTime) ? ST_RUNNING : ST_FINISHED;
    }

    /** 封榜是否已生效（未配置封榜则恒为 false） */
    public boolean frozenAt(LocalDateTime now) {
        return freezeAt != null && !now.isBefore(freezeAt) && now.isBefore(endTime);
    }

    public boolean isAcm() {
        return !RULE_IOI.equalsIgnoreCase(rule);
    }

    public int penaltyMinutesOrDefault() {
        return penaltyMinutes == null ? 20 : penaltyMinutes;
    }
}
