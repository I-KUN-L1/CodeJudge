package com.codejudge.problem.domain.po;

import com.baomidou.mybatisplus.annotation.TableName;
import com.codejudge.common.domain.BasePO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 题目
 *
 * <p>对应表 {@code problem}。题面正文、模板代码等「可被修改的内容」不放本表，
 * 而是通过 {@link #currentVersionId} 指向 {@link ProblemVersion} —— 改题即新增版本，
 * 保证历史提交在重判时可复现当时的题面（见 sql/init.sql 的表注释）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("problem")
public class Problem extends BasePO {

    /** 题目标题 */
    private String title;

    /** 难度：1~5 */
    private Integer difficulty;

    /** 时间限制(ms)，用例级 test_case.time_limit_ms 可覆盖 */
    private Integer timeLimitMs;

    /** 内存限制(MB) */
    private Integer memoryLimitMb;

    /** 状态：0-草稿 1-已发布 2-已下线 */
    private Integer status;

    /** 创建教师 id（归属校验用；OwnerAccessGuard 以此判断是否本人题目） */
    private Long ownerId;

    /** 当前题面版本 id（指向 problem_version.id） */
    private Long currentVersionId;

    /** 提交次数（冗余计数，供列表排序与统计） */
    private Integer submitCount;

    /** 通过次数（冗余计数，用于计算通过率） */
    private Integer acceptedCount;
}
