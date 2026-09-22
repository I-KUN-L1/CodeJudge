package com.codejudge.gateway.ratelimit;

import com.codejudge.gateway.filter.AuthGlobalFilter;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 登录接口限流 key 解析器：IP + 用户 双维度组合 key。
 * <p>
 * 用途：对 {@code POST /accounts/login} 做防爆破限流。登录请求基本都处于未认证状态，
 * 因此 IP 是主维度；已携带 user-info 头的场景按 IP+userId 拆分独立令牌桶，避免同一出口 IP
 * 下多用户互相挤占配额。
 * <p>
 * 网关限流使用 Redis 令牌算法（RequestRateLimiter），配置见
 * {@code application.yml} 的 {@code login-rate-limit} 路由。
 */
@Component
public class LoginRateLimitKeyResolver implements KeyResolver {

    /** 限流 key 前缀，便于在 Redis 中区分用途与排查 */
    public static final String KEY_PREFIX = "rate:login:";

    @Override
    public Mono<String> resolve(ServerWebExchange exchange) {
        String userId = exchange.getRequest().getHeaders().getFirst(AuthGlobalFilter.USER_INFO_HEADER);
        String ip = exchange.getRequest().getRemoteAddress() != null
                ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
                : "unknown";
        return Mono.just(KEY_PREFIX + ip + ":" + (userId == null ? "anon" : userId));
    }
}
