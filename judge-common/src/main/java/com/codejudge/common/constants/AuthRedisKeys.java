package com.codejudge.common.constants;

/**
 * 认证域 Redis Key 全局约定（judge-auth / judge-user 共用；网关侧有一份对齐的本地常量 ——
 * judge-gateway 独立于 judge-common，见 {@code AuthGlobalFilter} 注释）。
 *
 * <p>背景：JWT 无状态签发、签出无法收回。登出/禁用要「即时生效」就需要一个
 * 请求方可查的吊销状态存储，两个 key 各管一件事：
 * <ol>
 *   <li><b>jti 黑名单</b> —— 精确吊销单个会话（登出）；</li>
 *   <li><b>用户级吊销纪元</b> —— 一次杀掉某用户全部在途 token（禁用）。</li>
 * </ol>
 * 写入方：judge-auth（登出）、judge-user（禁用账号）。
 * 校验方：judge-gateway（每个 access token）、judge-auth 续签入口（refresh token）。
 */
public interface AuthRedisKeys {

    /**
     * token jti 黑名单前缀：{@code judge:auth:bl:jti:{jti}}（String "1"，
     * TTL = token 剩余寿命 —— 过期 token 无需拉黑，自然失效）。
     * 登出时把本次会话 access/refresh token 的 jti 写入。
     */
    String TOKEN_BL_JTI_PREFIX = "judge:auth:bl:jti:";

    /**
     * 用户级吊销纪元前缀：{@code judge:auth:revoked-before:{userId}} = 吊销时刻毫秒时间戳
     * （String，TTL = refresh token 最长寿命 30 天 —— 必须覆盖「纪元写入时刻之前签发的
     * 最后一枚 refresh token」的剩余寿命，否则纪元过期后旧 refresh token 复活）。
     * 管理员禁用账号时写入；iat（签发时刻）早于纪元的 token 一律拒绝。
     */
    String USER_REVOKED_BEFORE_PREFIX = "judge:auth:revoked-before:";

    /**
     * 计算禁用时刻的吊销纪元值：对齐到<b>下一秒边界</b>的毫秒时间戳。
     *
     * <p>为什么不是"当前毫秒"：JWT 的 {@code iat} 只有<b>秒级精度</b>（RFC 7519 NumericDate），
     * 比较时统一换算为「秒起点毫秒」。若纪元记当前毫秒，则与禁用落在<b>同一秒内</b>签发的
     * 新 token 满足 {@code iat(秒起点) < 纪元(毫秒)} 而被误杀 —— 同秒重登录永远失败。
     * 对齐到下一秒边界后：
     * <ul>
     *   <li>禁用当秒及之前签发的 token：iat ≤ 禁用秒 &lt; 纪元 → 吊销（零宽恕窗口）；</li>
     *   <li>禁用之后签发的 token：iat ≥ 纪元 → 正常（同秒重登录最多延迟 1 秒重试）。</li>
     * </ul>
     * 写入方（judge-auth / judge-user）必须统一走本方法，保证语义一致。
     */
    static long nextRevocationEpoch() {
        return (System.currentTimeMillis() / 1000 + 1) * 1000;
    }
}
