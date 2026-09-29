package com.codejudge.gateway.filter;

import com.codejudge.gateway.common.GatewayErrorResponse;
import com.codejudge.gateway.config.GatewayProperties;
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

    // 吊销黑名单 key 前缀 —— 与 judge-common AuthRedisKeys 逐字对齐
    // （网关不依赖 judge-common，故此复制；改 key 必须两处同步）
    private static final String TOKEN_BL_JTI_PREFIX = "judge:auth:bl:jti:";
    private static final String USER_REVOKED_BEFORE_PREFIX = "judge:auth:revoked-before:";

    private final JwtProperties jwtProperties;
    private final GatewayProperties gatewayProperties;
    private final JwtUtils jwtUtils;
    private final org.springframework.data.redis.core.ReactiveStringRedisTemplate reactiveRedis;

    /**
     * 启动时打印文档白名单开关状态（P-10）：生产误开时日志可第一时间暴露。
     */
    @jakarta.annotation.PostConstruct
    void logDocWhitelistState() {
        if (gatewayProperties.isDocWhitelistEnabled()) {
            log.info("API 文档白名单：已启用 —— {} 匿名可达（仅限开发/测试环境）",
                    gatewayProperties.getDocWhitelistPaths());
        } else {
            log.info("API 文档白名单：已关闭 —— /v3/api-docs、/doc.html 匿名访问将被 401");
        }
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        // 白名单放行（可选鉴权：不强制登录，但带合法 token 时透传身份，见 optionalIdentity）。
        // 两类来源：① 必须匿名的端点（excludePaths，方法不限）；
        //          ② 公开只读端点（publicReadPaths，仅 GET/HEAD，QA-C01）
        if (isExcludePath(path) || isPublicRead(path, request.getMethod())) {
            return applyOptionalIdentity(exchange, request).flatMap(chain::filter);
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

        // 吊销校验：登出拉黑的 jti / 禁用写入的吊销纪元（judge-user）→ 立即 401，
        // 把「登出/禁用 → 生效」窗口从 token 剩余寿命压到 0
        return isRevoked(identity)
                .flatMap(revoked -> {
                    if (revoked) {
                        return GatewayErrorResponse.write(exchange, HttpStatus.UNAUTHORIZED, 401,
                                "登录凭证已失效，请重新登录");
                    }
                    ServerHttpRequest.Builder builder = request.mutate()
                            .header(USER_INFO_HEADER, String.valueOf(identity.userId()));
                    // 透传角色（user.type：1员工/2学员/3教师），供下游做接口级角色校验（如知识库上传的教师权限）
                    if (identity.roleId() != null) {
                        builder.header(ROLE_INFO_HEADER, String.valueOf(identity.roleId()));
                    }
                    return chain.filter(exchange.mutate().request(builder.build()).build());
                });
    }

    /**
     * 白名单判定（P-10 重构）：
     * <ol>
     *   <li>固定匿名白名单（{@code cj.jwt.exclude-paths}，登录/注册/登出等「必须匿名」端点）；</li>
     *   <li>API 文档白名单（{@code cj.gateway.doc-whitelist-paths}），仅当
     *       {@code cj.gateway.doc-whitelist-enabled=true}（开发/测试态）时参与匹配；
     *       生产置 false 后文档路径不再放行，匿名请求在下方走 401 分支。</li>
     * </ol>
     * 包级私有以便单元测试直接覆盖两种开关状态。
     */
    boolean isExcludePath(String path) {
        if (jwtProperties.getExcludePaths().stream()
                .anyMatch(p -> PATH_MATCHER.match(p, path))) {
            return true;
        }
        return gatewayProperties.isDocWhitelistEnabled()
                && gatewayProperties.getDocWhitelistPaths().stream()
                        .anyMatch(p -> PATH_MATCHER.match(p, path));
    }

    /**
     * 公开只读判定（QA-C01）：{@code cj.jwt.public-read-paths} 中的路径仅对
     * GET/HEAD 匿名放行。方法感知是安全边界 —— 同一路径的写方法（如对题目
     * 端点的 DELETE/POST）仍走强制登录，网关层 401 先于下游 @RequireRole 兜底。
     * 包级私有以便单元测试直接覆盖。
     */
    boolean isPublicRead(String path, org.springframework.http.HttpMethod method) {
        if (!org.springframework.http.HttpMethod.GET.equals(method)
                && !org.springframework.http.HttpMethod.HEAD.equals(method)) {
            return false;
        }
        return jwtProperties.getPublicReadPaths().stream()
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
     * 防止匿名请求伪造下游信任的身份头。已吊销的 token 同样按匿名处理（不再透传身份）。
     */
    private Mono<ServerWebExchange> applyOptionalIdentity(ServerWebExchange exchange, ServerHttpRequest request) {
        String token = resolveToken(request);
        if (token != null) {
            // 单次验签（原来 isValid + parseUserId + parseRoleId 要解析三遍）
            JwtUtils.Identity identity = jwtUtils.parseIdentity(token);
            if (identity != null && identity.userId() != null) {
                return isRevoked(identity).map(revoked -> {
                    if (revoked) {
                        log.debug("白名单可选鉴权：token 已被吊销，按匿名处理");
                        return sanitize(exchange, request);
                    }
                    ServerHttpRequest.Builder builder = request.mutate()
                            .header(USER_INFO_HEADER, String.valueOf(identity.userId()));
                    if (identity.roleId() != null) {
                        builder.header(ROLE_INFO_HEADER, String.valueOf(identity.roleId()));
                    }
                    return exchange.mutate().request(builder.build()).build();
                });
            }
            log.debug("白名单可选鉴权：token 不合法，按匿名处理");
        }
        return Mono.just(sanitize(exchange, request));
    }

    /** 匿名化：剥离身份头，避免伪造 */
    private ServerWebExchange sanitize(ServerWebExchange exchange, ServerHttpRequest request) {
        ServerHttpRequest sanitized = request.mutate()
                .headers(headers -> {
                    headers.remove(USER_INFO_HEADER);
                    headers.remove(ROLE_INFO_HEADER);
                })
                .build();
        return exchange.mutate().request(sanitized).build();
    }

    /**
     * 吊销校验（两次 Redis 读合并为一个判定）：
     * <ol>
     *   <li>jti 在登出黑名单（{@code judge:auth:bl:jti:{jti}}）→ 吊销；</li>
     *   <li>用户级吊销纪元（{@code judge:auth:revoked-before:{userId}}）存在且
     *       iat 早于纪元 → 吊销（禁用账号一次杀掉全部在途 token）。</li>
     * </ol>
     * 旧 token（升级前签发）没有 jti，跳过 ① 但 ② 仍生效；其在途寿命 ≤ 30 分钟，自然淘汰。
     * Redis 故障 fail-open：可用性优先（登录限流同样依赖 Redis，故障面一致），
     * 且签名/过期校验不受影响，最坏情况是吊销延迟到 Redis 恢复。
     */
    private Mono<Boolean> isRevoked(JwtUtils.Identity identity) {
        Mono<String> blacklisted = identity.jti() == null ? Mono.empty()
                : reactiveRedis.opsForValue().get(TOKEN_BL_JTI_PREFIX + identity.jti());
        Mono<String> revokedBefore = reactiveRedis.opsForValue()
                .get(USER_REVOKED_BEFORE_PREFIX + identity.userId());
        return Mono.zip(blacklisted.defaultIfEmpty(""), revokedBefore.defaultIfEmpty(""))
                .map(tuple -> !tuple.getT1().isEmpty() || revokedBefore(tuple.getT2(), identity.issuedAtMs()))
                .onErrorResume(e -> {
                    log.warn("token 吊销校验 Redis 异常，按未吊销放行：userId={}, err={}",
                            identity.userId(), e.toString());
                    return Mono.just(false);
                });
    }

    private boolean revokedBefore(String epoch, long issuedAtMs) {
        if (epoch.isEmpty()) {
            return false;
        }
        try {
            return issuedAtMs < Long.parseLong(epoch);
        } catch (NumberFormatException e) {
            return false;
        }
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
