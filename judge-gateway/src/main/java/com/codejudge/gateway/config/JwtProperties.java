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

    /** 与认证服务一致的签名密钥（由环境变量 CJ_JWT_SECRET 注入，见 .env.example） */
    private String secret;

    /**
     * 白名单路径：匿名可达。
     * <p>
     * 原则：只放行「必须匿名」的端点，写操作一律不放行，由后端 {@code @RequireRole} fail-closed 兜底。
     * <p>
     * ⚠️ 与 zx-learn 底座的差异（安全加固）：底座白名单含 {@code /teachers/register}，
     * 意味着任何人都能自助注册成教师账号 —— 教师可创建题目、查看隐藏测试用例，
     * 这在判题平台里等于把题库存取权公开。CodeJudge 已将其移出白名单，
     * 并在 {@code TeacherController#register} 上补 {@code @RequireRole(STAFF)}，
     * 教师账号只能由管理员/员工开通。学员自助注册保留（{@code /students/register}）。
     */
    private List<String> excludePaths = List.of(
            "/accounts/login",
            "/accounts/admin/login",
            "/accounts/refresh",
            "/accounts/password/first-change",
            // 学员自助注册：判题平台允许学员自行注册后刷题
            "/students/register",
            // JWK 公钥集：供其他服务/前端校验证书链，公开无敏感信息
            "/jwks",
            "/jwks/**",
            // 接口文档（仅开发态使用，生产建议由运维按环境关闭）
            "/v3/api-docs",
            "/v3/api-docs/**",
            "/doc.html"
    );
}
