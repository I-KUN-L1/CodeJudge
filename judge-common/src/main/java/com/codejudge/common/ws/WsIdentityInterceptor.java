package com.codejudge.common.ws;

import com.codejudge.common.constants.Constant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * WebSocket 握手身份拦截器：把网关透传的 {@code user-info} / {@code role-info} 头
 * 落到会话属性里，供各服务的 WS Handler 做归属校验。
 *
 * <p><b>为什么不用 MVC 拦截器 + UserContext</b>：{@code WebSocketHandlerRegistry} 注册的
 * 端点由它自己的 {@code SimpleUrlHandlerMapping} 映射，而 {@code MvcConfig.addInterceptors}
 * 只作用于 WebMvc 自己创建的映射 —— 依赖它会出现「REST 请求有身份、WS 握手拿不到身份」
 * 的不确定行为。握手拦截器是 WebSocket 规范内的唯一确定位置。
 *
 * <p><b>鉴权边界</b>：JWT 校验在网关完成（{@code AuthGlobalFilter}），本拦截器只做
 * 「有无身份」的兜底 —— 没有 {@code user-info} 头说明请求绕过了网关（直连服务端口），
 * 直接以 401 拒绝握手。这与 {@code InternalOnlyGuard} 的前提假设一致。
 */
@Slf4j
public class WsIdentityInterceptor implements HandshakeInterceptor {

    /** 会话属性键：登录用户 id（Long） */
    public static final String ATTR_USER_ID = "wsUserId";
    /** 会话属性键：角色（Integer，1员工/2学员/3教师） */
    public static final String ATTR_ROLE = "wsRole";

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String userId = request.getHeaders().getFirst(Constant.USER_INFO_HEADER);
        if (userId == null || userId.isBlank()) {
            log.warn("WS 握手缺少 user-info 头（疑似绕过网关直连服务端口），拒绝：uri={}",
                    request.getURI().getPath());
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        try {
            attributes.put(ATTR_USER_ID, Long.parseLong(userId.trim()));
        } catch (NumberFormatException e) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        String role = request.getHeaders().getFirst(Constant.ROLE_INFO_HEADER);
        if (role != null && !role.isBlank()) {
            try {
                attributes.put(ATTR_ROLE, Integer.parseInt(role.trim()));
            } catch (NumberFormatException ignored) {
                // 非法角色按「无角色」处理，后续权限判断自然 fail-closed
            }
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 无需处理
    }
}
