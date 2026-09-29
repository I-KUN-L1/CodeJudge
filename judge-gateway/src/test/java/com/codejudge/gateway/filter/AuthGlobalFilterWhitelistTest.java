package com.codejudge.gateway.filter;

import com.codejudge.gateway.config.GatewayProperties;
import com.codejudge.gateway.config.JwtProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P-10：验证 {@code cj.gateway.doc-whitelist-enabled} 开关对网关白名单判定的影响。
 * <p>
 * 直接实例化 {@link AuthGlobalFilter}（不起 Spring 上下文），
 * 覆盖开关两种状态：true（开发/测试默认）文档路径匿名放行；
 * false（生产必须）文档路径不放行，匿名请求走 401 分支。
 * jwtUtils 与 reactiveRedis 仅构造占位（isExcludePath 不触达），传 null。
 */
class AuthGlobalFilterWhitelistTest {

    private AuthGlobalFilter newFilter(boolean docWhitelistEnabled) {
        GatewayProperties gateway = new GatewayProperties();
        gateway.setDocWhitelistEnabled(docWhitelistEnabled);
        return new AuthGlobalFilter(new JwtProperties(), gateway, null, null);
    }

    @Test
    void 开关开启_文档路径匿名放行_开发态默认() {
        AuthGlobalFilter filter = newFilter(true);
        assertThat(filter.isExcludePath("/v3/api-docs")).isTrue();
        // /v3/api-docs/** 通配覆盖 swagger-config 等子路径
        assertThat(filter.isExcludePath("/v3/api-docs/swagger-config")).isTrue();
        assertThat(filter.isExcludePath("/doc.html")).isTrue();
    }

    @Test
    void 开关关闭_文档路径不再放行_生产禁用态() {
        AuthGlobalFilter filter = newFilter(false);
        assertThat(filter.isExcludePath("/v3/api-docs")).isFalse();
        assertThat(filter.isExcludePath("/v3/api-docs/swagger-config")).isFalse();
        assertThat(filter.isExcludePath("/doc.html")).isFalse();
    }

    @Test
    void 开关不影响固定白名单_登录登出注册始终匿名可达() {
        for (boolean enabled : new boolean[] {true, false}) {
            AuthGlobalFilter filter = newFilter(enabled);
            assertThat(filter.isExcludePath("/accounts/login")).isTrue();
            assertThat(filter.isExcludePath("/accounts/refresh")).isTrue();
            assertThat(filter.isExcludePath("/accounts/logout")).isTrue();
            assertThat(filter.isExcludePath("/students/register")).isTrue();
        }
    }

    @Test
    void 业务路径不受开关影响_始终需要鉴权() {
        for (boolean enabled : new boolean[] {true, false}) {
            AuthGlobalFilter filter = newFilter(enabled);
            assertThat(filter.isExcludePath("/problems/page")).isFalse();
            assertThat(filter.isExcludePath("/submissions")).isFalse();
        }
    }

    // ==================== QA-C01：公开只读路径（方法感知） ====================

    @Test
    void 公开只读路径_GET匿名放行_已登录可选透传() {
        AuthGlobalFilter filter = newFilter(true);
        assertThat(filter.isPublicRead("/problems/page", org.springframework.http.HttpMethod.GET)).isTrue();
        // HEAD 与 GET 同语义（浏览器预检/代理探活），一并放行
        assertThat(filter.isPublicRead("/problems/page", org.springframework.http.HttpMethod.HEAD)).isTrue();
    }

    @Test
    void 公开只读路径_写方法不放行_网关层401兜底() {
        AuthGlobalFilter filter = newFilter(true);
        // QA-F05 契约：DELETE /problems/page 必须不放行（强登录），网关先于下游 @RequireRole 拦截
        assertThat(filter.isPublicRead("/problems/page", org.springframework.http.HttpMethod.DELETE)).isFalse();
        assertThat(filter.isPublicRead("/problems/page", org.springframework.http.HttpMethod.POST)).isFalse();
        assertThat(filter.isPublicRead("/problems/page", org.springframework.http.HttpMethod.PUT)).isFalse();
    }

    @Test
    void 公开只读白名单不含详情与其它业务路径() {
        AuthGlobalFilter filter = newFilter(true);
        // 刻意不放行 /problems/{id}：下游 isOwnerOrStaff 把「无 user-info 头」当
        // Feign 内部调用放行隐藏用例，网关匿名流量恰好也没有该头 —— 详情保持需登录
        assertThat(filter.isPublicRead("/problems/1", org.springframework.http.HttpMethod.GET)).isFalse();
        assertThat(filter.isPublicRead("/users/page", org.springframework.http.HttpMethod.GET)).isFalse();
        assertThat(filter.isPublicRead("/submissions", org.springframework.http.HttpMethod.GET)).isFalse();
    }
}
