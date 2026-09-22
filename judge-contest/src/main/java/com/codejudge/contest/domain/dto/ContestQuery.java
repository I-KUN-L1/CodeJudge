package com.codejudge.contest.domain.dto;

import com.codejudge.common.domain.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 竞赛列表查询。
 *
 * <p>注意 {@code status} 的语义：竞赛状态是**时间的函数**，库里那列只是快照，
 * 直接用它过滤会漏掉「刚开始但还没被扫描写回」的竞赛。因此本查询把 status 翻译成
 * 时间区间（见 ContestService），保证过滤结果与详情页显示的状态一致。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ContestQuery extends PageQuery {

    /** 标题模糊匹配 */
    private String keyword;

    /** 0未开始 1进行中 2已结束；null=全部 */
    private Integer status;

    /** true=仅看我报名过的 */
    private Boolean registeredOnly;
}
