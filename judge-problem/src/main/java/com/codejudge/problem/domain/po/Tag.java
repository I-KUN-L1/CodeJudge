package com.codejudge.problem.domain.po;

import com.baomidou.mybatisplus.annotation.TableName;
import com.codejudge.common.domain.BasePO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 题目标签
 *
 * <p>对应表 {@code tag}，{@code name} 上有唯一键。标签是全局共享字典，
 * 与题目的多对多关系落在 {@link ProblemTag}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("tag")
public class Tag extends BasePO {

    /** 标签名 */
    private String name;

    /** 标签类型：ALGORITHM-算法 / SOURCE-来源 / DIFFICULTY_TAG-难度 */
    private String type;
}
