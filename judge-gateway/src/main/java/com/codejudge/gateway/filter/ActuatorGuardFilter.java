package com.codejudge.gateway.filter;

import com.codejudge.gateway.config.GatewayProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * 网关自身 actuator 端点防护（BUG-002 修复，QA-F01）。
 *
 * <p>背景：Spring Cloud Gateway 自身的 management 端点挂在与业务相同的 9080 端口上，
 * 但它<b>不属于网关路由</b>——{@link AuthGlobalFilter}（GlobalFilter）只对路由转发的请求生效，
 * 管控不到 {@code /actuator/**}，导致 194KB 运行指标（JVM 内存、连接池、路由清单）
 * 对任意来源匿名可达，与 README「指标端点仅宿主机/监控网可达」的承诺矛盾。
 *
 * <p>本过滤器是 {@code WebFilter}（作用于整个 HttpHandler 链，先于一切路由与
 * management handler），两档防护：
 * <ol>
 *   <li><b>严格模式</b>：配置了 {@code CJ_ACTUATOR_TOKEN} → 任何来源（含内网）都必须
 *       携带 {@code Authorization: Bearer <token>} 或 {@code X-Actuator-Token}。
 *       生产推荐——内网来源可能只是宿主机上的反代，IP 白名单在 NAT/反代后形同虚设；</li>
 *   <li><b>内网白名单模式</b>（默认，未配令牌）：仅回环 + RFC1918 私网 + 链路本地 +
 *       IPv6 ULA 可达。覆盖本地浏览器/脚本、Docker 桥接网（Prometheus 抓取），
 *       公网来源一律 <b>404</b>（不回 403，避免确认端点存在）。</li>
 * </ol>
 *
 * <p>注意：来源判定只取 TCP 直连地址，<b>绝不信任 X-Forwarded-For</b>（可任意伪造）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActuatorGuardFilter implements WebFilter, Ordered {

    private static final String ACTUATOR_PREFIX = "/actuator";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String HEADER_ACTUATOR_TOKEN = "X-Actuator-Token";

    private final GatewayProperties properties;

    @Override
    public int getOrder() {
        // 抢在所有业务/网关过滤器之前，/actuator 流量在入口即被处置
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!properties.getActuatorGuard().isEnabled()
                || !exchange.getRequest().getPath().value().startsWith(ACTUATOR_PREFIX)) {
            return chain.filter(exchange);
        }

        // 严格模式：配置了令牌 → 一律验令牌，不再看来源
        String token = properties.getActuatorGuard().getToken();
        if (token != null && !token.isBlank()) {
            if (tokenEquals(token, exchange)) {
                return chain.filter(exchange);
            }
            return deny(exchange, "令牌缺失或不匹配");
        }

        // 内网白名单模式：只认 TCP 直连来源
        InetAddress remote = remoteAddress(exchange);
        if (remote != null && isPrivateSource(remote)) {
            return chain.filter(exchange);
        }
        return deny(exchange, remote == null ? "来源地址缺失" : "非内网来源 " + remote.getHostAddress());
    }

    private Mono<Void> deny(ServerWebExchange exchange, String reason) {
        log.warn("已拦截非受信来源访问网关 actuator：path={} remote={} 原因={}",
                exchange.getRequest().getPath().value(),
                exchange.getRequest().getRemoteAddress(), reason);
        exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
        return exchange.getResponse().setComplete();
    }

    private boolean tokenEquals(String expected, ServerWebExchange exchange) {
        String header = exchange.getRequest().getHeaders().getFirst(HEADER_ACTUATOR_TOKEN);
        if (header == null) {
            String auth = exchange.getRequest().getHeaders().getFirst("Authorization");
            header = (auth != null && auth.startsWith(BEARER_PREFIX))
                    ? auth.substring(BEARER_PREFIX.length()).trim() : null;
        }
        return header != null && !header.isBlank() && constantTimeEquals(expected.trim(), header.trim());
    }

    /** 常量时间比较，防时序侧信道逐字节猜令牌 */
    private boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(
                a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private InetAddress remoteAddress(ServerWebExchange exchange) {
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        return remote == null ? null : remote.getAddress();
    }

    /**
     * 受信来源：回环（127.0.0.0/8、::1）、RFC1918 私网（10/8、172.16/12、192.168/16，
     * Docker 默认桥接网 172.17+/自定义网均落在其中）、链路本地（169.254/16、fe80::/10）、
     * IPv6 ULA（fc00::/7，内网 IPv6 部署）。
     */
    private boolean isPrivateSource(InetAddress addr) {
        if (addr.isLoopbackAddress() || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()) {
            return true;
        }
        // InetAddress#isSiteLocalAddress 不覆盖 IPv6 ULA（fc00::/7），单独判定
        if (addr instanceof Inet6Address) {
            byte first = addr.getAddress()[0];
            return (first & 0xFE) == 0xFC;
        }
        return false;
    }
}
