package com.codejudge.ai.security;

import com.codejudge.common.constants.Constant;
import com.codejudge.common.constants.UserRole;
import org.springframework.http.server.reactive.ServerHttpRequest;

/**
 * WebFlux 下的调用方身份（网关鉴权后透传的 {@code user-info} / {@code role-info} 请求头）。
 *
 * <h3>为什么不直接用 judge-common 的 UserContext</h3>
 * {@code UserContext} 是 <b>ThreadLocal + Servlet 拦截器（UserInfoInterceptor）</b>填充的。
 * 本服务跑在 Netty 上，没有 Servlet 拦截器，也没有「一个请求固定一个线程」的前提
 * （响应式链路上同一请求可能在不同线程上执行），因此 {@code UserContext.getUser()}
 * 在本服务里<b>恒为 null</b>。
 *
 * <p>这个「恒为 null」会带来一个非常隐蔽的越权漏洞：judge-common 的
 * {@code InternalOnlyGuard} / {@code OwnerAccessGuard} 都以「无 user-info 头 ⇒ 判定为
 * 服务间 Feign 直连 ⇒ 放行」为规则。若在 WebFlux 里复用它们，**所有外部请求都会被误判为内部调用而放行**。
 * 因此本服务的鉴权一律走本类，从请求头显式取身份，并且：
 * <ul>
 *   <li>绝不调用 {@code UserContext.*}；</li>
 *   <li>绝不调用 {@code InternalOnlyGuard.checkInternal()} / {@code OwnerAccessGuard.check(...)}。</li>
 * </ul>
 *
 * <h3>解析容错</h3>
 * 头值非法（非数字）时对应字段置 null，而不是抛异常。
 * 目的：绕过网关直连 9087 的调用方可以随便填头，若在这里抛异常就变成了
 * 「用一个畸形头即可让服务返回 500」的廉价 DoS 面。置 null 后语义收敛为「未登录」，
 * 由 {@link AiAccessGuard} 统一拒绝，行为可预期。
 */
public record AiIdentity(Long userId, Integer role) {

    /** 匿名（未携带合法身份头） */
    public static final AiIdentity ANONYMOUS = new AiIdentity(null, null);

    /** 从请求头解析身份（网关流量必带；直连时可能缺失） */
    public static AiIdentity from(ServerHttpRequest request) {
        return new AiIdentity(
                parseLong(request.getHeaders().getFirst(Constant.USER_INFO_HEADER)),
                parseInt(request.getHeaders().getFirst(Constant.ROLE_INFO_HEADER)));
    }

    /** 是否已登录 */
    public boolean authenticated() {
        return userId != null;
    }

    /** 是否为特权角色（员工 / 教师）—— 可点评任意提交、可维护知识库 */
    public boolean privileged() {
        return role != null
                && (role == UserRole.STAFF.getCode() || role == UserRole.TEACHER.getCode());
    }

    private static Long parseLong(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer parseInt(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
