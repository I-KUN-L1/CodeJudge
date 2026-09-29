package com.codejudge.auth.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JWT 工具单元测试：验证签发与解析的正确性
 */
class JwtToolTest {

    /** 测试专用密钥（与生产环境无关，生产密钥由环境变量 CJ_JWT_SECRET 注入） */
    private final JwtTool jwtTool = new JwtTool("zhixing-learn-jwt-test-secret-key-2024-for-unit-tests");

    @Test
    void createAndParseAccessToken() {
        String token = jwtTool.createAccessToken(100L, 30 * 60 * 1000L);
        assertNotNull(token);
        assertEquals(100L, jwtTool.parseUserId(token));
    }

    @Test
    void createAndParseRefreshToken() {
        String token = jwtTool.createRefreshToken(200L, 30L * 24 * 60 * 60 * 1000L);
        assertNotNull(token);
        assertEquals(200L, jwtTool.parseUserId(token));
    }

    @Test
    void differentUsersProduceDifferentTokens() {
        String token1 = jwtTool.createAccessToken(1L, 60000L);
        String token2 = jwtTool.createAccessToken(2L, 60000L);
        assertNotEquals(token1, token2);
    }

    @Test
    void tokenTypeClaimIsWrittenAndParsable() {
        // type claim 是网关/续签链路区分 access 与 refresh 的依据（缺它 refresh 可当 access 用）
        String access = jwtTool.createAccessToken(100L, 30 * 60 * 1000L);
        String refresh = jwtTool.createRefreshToken(200L, 30L * 24 * 60 * 60 * 1000L);
        assertEquals("access", jwtTool.parseTokenType(access));
        assertEquals("refresh", jwtTool.parseTokenType(refresh));
    }

    @Test
    void everyTokenCarriesUniqueJti() {
        // jti 是登出吊销的锚点：黑名单按 jti 拉黑（judge:auth:bl:jti:{jti}）。
        // 每枚 token 必须携带唯一 jti，否则拉黑一枚 = 拉黑一批。
        String access = jwtTool.createAccessToken(100L, 30 * 60 * 1000L);
        String refresh = jwtTool.createRefreshToken(100L, 30L * 24 * 60 * 60 * 1000L);
        String accessAgain = jwtTool.createAccessToken(100L, 30 * 60 * 1000L);

        String jti = jwtTool.parse(access).getId();
        assertNotNull(jti, "access token 必须携带 jti claim");
        assertNotNull(jwtTool.parse(refresh).getId(), "refresh token 必须携带 jti claim");
        assertNotEquals(jti, jwtTool.parse(refresh).getId());
        assertNotEquals(jti, jwtTool.parse(accessAgain).getId());
    }
}
