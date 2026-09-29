package com.codejudge.auth.controller;

import com.codejudge.api.dto.user.LoginFormDTO;
import com.codejudge.auth.common.constants.JwtConstants;
import com.codejudge.auth.domain.dto.FirstChangePasswordDTO;
import com.codejudge.auth.domain.vo.CapabilityProfileVO;
import com.codejudge.auth.domain.vo.LoginResultVO;
import com.codejudge.auth.service.AccountService;
import com.codejudge.auth.service.AdminBootstrapService;
import com.codejudge.auth.service.CapabilityService;
import com.codejudge.auth.service.TokenRevocationService;
import com.codejudge.common.domain.R;
import com.codejudge.common.utils.CookieBuilder;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 账号管理
 */
@RestController
@RequestMapping("/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;
    private final AdminBootstrapService adminBootstrapService;
    private final CapabilityService capabilityService;
    private final TokenRevocationService tokenRevocationService;

    /**
     * 统一登录入口 —— <b>前端唯一使用的登录端点</b>。
     * <p>请求体只有手机号与密码；角色由后端按账号自身属性（{@code user.type}）判定，
     * 员工自动走管理端语义（管理端 refresh cookie、登录类型 2），学员/教师走用户端语义。
     * 前端不再需要「请选择你的身份」这类选择器。
     */
    @PostMapping("/login")
    @Operation(summary = "登录（统一入口，角色由账号属性自动判定）")
    public R<LoginResultVO> login(@RequestBody LoginFormDTO loginFormDTO,
                                 HttpServletRequest request, HttpServletResponse response) {
        LoginResultVO result = accountService.loginAuto(loginFormDTO, request);
        attachBootstrapHint(result);
        writeRefreshCookie(result, response);
        return R.ok(result);
    }

    /**
     * 兼容旧的管理端登录入口。
     *
     * @deprecated 前端不再使用（已改为 {@link #login} 单入口）。保留是为了不打断既有验收脚本
     *         （{@code scripts/verify-p1-login.py}）与可能的外部调用方。新代码请用 {@code POST /accounts/login}。
     */
    @Deprecated
    @PostMapping("/admin/login")
    @Operation(summary = "管理端登录（已废弃，请改用 /accounts/login）", deprecated = true)
    public R<LoginResultVO> adminLogin(@RequestBody LoginFormDTO loginFormDTO,
                                       HttpServletRequest request, HttpServletResponse response) {
        LoginResultVO result = accountService.login(loginFormDTO, true, request);
        attachBootstrapHint(result);
        writeRefreshCookie(result, response);
        return R.ok(result);
    }

    /**
     * 当前账号的能力画像：可见导航项 + 已授权能码 + 落地路由。
     *
     * <p>这是「前端不参与鉴权」的支点：前端拿到的是一份**结果**（能做哪些事），
     * 而不是**规则**（角色 1 能做什么）。因此角色语义调整、新增能力码，都只改后端。
     */
    @GetMapping("/me/capabilities")
    @Operation(summary = "当前账号的能力画像（导航项 + 能力码 + 落地路由）")
    public R<CapabilityProfileVO> myCapabilities() {
        return R.ok(capabilityService.currentProfile());
    }

    @PostMapping("/password/first-change")
    @Operation(summary = "首次登录修改初始密码")
    public R<Void> firstChangePassword(@RequestBody FirstChangePasswordDTO dto) {
        // 由 judge-user 校验原密码（BCrypt）并落库；成功后 judge-auth 删除 .bootstrap-credentials 凭据文件
        adminBootstrapService.changeBootstrapPassword(dto.getCellPhone(), dto.getOldPassword(), dto.getNewPassword());
        return R.ok();
    }

    @PostMapping("/logout")
    @Operation(summary = "退出登录（吊销本次会话的全部 token）")
    public R<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        // 吊销先行：把本次会话的 access/refresh jti 写入 Redis 黑名单（TTL=剩余寿命），
        // 即使 token/cookie 已被复制也随即失效；网关与续签入口都会校验黑名单。
        // 缺失/过期/伪造的 token 在此静默跳过 —— 登出永远成功。
        tokenRevocationService.revokeRequestTokens(request);
        CookieBuilder.newBuilder(JwtConstants.JWT_REFRESH_COOKIE_KEY).value("").maxAge(0).write(response);
        CookieBuilder.newBuilder(JwtConstants.JWT_ADMIN_REFRESH_COOKIE_KEY).value("").maxAge(0).write(response);
        return R.ok();
    }

    /**
     * 刷新 token。
     * <p>
     * 同时接受 GET 与 POST：底座实现为 GET（刷新令牌走 HttpOnly Cookie，从 Cookie 读取，
     * 无需请求体）；CodeJudge 的接口规范（docs/API-REFERENCE.md）声明为 POST，
     * 故这里同时放行两种方法，避免前端/调用方因方法不匹配拿到 405。
     */
    @RequestMapping(value = "/refresh", method = {RequestMethod.GET, RequestMethod.POST})
    @Operation(summary = "刷新 token")
    public R<LoginResultVO> refresh(HttpServletRequest request) {
        String refreshToken = getCookieValue(request, JwtConstants.JWT_REFRESH_COOKIE_KEY);
        if (refreshToken == null) {
            refreshToken = getCookieValue(request, JwtConstants.JWT_ADMIN_REFRESH_COOKIE_KEY);
        }
        return R.ok(accountService.refresh(refreshToken));
    }

    /**
     * 写入 refresh cookie，并**清掉另一种**。
     *
     * <p>为什么要清：历史上用户端与管理端各有一枚 refresh cookie。收敛为单入口后，
     * 若同一浏览器先以学员登录、再以管理员登录，两枚 cookie 会并存；而
     * {@link #refresh} 的读取顺序是「先普通、后管理」—— 于是管理员的续签会拿到那枚
     * <b>过期的学员 cookie</b>，表现为「刚登录没多久就被踢下线」。
     * 登录即互斥，一个浏览器同一时刻只应有一个身份。
     */
    private void writeRefreshCookie(LoginResultVO result, HttpServletResponse response) {
        boolean staff = AccountService.isStaffEntry(result);
        String keep = staff ? JwtConstants.JWT_ADMIN_REFRESH_COOKIE_KEY : JwtConstants.JWT_REFRESH_COOKIE_KEY;
        String drop = staff ? JwtConstants.JWT_REFRESH_COOKIE_KEY : JwtConstants.JWT_ADMIN_REFRESH_COOKIE_KEY;

        CookieBuilder.newBuilder(keep)
                .value(result.getRefreshToken())
                .path("/")
                .maxAge(30 * 24 * 60 * 60)
                .httpOnly(true)
                .sameSite("Lax")
                .write(response);
        CookieBuilder.newBuilder(drop).value("").path("/").maxAge(0).httpOnly(true).write(response);
    }

    /**
     * 引导期提示：首个管理员的初始凭据文件还在 ⇒ 提醒改密。
     * <p>只暴露一个布尔，不含任何凭据内容。判据见 {@link AdminBootstrapService#isBootstrapPending()}。
     */
    private void attachBootstrapHint(LoginResultVO result) {
        result.setMustChangePassword(AccountService.isStaffEntry(result) && adminBootstrapService.isBootstrapPending());
    }

    private String getCookieValue(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (name.equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }
}
