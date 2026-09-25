package com.codejudge.auth.domain.vo;

import lombok.Data;

import java.util.List;

/**
 * 当前账号的能力画像 —— 前端渲染按钮/入口所需的**全部**信息，一次取回。
 *
 * <p>设计要点：这份对象是前端鉴权信息的唯一来源。前端拿到后
 * <b>只做两件不需要判断力的事</b>：
 * <ol>
 *   <li>把 {@link #perms} 展平成 Set，供 {@code v-perm} 指令与 {@code can()} 查表；</li>
 *   <li>把 {@link #menus} 按 {@link MenuItemVO#getGroup()} 分栏渲染。</li>
 * </ol>
 * 没有任何「角色 → 权限」的推导留在前端，因此不存在前后端权限口径不一致的可能。
 */
@Data
public class CapabilityProfileVO {

    /** 用户类型（{@code user.type}：1 员工 / 2 学员 / 3 教师） */
    private Integer role;

    /** 角色别名（admin / student / teacher），与 {@code UserRole.alias} 一致 */
    private String roleAlias;

    /** 角色中文名（管理员 / 学员 / 教师） */
    private String roleLabel;

    /**
     * 登录后的默认落地路由。
     * <p>由后端按角色给出，前端 {@code router.replace(redirect || home)} 直接用 ——
     * 「进屋先看哪儿」也是权限语义的一部分，不该由前端写死。
     */
    private String home;

    /** 可见的导航入口（已按能力过滤，前端无需再筛） */
    private List<MenuItemVO> menus;

    /** 已获授权的能力码（含展示名），前端据此决定按钮是否渲染 */
    private List<CapabilityVO> perms;
}
