package com.codejudge.problem.domain.vo;

import lombok.Data;

/**
 * 标签视图
 */
@Data
public class TagVO {

    private Long id;

    /** 标签名 */
    private String name;

    /** 标签类型：ALGORITHM / SOURCE / DIFFICULTY_TAG */
    private String type;

    public static TagVO of(Long id, String name, String type) {
        TagVO vo = new TagVO();
        vo.setId(id);
        vo.setName(name);
        vo.setType(type);
        return vo;
    }
}
