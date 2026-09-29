package com.codejudge.gateway.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

/**
 * 网关 JWT 验签工具
 */
@Component
public class JwtUtils {

    private final SecretKey key;

    public JwtUtils(com.codejudge.gateway.config.JwtProperties properties) {
        this.key = Keys.hmacShaKeyFor(properties.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 解析结果：一次验签同时取出身份、角色与吊销校验所需的 jti/iat。
     *
     * @param userId     用户 id（token 必带；缺失或非法时为 {@code null}）
     * @param roleId     角色（user.type：1员工/2学员/3教师）；旧 token 无该 claim 时为 {@code null}
     * @param jti        JWT ID（吊销黑名单的锚点）；旧 token（升级前签发）无该 claim 时为 {@code null}
     * @param issuedAtMs 签发时刻毫秒（用户级吊销纪元比较用）；无 iat claim 时为 0
     */
    public record Identity(Long userId, Integer roleId, String jti, long issuedAtMs) {
    }

    /**
     * 单次解析出 {@link Identity}。
     * <p>
     * 原实现走 {@code isValid() + parseUserId() + parseRoleId()}，同一串 JWT 被
     * {@code parseSignedClaims} 验签/解析**三遍**。网关是每个请求的必经热路径，三次 HMAC
     * 验签纯属重复计算；合并为一次后签名只校验一遍，行为不变。
     *
     * @return {@code null} 表示 token 缺失、被篡改、已过期或载荷非法（调用方一律按未认证处理）
     */
    public Identity parseIdentity(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            // token 类型校验：只有 access token 可作为接口凭证。refresh token 有效期 30 天且
            // 经 HttpOnly Cookie 流转，若不校验 type，被盗 cookie 可直接当 access 用（30 天全权限）
            if (!"access".equals(claims.get("type"))) {
                return null;
            }
            java.util.Date issuedAt = claims.getIssuedAt();
            return new Identity(toLong(claims.get("userId")), toInt(claims.get("roleId")),
                    claims.getId(), issuedAt == null ? 0L : issuedAt.getTime());
        } catch (Exception e) {
            return null;
        }
    }

    public Long parseUserId(String token) {
        Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        return toLong(claims.get("userId"));
    }

    /**
     * 解析角色 claim（user.type：1员工/2学员/3教师）；旧 token 无该 claim 时返回 null
     */
    public Integer parseRoleId(String token) {
        try {
            return toInt(Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload().get("roleId"));
        } catch (Exception e) {
            return null;
        }
    }

    public boolean isValid(String token) {
        try {
            Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private Long toLong(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number num) {
            return num.longValue();
        }
        return Long.valueOf(String.valueOf(v));
    }

    private Integer toInt(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number num) {
            return num.intValue();
        }
        return Integer.valueOf(String.valueOf(v));
    }
}
