package com.codejudge.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 网关鉴权配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "cj.jwt")
public class JwtProperties {

    /** 与认证服务一致的签名密钥（由环境变量 CJ_JWT_SECRET 注入, 见 .env.example） */
    private String secret;

    /**
     * 白名单路径: 匿名可达, 只放行"必须匿名"的端点, 写操作一律不放行,
     * 由后端 @RequireRole fail-closed 兜底。
     *
     * 安全说明:
     * - /teachers/register 已移出白名单并补 @RequireRole(STAFF), 教师账号只能由管理员开通;
     *   学员自助注册保留 (/students/register)。
     * - /jwks 已随 2026-09-25 加固删除: HMAC 对称签名没有"公钥", 匿名暴露即交出伪造身份能力。
     * - 接口文档路径 (/v3/api-docs, /doc.html) 已迁出本列表 (P-10):
     *   改由 cj.gateway.doc-whitelist-enabled 驱动 (GatewayProperties.docWhitelistPaths),
     *   生产置 false 后匿名访问文档路径直接 401。
     */
    private List<String> excludePaths = List.of(
            "/accounts/login",
            "/accounts/admin/login",
            "/accounts/refresh",
            // 登出必须匿名可达: access token 过期/已吊销后, 用户仍需能清理 HttpOnly refresh
            // cookie (JS 无法清除 HttpOnly) 并吊销在途 token, 否则共享设备上的旧 cookie 残留
            "/accounts/logout",
            "/accounts/password/first-change",
            // 学员自助注册: 判题平台允许学员自行注册后刷题
            "/students/register"
    );

    /**
     * 公开只读路径（QA-C01 修复）：仅 GET/HEAD 匿名放行（经可选鉴权透传身份），
     * POST/PUT/DELETE 等写方法不匹配 —— 仍走强制登录 401，守住
     * 「写操作一律不放行」的既有约定（QA-F05 断言 DELETE 题目端点必须 401）。
     *
     * <p>刻意<b>不含</b> {@code /problems/{id}} 详情：下游
     * {@code ProblemService.isOwnerOrStaff} 把「无 user-info 头」视为服务间
     * Feign 内部调用而放行（返回 true），而网关匿名流量恰好也没有该头 ——
     * 一旦放行详情路径，未登录用户将能看到草稿/下线题目并收到隐藏测试用例。
     * 在区分「内部调用」与「网关匿名」的标记机制落地前，详情保持需登录。
     */
    private List<String> publicReadPaths = List.of("/problems/page");
}
