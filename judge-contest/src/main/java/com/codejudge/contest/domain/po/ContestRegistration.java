package com.codejudge.contest.domain.po;

import com.baomidou.mybatisplus.annotation.TableName;
import com.codejudge.common.domain.BasePO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 竞赛报名
 *
 * <p>唯一键 {@code uk_contest_registration(contest_id, user_id)} 保证同一用户对同一竞赛
 * 只有一条记录；取消报名是「改状态」而非删除行，这样重新报名不会撞唯一键，
 * 也让「谁曾经报名过」可审计。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("contest_registration")
public class ContestRegistration extends BasePO {

    /** 已取消 */
    public static final int ST_CANCELLED = 0;
    /** 已报名 */
    public static final int ST_REGISTERED = 1;

    /** 竞赛 id */
    private Long contestId;

    /** 用户 id */
    private Long userId;

    /** 报名时间 */
    private LocalDateTime registerTime;

    /** 状态：0取消 / 1已报名 */
    private Integer status;
}
