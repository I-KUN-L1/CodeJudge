package com.codejudge.auth.common.util;

import com.codejudge.auth.common.constants.JwtConstants;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具：签发与解析
 */
@Component
public class JwtTool {

    private final SecretKey key;

    /** 签名密钥从环境变量 CJ_JWT_SECRET 注入，避免硬编码 */
    public JwtTool(@Value("${cj.jwt.secret}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String createAccessToken(Long userId, long ttlMillis) {
        return createToken(userId, null, ttlMillis, true);
    }

    /**
     * 签发 access token，并将用户类型（user.type：1员工/2学员/3教师）写入 role claim，
     * 供网关透传、下游服务做接口级角色校验（如知识库上传的教师权限）。
     */
    public String createAccessToken(Long userId, Integer role, long ttlMillis) {
        return createToken(userId, role, ttlMillis, true);
    }

    public String createRefreshToken(Long userId, long ttlMillis) {
        return createToken(userId, null, ttlMillis, false);
    }

    private String createToken(Long userId, Integer role, long ttlMillis, boolean access) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + ttlMillis);
        var builder = Jwts.builder()
                .subject(String.valueOf(userId))
                // jti（JWT ID）：每枚 token 的唯一指纹，是「登出吊销」的锚点 ——
                // 黑名单按 jti 拉黑（judge:auth:bl:jti:{jti}），网关/续签入口据此拒绝已吊销 token
                .id(java.util.UUID.randomUUID().toString())
                .claim(JwtConstants.PAYLOAD_USER_KEY, userId)
                .claim("type", access ? "access" : "refresh")
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key);
        if (role != null) {
            builder.claim(JwtConstants.PAYLOAD_ROLE_KEY, role);
        }
        return builder.compact();
    }

    public Long parseUserId(String token) {
        Claims claims = parse(token);
        Object userId = claims.get(JwtConstants.PAYLOAD_USER_KEY);
        if (userId instanceof Number num) {
            return num.longValue();
        }
        return Long.valueOf(String.valueOf(userId));
    }

    /** 解析角色 claim（user.type：1员工/2学员/3教师）；旧 token 无该 claim 时返回 null */
    public Integer parseRole(String token) {
        Object role = parse(token).get(JwtConstants.PAYLOAD_ROLE_KEY);
        if (role instanceof Number num) {
            return num.intValue();
        }
        return role == null ? null : Integer.valueOf(String.valueOf(role));
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    /**
     * 解析 token 的类型标记（{@code type} claim：access / refresh）。
     * <p>HMAC 是对称签名，密钥即"公钥"，不存在可公开分发的 JWK ——
     * 历史上的 {@code GET /jwks} 端点会把 {@code getEncoded()}（即密钥本体）匿名返回，
     * 等于交出任意伪造身份的能力，已随本次加固删除；该方法仅供服务内部区分 token 类型。
     */
    public String parseTokenType(String token) {
        Object type = parse(token).get("type");
        return type == null ? null : String.valueOf(type);
    }
}
