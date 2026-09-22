package com.codejudge.problem.domain.dto;

import lombok.Data;

/**
 * 标签表单
 */
@Data
public class TagFormDTO {

    /** 标签名（全局唯一） */
    private String name;

    /** 标签类型：ALGORITHM / SOURCE / DIFFICULTY_TAG，缺省 ALGORITHM */
    private String type;
}
