package com.codejudge.auth.service;

import com.codejudge.auth.domain.Capabilities;
import com.codejudge.auth.domain.vo.CapabilityProfileVO;
import com.codejudge.auth.domain.vo.CapabilityVO;
import com.codejudge.auth.domain.vo.MenuItemVO;
import com.codejudge.common.constants.UserRole;
import com.codejudge.common.exceptions.UnauthorizedException;
import com.codejudge.common.utils.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 能力画像服务：把「当前登录者是谁」翻译成「他能看到/点到什么」。
 *
 * <h3>导航项为什么定义在代码里而不是查 {@code menu} 表</h3>
 * {@code menu} 表是 RBAC 管理面的一部分，当前<b>没有任何种子数据</b>，
 * 且它与前端路由表必须逐项对齐（path 写错就是死链）。若走 DB，则每次改一个菜单
 * 都要同时改 SQL 种子、跑迁移、并确保角色绑定不漏项 —— 而收益只是「可在后台配菜单」，
 * 这个诉求目前不存在。故这里用代码常量表达，与前端 {@code router/index.js} 的路由表一一对应；
 * 等真的需要动态菜单时，把 {@link #MENUS} 换成 mapper 查询即可，接口契约不变。
 *
 * <p>但<b>可见性判定不在这里写死</b>：每个菜单项自带所需能力码，统一过
 * {@link Capabilities} —— 这样「菜单可见」与「接口可调」用的是同一套码，不会分叉。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CapabilityService {

    /**
     * 全量导航项定义。
     * <p>{@code perm} 是打开该入口所需的能力码，不是角色 —— 新增角色时
     * 只改 {@link Capabilities} 的角色映射，此处一行都不用动。
     */
    private static final List<MenuItemVO> MENUS = List.of(
            menu("problems", "题库", "/problems", "Notebook", "primary", Capabilities.PROBLEM_VIEW),
            menu("contests", "竞赛", "/contests", "Trophy", "primary", Capabilities.CONTEST_VIEW),
            menu("submissions", "提交记录", "/submissions", "Tickets", "primary", Capabilities.SUBMISSION_VIEW_OWN),

            menu("problem-manage", "题目管理", "/teacher/problems", "EditPen", "teach", Capabilities.PROBLEM_MANAGE),
            menu("contest-create", "创建竞赛", "/teacher/contests/new", "CirclePlus", "teach", Capabilities.CONTEST_CREATE),
            menu("knowledge", "AI 知识库", "/teacher/knowledge", "Collection", "teach", Capabilities.KNOWLEDGE_MANAGE),

            menu("user-manage", "用户管理", "/admin/users", "User", "system", Capabilities.USER_MANAGE),
            menu("tag-manage", "标签管理", "/admin/tags", "PriceTag", "system", Capabilities.TAG_MANAGE),
            menu("worker-cluster", "判题集群", "/admin/workers", "Cpu", "system", Capabilities.WORKER_VIEW),
            menu("monitor", "系统监控", "/admin/monitor", "Odometer", "system", Capabilities.MONITOR_VIEW));

    /**
     * 各角色的登录落地路由。
     * <p>「进屋先看哪儿」属于权限语义（学员落在题库、教师落在题目管理、管理员落在用户管理），
     * 故由后端下发。若要改回「所有人都进 /problems」，只改这张表。
     */
    private static final String HOME_STUDENT = "/problems";
    private static final String HOME_TEACHER = "/teacher/problems";
    private static final String HOME_STAFF = "/admin/users";

    /**
     * 组装当前登录者的能力画像。
     *
     * @throws UnauthorizedException 未登录（缺 {@code user-info} 头）
     */
    public CapabilityProfileVO currentProfile() {
        Long userId = UserContext.getUserId();
        if (userId == null || userId == 0L) {
            throw new UnauthorizedException("未登录");
        }
        Integer roleCode = UserContext.getRole();
        UserRole role = UserRole.of(roleCode);
        if (role == null) {
            // 已登录但 role 缺失：网关一定透传了 role-info，走到这里说明是直连服务且未带该头。
            // fail-closed —— 不猜角色、不给能力，明确报未登录，避免"降级成某个角色"这种隐式授权。
            log.warn("能力画像请求缺少角色信息：userId={}, role-info={}", userId, roleCode);
            throw new UnauthorizedException("未登录");
        }

        Set<String> granted = Capabilities.of(roleCode);

        CapabilityProfileVO vo = new CapabilityProfileVO();
        vo.setRole(roleCode);
        vo.setRoleAlias(role.getAlias());
        vo.setRoleLabel(role.getLabel());
        vo.setHome(homeOf(role));
        vo.setMenus(visibleMenus(granted));
        vo.setPerms(grantedPerms(granted));
        return vo;
    }

    /** 按当前能力码过滤导航项，保持 {@link #MENUS} 的声明顺序 */
    private List<MenuItemVO> visibleMenus(Set<String> granted) {
        List<MenuItemVO> visible = new ArrayList<>();
        for (MenuItemVO item : MENUS) {
            if (granted.contains(item.getPerm())) {
                visible.add(item);
            }
        }
        return visible;
    }

    /**
     * 输出已授权能力码。<b>按 {@link Capabilities#catalogue()} 的声明顺序</b>而非 Set 迭代顺序，
     * 保证同一账号每次拿到的列表完全一致（前端做 diff、测试做断言都需要这个稳定性）。
     */
    private List<CapabilityVO> grantedPerms(Set<String> granted) {
        List<CapabilityVO> list = new ArrayList<>();
        Capabilities.catalogue().forEach((code, name) -> {
            if (granted.contains(code)) {
                list.add(new CapabilityVO(code, name));
            }
        });
        return list;
    }

    private String homeOf(UserRole role) {
        return switch (role) {
            case STAFF -> HOME_STAFF;
            case TEACHER -> HOME_TEACHER;
            case STUDENT -> HOME_STUDENT;
        };
    }

    private static MenuItemVO menu(String key, String name, String path,
                                   String icon, String group, String perm) {
        MenuItemVO item = new MenuItemVO();
        item.setKey(key);
        item.setName(name);
        item.setPath(path);
        item.setIcon(icon);
        item.setGroup(group);
        item.setPerm(perm);
        return item;
    }
}
