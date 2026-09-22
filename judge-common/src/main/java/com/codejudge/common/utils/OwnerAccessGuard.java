package com.codejudge.common.utils;

import com.codejudge.common.constants.UserRole;
import com.codejudge.common.exceptions.ForbiddenException;

/**
 * 资源所有者访问守卫：保护"按 userId 查询他人数据"的内部型接口。
 *
 * <p>背景：形如 {@code /xxx/users/{userId}} 的接口设计为
 * 内部 Feign 调用（@NoWrapper），但网关同样对外暴露了这些路径，存在水平越权（IDOR）风险。
 *
 * <p>在 judge-problem 中的复用方式：把资源的归属者 id（如 {@code problem.ownerId}）当作
 * targetUserId 传入，语义即为"当前用户必须是该资源的归属者，或 STAFF"。
 *
 * <p>判定规则：
 * <ul>
 *   <li>请求无 user-info 头（UserContext.getUser() == null）→ 服务间 Feign 直连，放行。
 *       依据：网关认证后必定注入 user-info 头，且 Feign 仅透传 requestId，不转发该头；</li>
 *   <li>外部用户请求：仅允许本人查询，或 STAFF（员工）查询任意用户；</li>
 *   <li>其余情况抛 ForbiddenException(403)。</li>
 * </ul>
 *
 * <p>注意：被保护路径不得加入网关 excludePaths 白名单，否则无 user-info 头的
 * 未登录请求会被误判为内部调用而放行。
 */
public final class OwnerAccessGuard {

    private OwnerAccessGuard() {
    }

    /**
     * 校验当前请求是否可访问 targetUserId 的私有数据。
     *
     * @param targetUserId 被查询的用户 id
     */
    public static void checkOwnerOrInternal(Long targetUserId) {
        Long current = UserContext.getUser();
        if (current == null) {
            // 服务间内部调用：网关流量必带 user-info 头，无头即 Feign 直连
            return;
        }
        if (current.equals(targetUserId) || UserContext.hasRole(UserRole.STAFF.getCode())) {
            return;
        }
        throw new ForbiddenException("无权限访问该资源");
    }
}
