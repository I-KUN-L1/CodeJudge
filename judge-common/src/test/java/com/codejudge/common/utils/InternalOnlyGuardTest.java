package com.codejudge.common.utils;

import com.codejudge.common.constants.UserRole;
import com.codejudge.common.exceptions.ForbiddenException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * InternalOnlyGuard 内部接口守卫单测。
 *
 * <p>回归背景：内部聚合端点（如 /users/** 统计类接口）虽设计为 Feign 内部调用，
 * 但仍可能被网关路由对外暴露，任何已登录用户（含管理员）将可直接访问。
 */
class InternalOnlyGuardTest {

    @AfterEach
    void tearDown() {
        UserContext.remove();
    }

    @Test
    @DisplayName("内部 Feign 调用（无 user-info 头 → UserContext 为空）放行")
    void internalCallWithoutUserContextShouldPass() {
        assertDoesNotThrow(InternalOnlyGuard::checkInternal);
    }

    @Test
    @DisplayName("学员经网关访问内部端点拒绝")
    void studentShouldBeForbidden() {
        UserContext.setUser(100L);
        UserContext.setRole(UserRole.STUDENT.getCode());
        assertThrows(ForbiddenException.class, InternalOnlyGuard::checkInternal);
    }

    @Test
    @DisplayName("管理员经网关访问内部端点同样拒绝（内部端点不区分角色）")
    void staffShouldBeForbidden() {
        UserContext.setUser(1L);
        UserContext.setRole(UserRole.STAFF.getCode());
        assertThrows(ForbiddenException.class, InternalOnlyGuard::checkInternal);
    }

    @Test
    @DisplayName("教师经网关访问内部端点拒绝")
    void teacherShouldBeForbidden() {
        UserContext.setUser(300L);
        UserContext.setRole(UserRole.TEACHER.getCode());
        assertThrows(ForbiddenException.class, InternalOnlyGuard::checkInternal);
    }
}
