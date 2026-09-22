package com.codejudge.contest.domain.po;

import com.baomidou.mybatisplus.annotation.TableName;
import com.codejudge.common.domain.BasePO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 榜单快照（封榜 / 终榜留档）
 *
 * <p><b>为什么要有落库快照</b>：Redis 榜单是「有 TTL 的缓存」，而封榜时刻的榜单是
 * 具有历史意义的事实（赛后申诉、奖项核定都以它为准）。一旦 Redis 被清空/过期，
 * 只靠 live ZSet 无法重放「当时冻结的是什么」。因此封榜与终榜各写一条 DB 记录。
 *
 * <p>表上只有普通索引（非唯一）：允许同一竞赛存在多条同类快照（例如管理员重建后重新封榜），
 * 取最新一条即可。幂等由 Redis 锁 + 「先查是否存在」共同保证。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("contest_rank_snapshot")
public class ContestRankSnapshot extends BasePO {

    /** 封榜快照 */
    public static final String TYPE_FROZEN = "FROZEN";
    /** 终榜快照 */
    public static final String TYPE_FINAL = "FINAL";

    /** 竞赛 id */
    private Long contestId;

    /** 类型：FROZEN / FINAL */
    private String snapshotType;

    /** 快照时刻 */
    private LocalDateTime snapshotAt;

    /** 榜单 JSON（含排名、通过题数、罚时、各题状态） */
    private String rankJson;
}
