package com.codejudge.gateway.filter;

import com.codejudge.gateway.common.GatewayErrorResponse;
import com.codejudge.gateway.config.JwtProperties;
import com.codejudge.gateway.util.JwtUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 统一鉴权过滤器：校验 JWT 并透传用户身份
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    public static final String USER_INFO_HEADER = "user-info";
    public static final String ROLE_INFO_HEADER = "role-info";
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private final JwtProperties jwtProperties;
    private final JwtUtils jwtUtils;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        // 白名单放行（可选鉴权：不强制登录，但带合法 token 时透传身份，见 optionalIdentity）
        if (isExcludePath(path)) {
            return chain.filter(optionalIdentity(exchange, request));
        }

        String token = resolveToken(request);
        if (token == null) {
            return GatewayErrorResponse.write(exchange, HttpStatus.UNAUTHORIZED, 401, "未登录或登录已过期");
        }
        // 单次验签同时取出身份与角色（见 JwtUtils.parseIdentity：原来要解析三遍）
        JwtUtils.Identity identity = jwtUtils.parseIdentity(token);
        if (identity == null) {
            log.warn("token 解析失败：签名无效/已过期/载荷非法");
            return GatewayErrorResponse.write(exchange, HttpStatus.UNAUTHORIZED, 401, "登录凭证无效或已过期");
        }
        if (identity.userId() == null) {
            return GatewayErrorResponse.write(exchange, HttpStatus.UNAUTHORIZED, 401, "登录凭证无效");
        }

        ServerHttpRequest.Builder builder = request.mutate()
                .header(USER_INFO_HEADER, String.valueOf(identity.userId()));
        // 透传角色（user.type：1员工/2学员/3教师），供下游做接口级角色校验（如知识库上传的教师权限）
        if (identity.roleId() != null) {
            builder.header(ROLE_INFO_HEADER, String.valueOf(identity.roleId()));
        }
        return chain.filter(exchange.mutate().request(builder.build()).build());
    }

    private boolean isExcludePath(String path) {
        return jwtProperties.getExcludePaths().stream()
                .anyMatch(p -> PATH_MATCHER.match(p, path));
    }

    /**
     * 白名单路径的「可选鉴权」。
     *
     * <p>背景：{@code /problems/page} 这类端点既要匿名可浏览（未登录只能看到已发布题目），
     * 又要允许已登录的教师/管理员按 {@code status} 筛选草稿与已下线题目。但白名单若直接
     * {@code chain.filter(exchange)} 放行，就**从不注入 user-info** → 下游永远拿不到身份，
     * 教师工作台按 status 筛选恒为空（功能性缺陷）。
     *
     * <p>因此：携带合法 token 时透传 {@code user-info}/{@code role-info}；未携带或不合法时
     * 按匿名处理，并**剥离**客户端自带的 {@code user-info}/{@code role-info}，
     * 防止匿名请求伪造下游信任的身份头。
     */
    private ServerWebExchange optionalIdentity(ServerWebExchange exchange, ServerHttpRequest request) {
        String token = resolveToken(request);
        if (token != null) {
            // 单次验签（原来 isValid + parseUserId + parseRoleId 要解析三遍）
            JwtUtils.Identity identity = jwtUtils.parseIdentity(token);
            if (identity != null && identity.userId() != null) {
                ServerHttpRequest.Builder builder = request.mutate()
                        .header(USER_INFO_HEADER, String.valueOf(identity.userId()));
                if (identity.roleId() != null) {
                    builder.header(ROLE_INFO_HEADER, String.valueOf(identity.roleId()));
                }
                return exchange.mutate().request(builder.build()).build();
            }
            log.debug("白名单可选鉴权：token 不合法，按匿名处理");
        }
        // 匿名（或无合法 token）：一律剥离身份头，避免伪造
        ServerHttpRequest sanitized = request.mutate()
                .headers(headers -> {
                    headers.remove(USER_INFO_HEADER);
                    headers.remove(ROLE_INFO_HEADER);
                })
                .build();
        return exchange.mutate().request(sanitized).build();
    }

    private String resolveToken(ServerHttpRequest request) {
        String bearer = request.getHeaders().getFirst("authorization");
        if (bearer != null && bearer.startsWith("Bearer ")) {
            return bearer.substring(7).trim();
        }
        // WebSocket 兜底：浏览器的 WebSocket API 无法自定义握手请求头（不能设 Authorization），
        // 只能用 URL 携带凭证。因此仅对 /ws/** 放宽为查询参数 token。
        //
        // 安全取舍（已知并接受的风险）：URL 可能被记录进网关访问日志、浏览器历史与 Referer。
        // 缓解措施：① 仅限 /ws/** 路径；② JWT 本身是短期凭证；③ 生产可将 access token 生命周期
        // 调短并配合连接建立后的首帧校验。若需要彻底消除，应在 P6 引入「一次性 WS 票据」
        // （POST /accounts/ws-ticket 换取 60s 有效的单次令牌）——那属于独立需求，不在此扩张范围。
        String path = request.getURI().getPath();
        if (path != null && path.startsWith("/ws/")) {
            String query = request.getURI().getQuery();
            if (query != null) {
                for (String pair : query.split("&")) {
                    int idx = pair.indexOf('=');
                    if (idx > 0 && "token".equals(pair.substring(0, idx))) {
                        // JWT 使用 base64url（不含 '+'），故 URLDecoder 把 '+' 转空格的副作用不会伤到它
                        String value = java.net.URLDecoder.decode(pair.substring(idx + 1).trim(),
                                java.nio.charset.StandardCharsets.UTF_8);
                        return value.isEmpty() ? null : value;
                    }
                }
            }
        }
        return null;
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
