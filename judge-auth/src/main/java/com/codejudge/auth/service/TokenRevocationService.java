package com.codejudge.auth.service;

import com.codejudge.auth.common.constants.JwtConstants;
import com.codejudge.auth.common.util.JwtTool;
import com.codejudge.common.constants.AuthRedisKeys;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Date;

/**
 * Token 吊销服务：让「登出 / 禁用」对已签发的 JWT 即时生效。
 *
 * <p>JWT 是无状态凭证，签出后服务端本无权收回 —— 修复前的两个残留窗口：
 * <ul>
 *   <li>登出只清了浏览器 cookie，被盗取的 access/refresh token 仍按剩余寿命有效；</li>
 *   <li>禁用账号后，在途 access token 还能在 30 分钟 TTL 内畅通（refresh 侧已由
 *       状态校验拦截，见 {@code AccountService.refresh}）。</li>
 * </ul>
 * 本服务用两个 Redis key 把「吊销 → 生效」的窗口从 token 剩余寿命压到一次请求：
 * <ol>
 *   <li><b>jti 黑名单</b>：{@code judge:auth:bl:jti:{jti}}，TTL = token 剩余寿命
 *       （登出时吊销本次会话，精确到单枚 token，不影响用户其他设备）；</li>
 *   <li><b>用户级吊销纪元</b>：{@code judge:auth:revoked-before:{userId}} = 吊销时刻
 *       毫秒时间戳（禁用账号时由 judge-user 写入）—— 无需枚举在途 jti 即可一次杀掉
 *       该用户全部 token，TTL = refresh 最长寿命 30 天。</li>
 * </ol>
 * 校验点：网关 {@code AuthGlobalFilter}（每个 access token）+ {@code AccountService.refresh}
 * （refresh token）。key 常量见 {@link AuthRedisKeys}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenRevocationService {

    /** 用户级吊销纪元的存活期 = refresh token 最长寿命（须覆盖纪元写入前签发的最后一枚 refresh token） */
    private static final Duration USER_REVOCATION_TTL = Duration.ofDays(30);

    private final JwtTool jwtTool;
    private final StringRedisTemplate redis;

    /**
     * 登出吊销：解析请求携带的 access token（Authorization 头）与两枚 refresh cookie，
     * 把各自 jti 拉黑，TTL = 剩余寿命。解析失败（缺失/已过期/被篡改）静默跳过 ——
     * 登出永远成功，不因 token 状态报错；已过期 token 无需拉黑（自然失效）。
     */
    public void revokeRequestTokens(HttpServletRequest request) {
        String bearer = request.getHeader("Authorization");
        if (bearer != null && bearer.startsWith("Bearer ")) {
            revokeJtiQuietly(bearer.substring(7).trim(), "access");
        }
        revokeCookieJtiQuietly(request, JwtConstants.JWT_REFRESH_COOKIE_KEY);
        revokeCookieJtiQuietly(request, JwtConstants.JWT_ADMIN_REFRESH_COOKIE_KEY);
    }

    /**
     * 用户级吊销（禁用账号时调用）：写入吊销纪元，iat 早于此刻的全部 token 即刻失效。
     */
    public void revokeAllTokensIssuedBeforeNow(Long userId) {
        if (userId == null) {
            return;
        }
        try {
            // 纪元对齐下一秒边界：iat 是秒级精度，详见 AuthRedisKeys.nextRevocationEpoch
            redis.opsForValue().set(AuthRedisKeys.USER_REVOKED_BEFORE_PREFIX + userId,
                    String.valueOf(AuthRedisKeys.nextRevocationEpoch()), USER_REVOCATION_TTL);
            log.info("已写入用户级 token 吊销纪元：userId={}", userId);
        } catch (Exception e) {
            // 不阻断禁用主流程：Redis 故障时在途 token 存活至自然过期（access ≤30min / refresh 30d，
            // refresh 侧仍有续签状态校验兜底），由对账告警发现
            log.error("用户级 token 吊销纪元写入失败（在途 token 将存活至自然过期）：userId={}", userId, e);
        }
    }

    /**
     * refresh token 吊销校验（续签入口）：jti 已拉黑，或签发时刻早于用户吊销纪元 → true。
     */
    public boolean isRefreshRevoked(Long userId, String jti, long issuedAtMs) {
        try {
            if (jti != null && Boolean.TRUE.equals(redis.hasKey(AuthRedisKeys.TOKEN_BL_JTI_PREFIX + jti))) {
                return true;
            }
            String epoch = redis.opsForValue().get(AuthRedisKeys.USER_REVOKED_BEFORE_PREFIX + userId);
            return epoch != null && issuedAtMs < Long.parseLong(epoch);
        } catch (Exception e) {
            // Redis 故障 fail-open：续签仍有签名/过期校验与「账号状态 fail-closed」双兜底
            log.warn("吊销校验 Redis 异常，按未吊销处理：userId={}, err={}", userId, e.getMessage());
            return false;
        }
    }

    private void revokeJtiQuietly(String token, String kind) {
        if (token == null || token.isBlank()) {
            return;
        }
        try {
            Claims claims = jwtTool.parse(token);
            String jti = claims.getId();
            Date expiration = claims.getExpiration();
            if (jti == null || expiration == null) {
                return;
            }
            long remaining = expiration.getTime() - System.currentTimeMillis();
            if (remaining <= 0) {
                return;
            }
            redis.opsForValue().set(AuthRedisKeys.TOKEN_BL_JTI_PREFIX + jti, "1",
                    Duration.ofMillis(remaining));
            log.info("登出已吊销 {} token：jti={}, 剩余 {}ms", kind, jti, remaining);
        } catch (Exception e) {
            // 过期/篡改/缺失的 token 无需吊销，静默跳过
        }
    }

    private void revokeCookieJtiQuietly(HttpServletRequest request, String cookieName) {
        revokeJtiQuietly(getCookieValue(request, cookieName), "refresh");
    }

    private String getCookieValue(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
