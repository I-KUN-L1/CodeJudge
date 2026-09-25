package com.codejudge.auth.domain.vo;

import lombok.Data;

/**
 * 导航项。
 *
 * <p>菜单/入口的可见性同样由后端决定：每一项自带一个 {@link #perm} 能力码，
 * 前端只负责「有就画、没有就不画」。前端不再出现
 * {@code if (role === 1) ... } 这类推导，也就不会再与后端口径漂移。
 */
@Data
public class MenuItemVO {

    /** 稳定标识（前端 :key 与测试断言用），形如 {@code problem-manage} */
    private String key;

    /** 展示名 */
    private String name;

    /** 路由地址（前端 router 的 path） */
    private String path;

    /** Element Plus 图标组件名（前端已全局注册，可直接 <component :is="icon" />） */
    private String icon;

    /**
     * 分组：{@code primary} 顶栏直接展示 / {@code teach} 教学下拉 / {@code system} 系统下拉。
     * <p>分组只决定「画在哪一栏」，不承担权限语义 —— 权限一律看 {@link #perm}。
     */
    private String group;

    /** 打开该入口所需的能力码 */
    private String perm;
}
