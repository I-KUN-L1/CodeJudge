package com.codejudge.auth.service;

import com.codejudge.auth.domain.Capabilities;
import com.codejudge.auth.domain.vo.CapabilityProfileVO;
import com.codejudge.auth.domain.vo.MenuItemVO;
import com.codejudge.common.constants.UserRole;
import com.codejudge.common.exceptions.UnauthorizedException;
import com.codejudge.common.utils.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 能力画像服务测试。
 *
 * <p>重点验证三件事：
 * <ol>
 *   <li><b>fail-closed</b> —— 未登录、role 缺失、role 是脏值，一律 401，
 *       绝不"降级成某个角色"（隐式授权比报错危险得多）；</li>
 *   <li><b>菜单与能力码同源</b> —— 每一项菜单的 {@code perm} 都必须是
 *       {@link Capabilities} 里已登记的码，否则前端会拿到一个永远不可见的入口；</li>
 *   <li><b>输出稳定</b> —— perms 按目录声明序输出，前端 diff 与测试断言都依赖它。</li>
 * </ol>
 */
class CapabilityServiceTest {

    private static final Long USER_ID = 2001L;

    private final CapabilityService service = new CapabilityService();

    @BeforeEach
    void setUp() {
        UserContext.remove();
    }

    @AfterEach
    void tearDown() {
        // ThreadLocal 必须清：漏清会让后续测试"继承"上一个测试的身份
        UserContext.remove();
    }

    private void loginAs(Integer userType) {
        UserContext.setUser(USER_ID);
        UserContext.setRole(userType);
    }

    private Set<String> codesOf(CapabilityProfileVO vo) {
        return vo.getPerms().stream().map(p -> p.getCode()).collect(Collectors.toSet());
    }

    private List<String> menuKeysOf(CapabilityProfileVO vo) {
        return vo.getMenus().stream().map(MenuItemVO::getKey).collect(Collectors.toList());
    }

    @Test
    void anonymousIsRejected() {
        assertThrows(UnauthorizedException.class, service::currentProfile);
    }

    @Test
    void missingRoleIsRejectedRatherThanDowngraded() {
        // 已登录（有 userId）但网关没透传 role-info：直连服务的场景。
        // 必须 401 —— 若"没角色就当学员"，就等于把未授权请求静默降级成学员权限。
        UserContext.setUser(USER_ID);
        UnauthorizedException e = assertThrows(UnauthorizedException.class, service::currentProfile);
        assertEquals("未登录", e.getMessage());
    }

    @Test
    void dirtyRoleIsRejected() {
        loginAs(99);
        assertThrows(UnauthorizedException.class, service::currentProfile);
    }

    @Test
    void zeroUserIdIsRejected() {
        // 网关未注入 user-info 时拦截器会写入 0，不能被当成合法用户
        UserContext.setUser(0L);
        UserContext.setRole(UserRole.STUDENT_CODE);
        assertThrows(UnauthorizedException.class, service::currentProfile);
    }

    @Test
    void studentProfile() {
        loginAs(UserRole.STUDENT_CODE);
        CapabilityProfileVO vo = service.currentProfile();

        assertEquals(UserRole.STUDENT_CODE, vo.getRole());
        assertEquals("student", vo.getRoleAlias());
        assertEquals("学员", vo.getRoleLabel());
        assertEquals("/problems", vo.getHome());
        assertEquals(List.of("problems", "contests", "submissions"), menuKeysOf(vo));
        assertEquals(Capabilities.of(UserRole.STUDENT_CODE), codesOf(vo));
        assertTrue(menuKeysOf(vo).stream().noneMatch(k -> k.startsWith("problem-manage")));
        assertTrue(vo.getMenus().stream().noneMatch(m -> "system".equals(m.getGroup())),
                "学员不应看到任何系统分组菜单");
    }

    @Test
    void teacherProfile() {
        loginAs(UserRole.TEACHER_CODE);
        CapabilityProfileVO vo = service.currentProfile();

        assertEquals("teacher", vo.getRoleAlias());
        assertEquals("教师", vo.getRoleLabel());
        assertEquals("/teacher/problems", vo.getHome());
        assertEquals(List.of("problems", "contests", "submissions",
                        "problem-manage", "contest-create", "knowledge"),
                menuKeysOf(vo));
        assertTrue(vo.getMenus().stream().anyMatch(m -> "teach".equals(m.getGroup())));
        assertTrue(vo.getMenus().stream().noneMatch(m -> "system".equals(m.getGroup())),
                "教师不应看到系统分组菜单（用户/标签/集群/监控）");
    }

    @Test
    void staffProfileSeesEverything() {
        loginAs(UserRole.STAFF_CODE);
        CapabilityProfileVO vo = service.currentProfile();

        assertEquals("admin", vo.getRoleAlias());
        assertEquals("管理员", vo.getRoleLabel());
        assertEquals("/admin/users", vo.getHome());
        assertEquals(10, vo.getMenus().size(), "员工应看到全部 10 个导航项");
        for (String group : List.of("primary", "teach", "system")) {
            assertTrue(vo.getMenus().stream().anyMatch(m -> group.equals(m.getGroup())),
                    "员工应覆盖分组：" + group);
        }
        assertEquals(Capabilities.catalogue().size(), vo.getPerms().size());
    }

    @Test
    void menusAreSubsetOfPerms() {
        for (Integer type : new Integer[]{UserRole.STUDENT_CODE, UserRole.TEACHER_CODE, UserRole.STAFF_CODE}) {
            loginAs(type);
            CapabilityProfileVO vo = service.currentProfile();
            Set<String> granted = codesOf(vo);
            for (MenuItemVO item : vo.getMenus()) {
                assertTrue(granted.contains(item.getPerm()),
                        "菜单 " + item.getKey() + " 的 perm=" + item.getPerm()
                                + " 不在已授权能力码里 —— 前端会画出入口但点进去 403");
            }
            UserContext.remove();
        }
    }

    @Test
    void everyMenuPermIsDeclaredInCatalogue() {
        // 守卫：菜单写了一个能力码但忘了登记到 CATALOGUE，则该码不会下发给前端 → 入口永远不可见。
        // 这里用员工视角（拥有全部能力）遍历所有菜单，把这类笔误顶出来。
        loginAs(UserRole.STAFF_CODE);
        CapabilityProfileVO vo = service.currentProfile();
        for (MenuItemVO item : vo.getMenus()) {
            assertTrue(Capabilities.catalogue().containsKey(item.getPerm()),
                    "菜单 " + item.getKey() + " 引用了未登记的能力码：" + item.getPerm());
        }
    }

    @Test
    void permsFollowCatalogueDeclarationOrder() {
        loginAs(UserRole.STAFF_CODE);
        List<String> actual = service.currentProfile().getPerms().stream()
                .map(p -> p.getCode()).collect(Collectors.toList());
        List<String> expected = List.copyOf(Capabilities.catalogue().keySet());
        assertEquals(expected, actual, "perms 必须按目录声明序输出，保证前端每次拿到同一顺序");
    }

    @Test
    void permEntriesCarryDisplayNames() {
        loginAs(UserRole.STUDENT_CODE);
        CapabilityProfileVO vo = service.currentProfile();
        assertFalse(vo.getPerms().isEmpty());
        vo.getPerms().forEach(p -> {
            assertTrue(p.getCode() != null && !p.getCode().isBlank());
            assertTrue(p.getName() != null && !p.getName().isBlank(), p.getCode() + " 缺展示名");
        });
    }
}
