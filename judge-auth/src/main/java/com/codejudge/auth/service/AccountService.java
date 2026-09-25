package com.codejudge.auth.service;

import com.codejudge.api.client.user.UserClient;
import com.codejudge.api.dto.user.LoginFormDTO;
import com.codejudge.api.dto.user.UserDTO;
import com.codejudge.auth.common.util.JwtTool;
import com.codejudge.auth.domain.po.LoginRecord;
import com.codejudge.auth.domain.vo.LoginResultVO;
import com.codejudge.auth.mapper.LoginRecordMapper;
import com.codejudge.common.constants.UserRole;
import com.codejudge.common.exceptions.AccountDisabledException;
import com.codejudge.common.exceptions.BadRequestException;
import com.codejudge.common.exceptions.UnauthorizedException;
import com.codejudge.common.utils.StringUtils;
import com.codejudge.common.utils.WebUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 账号服务：登录、登出、刷新
 *
 * <h3>登录入口的演进（本次收敛）</h3>
 * 历史实现有<b>两个</b>登录入口：{@code /accounts/login}（学员/教师）与
 * {@code /accounts/admin/login}（管理员），前端还要用户先手动选「我是什么角色」。
 * 那是有害的：
 * <ul>
 *   <li>角色是账号的<b>属性</b>，不是用户的<b>选择</b>。「选错了」既没有正当语义，</li>
 *   <li>又会把「哪个入口签发哪个 refresh cookie」这种实现细节泄漏到 UI（前端得知道
 *       {@code mode === 'admin'} 该走哪个端点）；</li>
 *   <li>更实际的是：网关的登录限流谓词是 {@code Path=/accounts/login} + {@code Method=POST}，
 *       于是 {@code /accounts/admin/login} <b>完全不受爆破限流保护</b> —— 一个安全缺口。</li>
 * </ul>
 * 现在统一为 {@link #loginAuto}：账号密码校验通过后，<b>由账号自身的 {@code user.type}</b>
 * 决定角色、登录类型与 refresh cookie 形态。前端只发一次请求，不需要知道任何角色分支。
 * 旧端点保留为兼容别名（{@link #login}），委托同一条逻辑，仅额外要求账号为员工。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountService {

    private static final long ACCESS_TOKEN_TTL = 30 * 60 * 1000L;
    private static final long REFRESH_TOKEN_TTL = 30L * 24 * 60 * 60 * 1000L;

    /** {@code login_record.login_type}：用户端 */
    private static final int LOGIN_TYPE_USER = 1;
    /** {@code login_record.login_type}：管理端（按"账号是员工"判定，而非"走了哪个端点"） */
    private static final int LOGIN_TYPE_ADMIN = 2;

    private final JwtTool jwtTool;
    private final UserClient userClient;
    private final LoginRecordMapper loginRecordMapper;

    /**
     * 统一登录（唯一入口）：按账号自身属性自动判定角色，无需前端指定。
     * <p>员工账号自动走管理端语义（管理端 refresh cookie + 登录类型 2），
     * 学员/教师走用户端语义 —— 判定依据只有 {@code user.type} 一个。
     */
    public LoginResultVO loginAuto(LoginFormDTO loginFormDTO, HttpServletRequest request) {
        UserDTO user = authenticate(loginFormDTO, false);
        return issue(user, request);
    }

    /**
     * 兼容旧的分角色入口。
     * <p>本方法不是「另一条登录链路」，只是 {@link #loginAuto} 加了一道可选的角色门槛，
     * 供兼容端点 {@code POST /accounts/admin/login} 使用（该端点已标注 {@code @Deprecated}）。
     * 保留方法而不保留方法体的分枝，是为了让「旧端点不会退化成一套独立逻辑」这件事
     * 由编译期保证 —— 两条路必然签发同样的 token 与 cookie。
     *
     * @param isAdmin 为 {@code true} 时额外要求该账号是员工（沿用底座语义，
     *                避免"学员从管理端入口登录成功"这种越权观感）；为 {@code false} 时
     *                行为与 {@link #loginAuto} 完全一致。
     */
    public LoginResultVO login(LoginFormDTO loginFormDTO, boolean isAdmin, HttpServletRequest request) {
        UserDTO user = authenticate(loginFormDTO, isAdmin);
        return issue(user, request);
    }

    /**
     * 凭据校验（经 judge-user 的 Feign 接口，BCrypt 在那边比对）。
     *
     * @param staffOnly 是否额外要求账号为员工（{@code user.type == 1}）
     */
    private UserDTO authenticate(LoginFormDTO loginFormDTO, boolean staffOnly) {
        if (loginFormDTO == null
                || StringUtils.isBlank(loginFormDTO.getCellPhone())
                || StringUtils.isBlank(loginFormDTO.getPassword())) {
            throw new BadRequestException("手机号或密码不能为空");
        }
        // 调用用户服务校验账号密码
        UserDTO user;
        try {
            user = userClient.queryUserDetail(loginFormDTO, staffOnly);
        } catch (Exception e) {
            throw new UnauthorizedException("用户名或密码错误");
        }
        // 防御：远程返回"无身份的空对象"（id 为 null）视为凭据错误，杜绝签发空身份 token
        if (user == null || user.getId() == null) {
            throw new UnauthorizedException("用户名或密码错误");
        }
        if (user.getStatus() != null && user.getStatus() != 1) {
            // 专属业务码 423：前端据此弹出"请联系管理员"提示弹窗，而不是泛化的登录失败
            throw new AccountDisabledException("该账号已被禁用，请联系管理员");
        }
        return user;
    }

    /**
     * 签发双 Token 并记登录流水。角色相关的分支全部收在这里 ——
     * 调用方（Controller）不再需要知道"这次是管理端还是用户端"。
     */
    private LoginResultVO issue(UserDTO user, HttpServletRequest request) {
        UserRole role = UserRole.of(user.getType());

        LoginResultVO result = new LoginResultVO();
        result.setUserId(user.getId());
        result.setUsername(user.getName());
        result.setRole(user.getType());
        result.setRoleLabel(role == null ? null : role.getLabel());
        // user.type（1员工/2学员/3教师）写入 token role claim，供网关透传做接口级权限校验
        result.setAccessToken(jwtTool.createAccessToken(user.getId(), user.getType(), ACCESS_TOKEN_TTL));
        result.setRefreshToken(jwtTool.createRefreshToken(user.getId(), REFRESH_TOKEN_TTL));
        result.setExpireTime(ACCESS_TOKEN_TTL / 1000);

        int loginType = role == UserRole.STAFF ? LOGIN_TYPE_ADMIN : LOGIN_TYPE_USER;
        try {
            LoginRecord record = new LoginRecord();
            record.setUserId(user.getId());
            record.setCellPhone(user.getCellPhone());
            record.setIpv4(WebUtils.getClientIp(request));
            record.setLoginType(loginType);
            record.setLoginTime(LocalDateTime.now());
            loginRecordMapper.insert(record);
        } catch (Exception e) {
            log.warn("登录日志写入失败：userId={}", user.getId(), e);
        }
        return result;
    }

    /**
     * 当前账号是否应使用「管理端」refresh cookie。
     * <p>由登录结果里的角色决定，而不是由"调了哪个端点"决定 ——
     * 这样兼容端点与统一端点签发出来的 cookie 形态完全一致，
     * 用户从任一入口登录后，{@code /accounts/refresh} 都读得到。
     */
    public static boolean isStaffEntry(LoginResultVO result) {
        return result != null && UserRole.of(result.getRole()) == UserRole.STAFF;
    }

    public LoginResultVO refresh(String refreshToken) {
        if (StringUtils.isBlank(refreshToken)) {
            throw new UnauthorizedException("登录已过期，请重新登录");
        }
        try {
            Long userId = jwtTool.parseUserId(refreshToken);
            // 类型校验：refresh 端点只接受 refresh token，access token 不得用来续签
            if (!"refresh".equals(jwtTool.parseTokenType(refreshToken))) {
                throw new UnauthorizedException("登录已过期，请重新登录");
            }
            LoginResultVO result = new LoginResultVO();
            result.setUserId(userId);
            // 刷新时补查用户类型，保证续签 token 仍携带 role claim（查询失败不阻断续签，仅降级为无角色）
            Integer role = null;
            try {
                role = userClient.queryUserType(userId);
            } catch (Exception e) {
                log.warn("刷新 token 查询用户角色失败：userId={}, err={}", userId, e.getMessage());
            }
            result.setAccessToken(jwtTool.createAccessToken(userId, role, ACCESS_TOKEN_TTL));
            result.setRole(role);
            UserRole r = UserRole.of(role);
            result.setRoleLabel(r == null ? null : r.getLabel());
            result.setExpireTime(ACCESS_TOKEN_TTL / 1000);
            return result;
        } catch (Exception e) {
            throw new UnauthorizedException("登录已过期，请重新登录");
        }
    }
}
