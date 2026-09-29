package com.codejudge.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 网关自身行为配置（P-10 API 文档环境开关）。
 * <p>
 * {@code cj.gateway.doc-whitelist-enabled} 控制 API 文档路径是否进入匿名白名单：
 * <ul>
 *   <li>{@code true}（开发/测试默认）：{@code /v3/api-docs}、{@code /doc.html} 匿名可达；</li>
 *   <li>{@code false}（生产必须）：文档路径不再放行，匿名请求被 {@link
 *       com.codejudge.gateway.filter.AuthGlobalFilter} 直接 401，
 *       防止接口清单/参数结构在公网暴露（对应 {@code .env} 的 {@code CJ_DOC_WHITELIST_ENABLED}）。</li>
 * </ul>
 * 与各服务自身的 {@code CJ_SPRINGDOC_ENABLED}（关闭服务端文档端点）是两层独立防线：
 * 网关开关管「经网关的匿名访问」，服务开关管「端点是否存在」。
 */
@Data
@Component("cjGatewayProperties")
@ConfigurationProperties(prefix = "cj.gateway")
public class GatewayProperties {

    /** 是否将 API 文档路径加入匿名白名单；生产必须显式置 false（见 .env.example） */
    private boolean docWhitelistEnabled = true;

    /** 文档白名单路径（仅 docWhitelistEnabled=true 时生效），Ant 风格 */
    private List<String> docWhitelistPaths = List.of(
            "/v3/api-docs",
            "/v3/api-docs/**",
            "/doc.html"
    );

    /** actuator 防护（BUG-002 修复，QA-F01）：控制网关自身 /actuator/** 的可达性 */
    private ActuatorGuard actuatorGuard = new ActuatorGuard();

    @Data
    public static class ActuatorGuard {

        /**
         * 是否启用 /actuator/** 防护。启用时：未配置令牌 → 仅回环/内网来源可达，
         * 其余一律 404（不暴露端点存在性）；配置了令牌 → 任何来源都必须携带令牌。
         * 关闭即恢复匿名可达，仅限内网联调场景。
         */
        private boolean enabled = true;

        /**
         * 访问令牌（{@code CJ_ACTUATOR_TOKEN}）。非空时进入严格模式：
         * 即使来源是回环/内网也必须携带 {@code Authorization: Bearer <token>}
         * 或 {@code X-Actuator-Token: <token>}。生产建议设置 —— 内网来源
         * 可能只是宿主机上的反代，IP 白名单在 NAT/反代后形同虚设。
         */
        private String token = "";
    }
}
