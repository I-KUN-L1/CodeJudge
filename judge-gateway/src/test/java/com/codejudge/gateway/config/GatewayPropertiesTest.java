package com.codejudge.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P-10：验证 {@code cj.gateway.doc-whitelist-enabled} 配置项的读取与绑定。
 * <p>
 * 走 {@link Binder} 直接绑定（不起 Spring 上下文）：等价于 application.yml /
 * .env（properties 导入）对 {@code cj.gateway.*} 的 relaxed binding 路径。
 */
class GatewayPropertiesTest {

    private GatewayProperties bind(Map<String, String> props) {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(props);
        // 空配置时 bind 结果为 unbound，get() 会抛 NoSuchElement；
        // orElse 回退到带 Java 默认值的新实例（docWhitelistEnabled=true），与未配置语义一致
        return new Binder(source)
                .bind("cj.gateway", Bindable.ofInstance(new GatewayProperties()))
                .orElse(new GatewayProperties());
    }

    @Test
    void 未配置时默认启用_开发态文档路径匿名可达() {
        GatewayProperties props = bind(Map.of());
        assertThat(props.isDocWhitelistEnabled()).isTrue();
        assertThat(props.getDocWhitelistPaths())
                .containsExactly("/v3/api-docs", "/v3/api-docs/**", "/doc.html");
    }

    @Test
    void 显式false应正确绑定_生产禁用文档白名单() {
        GatewayProperties props = bind(Map.of("cj.gateway.doc-whitelist-enabled", "false"));
        assertThat(props.isDocWhitelistEnabled()).isFalse();
        // 路径列表不受开关影响，仅参与匹配与否
        assertThat(props.getDocWhitelistPaths()).isNotEmpty();
    }

    @Test
    void 显式true应正确绑定_开发态显式开启() {
        GatewayProperties props = bind(Map.of("cj.gateway.doc-whitelist-enabled", "true"));
        assertThat(props.isDocWhitelistEnabled()).isTrue();
    }
}
