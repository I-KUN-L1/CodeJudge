package com.codejudge.problem.domain.po;

import com.baomidou.mybatisplus.annotation.TableName;
import com.codejudge.common.domain.BasePO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 题目-标签关联
 *
 * <p>对应表 {@code problem_tag}，{@code (problem_id, tag_id)} 上有唯一键，
 * 数据库层直接杜绝同一题重复打同一标签。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("problem_tag")
public class ProblemTag extends BasePO {

    /** 题目 id */
    private Long problemId;

    /** 标签 id */
    private Long tagId;
}
