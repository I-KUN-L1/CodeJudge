package com.codejudge.auth.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 能力码（按钮级权限）条目。
 *
 * <p>下发它是为了让「有哪些能力」这件事在前端也只是**数据**：
 * 管理面要展示权限清单、排查「按钮没出现」时可直接看接口原始输出，
 * 都不需要再去读一遍代码里的常量表。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CapabilityVO {

    /** 能力码，形如 {@code problem:create} */
    private String code;

    /** 展示名，形如「新建题目」 */
    private String name;
}
