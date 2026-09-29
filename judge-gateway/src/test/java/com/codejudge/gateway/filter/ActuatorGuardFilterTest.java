package com.codejudge.gateway.filter;

import com.codejudge.gateway.config.GatewayProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.InetAddress;
import java.net.InetSocketAddress;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ActuatorGuardFilter} 单元测试（BUG-002 / QA-F01 修复）。
 *
 * <p>覆盖：内网白名单模式（回环/Docker 桥接网放行、公网 404、来源缺失 fail-closed）、
 * 严格令牌模式（任何来源必须携带）、X-Forwarded-For 不被信任、开关关闭。
 */
@ExtendWith(MockitoExtension.class)
class ActuatorGuardFilterTest {

    private static final InetAddress LOOPBACK = inet("127.0.0.1");
    private static final InetAddress DOCKER_BRIDGE = inet("172.18.0.5");
    private static final InetAddress PUBLIC = inet("8.8.8.8");
    private static final InetAddress IPV6_ULA = inet("fc00::1");

    @Mock
    private WebFilterChain chain;
    @Mock
    private ServerHttpResponse response;

    private GatewayProperties properties;
    private ActuatorGuardFilter filter;

    @BeforeEach
    void setUp() {
        properties = new GatewayProperties();
        filter = new ActuatorGuardFilter(properties);
        lenient().when(response.setComplete()).thenReturn(Mono.empty());
        lenient().when(chain.filter(any(ServerWebExchange.class))).thenReturn(Mono.empty());
    }

    private static InetAddress inet(String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ServerWebExchange exchangeFor(String path, InetAddress remote, HttpHeaders headers) {
        ServerWebExchange exchange = mock(ServerWebExchange.class);
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        org.springframework.http.server.RequestPath requestPath =
                mock(org.springframework.http.server.RequestPath.class);
        lenient().when(exchange.getRequest()).thenReturn(request);
        lenient().when(request.getPath()).thenReturn(requestPath);
        lenient().when(requestPath.value()).thenReturn(path);
        if (remote != null) {
            lenient().when(request.getRemoteAddress())
                    .thenReturn(new InetSocketAddress(remote, 12345));
        }
        if (headers != null) {
            lenient().when(request.getHeaders()).thenReturn(headers);
        }
        lenient().when(exchange.getResponse()).thenReturn(response);
        return exchange;
    }

    @Test
    @DisplayName("回环来源（本机浏览器/脚本/健康检查）→ 放行")
    void loopbackSourceAllowed() {
        filter.filter(exchangeFor("/actuator/prometheus", LOOPBACK, null), chain).block();
        verify(chain).filter(any(ServerWebExchange.class));
    }

    @Test
    @DisplayName("Docker 桥接网来源（Prometheus 抓取 172.18.x）→ 放行")
    void dockerBridgeSourceAllowed() {
        filter.filter(exchangeFor("/actuator/prometheus", DOCKER_BRIDGE, null), chain).block();
        verify(chain).filter(any(ServerWebExchange.class));
    }

    @Test
    @DisplayName("IPv6 ULA（fc00::/7 内网）→ 放行")
    void ipv6UlaAllowed() {
        filter.filter(exchangeFor("/actuator/health", IPV6_ULA, null), chain).block();
        verify(chain).filter(any(ServerWebExchange.class));
    }

    @Test
    @DisplayName("公网来源 → 404（不回 403，不暴露端点存在性），不进业务链")
    void publicSourceDeniedWith404() {
        filter.filter(exchangeFor("/actuator/prometheus", PUBLIC, null), chain).block();
        verify(response).setStatusCode(HttpStatus.NOT_FOUND);
        verify(chain, never()).filter(any(ServerWebExchange.class));
    }

    @Test
    @DisplayName("来源地址缺失 → fail-closed 404")
    void missingRemoteAddressFailsClosed() {
        filter.filter(exchangeFor("/actuator/prometheus", null, null), chain).block();
        verify(response).setStatusCode(HttpStatus.NOT_FOUND);
        verify(chain, never()).filter(any(ServerWebExchange.class));
    }

    @Test
    @DisplayName("X-Forwarded-For 伪造回环不被信任：TCP 直连公网仍 404")
    void xffHeaderNotTrusted() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Forwarded-For", "127.0.0.1");
        filter.filter(exchangeFor("/actuator/prometheus", PUBLIC, headers), chain).block();
        verify(response).setStatusCode(HttpStatus.NOT_FOUND);
        verify(chain, never()).filter(any(ServerWebExchange.class));
    }

    @Test
    @DisplayName("非 /actuator 路径不受防护影响（公网来源业务流量照常）")
    void nonActuatorPathUnaffected() {
        filter.filter(exchangeFor("/users/page", PUBLIC, null), chain).block();
        verify(chain).filter(any(ServerWebExchange.class));
        verify(response, never()).setStatusCode(any(HttpStatus.class));
    }

    @Test
    @DisplayName("防护开关关闭 → 恢复匿名可达（仅内网联调场景）")
    void disabledGuardRestoresAnonymousAccess() {
        properties.getActuatorGuard().setEnabled(false);
        filter.filter(exchangeFor("/actuator/prometheus", PUBLIC, null), chain).block();
        verify(chain).filter(any(ServerWebExchange.class));
    }

    @Test
    @DisplayName("严格令牌模式：未带令牌的回环来源也 404")
    void tokenModeDeniesLoopbackWithoutToken() {
        properties.getActuatorGuard().setToken("s3cret-token");
        filter.filter(exchangeFor("/actuator/prometheus", LOOPBACK, new HttpHeaders()), chain).block();
        verify(response).setStatusCode(HttpStatus.NOT_FOUND);
        verify(chain, never()).filter(any(ServerWebExchange.class));
    }

    @Test
    @DisplayName("严格令牌模式：Authorization Bearer 放行（Prometheus scrape 接入方式）")
    void tokenModeAcceptsBearerHeader() {
        properties.getActuatorGuard().setToken("s3cret-token");
        HttpHeaders headers = new HttpHeaders();
        headers.add("Authorization", "Bearer s3cret-token");
        filter.filter(exchangeFor("/actuator/prometheus", PUBLIC, headers), chain).block();
        verify(chain).filter(any(ServerWebExchange.class));
    }

    @Test
    @DisplayName("严格令牌模式：X-Actuator-Token 头同样放行")
    void tokenModeAcceptsDedicatedHeader() {
        properties.getActuatorGuard().setToken("s3cret-token");
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Actuator-Token", "s3cret-token");
        filter.filter(exchangeFor("/actuator/prometheus", LOOPBACK, headers), chain).block();
        verify(chain).filter(any(ServerWebExchange.class));
    }

    @Test
    @DisplayName("严格令牌模式：令牌错误 → 404")
    void tokenModeRejectsWrongToken() {
        properties.getActuatorGuard().setToken("s3cret-token");
        HttpHeaders headers = new HttpHeaders();
        headers.add("Authorization", "Bearer wrong-token");
        filter.filter(exchangeFor("/actuator/prometheus", LOOPBACK, headers), chain).block();
        verify(response).setStatusCode(HttpStatus.NOT_FOUND);
        verify(chain, never()).filter(any(ServerWebExchange.class));
    }

    @Test
    @DisplayName("过滤器优先级最高：先于一切业务与网关路由过滤器")
    void runsBeforeEverythingElse() {
        org.junit.jupiter.api.Assertions.assertEquals(
                Ordered.HIGHEST_PRECEDENCE, filter.getOrder());
    }
}
