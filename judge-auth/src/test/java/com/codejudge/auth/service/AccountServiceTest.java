package com.codejudge.auth.service;

import com.codejudge.api.client.user.UserClient;
import com.codejudge.api.dto.user.LoginFormDTO;
import com.codejudge.api.dto.user.UserDTO;
import com.codejudge.auth.common.constants.JwtConstants;
import com.codejudge.auth.common.util.JwtTool;
import com.codejudge.auth.domain.vo.LoginResultVO;
import com.codejudge.auth.mapper.LoginRecordMapper;
import com.codejudge.common.exceptions.AccountDisabledException;
import com.codejudge.common.exceptions.UnauthorizedException;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 登录服务单元测试：重点覆盖 P0-1 修复——
 * 错误凭据、远程空身份对象均必须 401，禁止签发无身份 token
 */
@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    private JwtTool jwtTool;
    @Mock
    private UserClient userClient;
    @Mock
    private LoginRecordMapper loginRecordMapper;
    @Mock
    private TokenRevocationService tokenRevocation;
    @Mock
    private HttpServletRequest request;

    @InjectMocks
    private AccountService accountService;

    private LoginFormDTO form() {
        LoginFormDTO dto = new LoginFormDTO();
        dto.setCellPhone("13800000000");
        dto.setPassword("Any#Password123");
        return dto;
    }

    private void mockRequestIp() {
        // WebUtils.getClientIp 依次读取 X-Forwarded-For / X-Real-IP / remoteAddr
        lenient().when(request.getHeader(anyString())).thenReturn(null);
        lenient().when(request.getRemoteAddr()).thenReturn("127.0.0.1");
    }

    @Test
    void wrongCredentialsThrows401() {
        // 远程校验失败：judge-user 返回业务 401，经 RDecoder 转为 UnauthorizedException
        when(userClient.queryUserDetail(any(LoginFormDTO.class), anyBoolean()))
                .thenThrow(new UnauthorizedException("用户名或密码错误"));
        UnauthorizedException e = assertThrows(UnauthorizedException.class,
                () -> accountService.login(form(), true, request));
        assertEquals("用户名或密码错误", e.getMessage());
    }

    @Test
    void remoteFailureThrows401() {
        // 远程调用网络级失败（FeignException 等）同样 401
        when(userClient.queryUserDetail(any(LoginFormDTO.class), anyBoolean()))
                .thenThrow(new RuntimeException("connection refused"));
        assertThrows(UnauthorizedException.class, () -> accountService.login(form(), true, request));
    }

    @Test
    void emptyUserWithoutIdThrows401() {
        // P0-1 防御：远程返回"全字段为 null 的空对象"（历史缺陷中的空身份）必须 401
        when(userClient.queryUserDetail(any(LoginFormDTO.class), anyBoolean()))
                .thenReturn(new UserDTO());
        UnauthorizedException e = assertThrows(UnauthorizedException.class,
                () -> accountService.login(form(), true, request));
        assertEquals("用户名或密码错误", e.getMessage());
    }

    @Test
    void disabledUserThrowsAccountDisabled() {
        // 账号被禁用**不再是 401**：凭据错误才归 401（前端只提示"用户名或密码错误"），
        // 禁用需要明确处置指引，故单列业务码 423，前端据此弹"请联系管理员"专属弹窗
        // （前端在 judge-web/src/api/http.js 按该业务码分支处理）。
        UserDTO user = new UserDTO();
        user.setId(1L);
        user.setStatus(0);
        when(userClient.queryUserDetail(any(LoginFormDTO.class), anyBoolean())).thenReturn(user);
        AccountDisabledException e = assertThrows(AccountDisabledException.class,
                () -> accountService.login(form(), true, request));
        assertEquals("该账号已被禁用，请联系管理员", e.getMessage());
        assertEquals(423, e.getCode());
    }

    @Test
    void loginSuccessIssuesTokenWithIdentity() {
        mockRequestIp();
        UserDTO user = new UserDTO();
        user.setId(100L);
        user.setName("admin");
        user.setCellPhone("13800000000");
        user.setType(1);
        user.setStatus(1);
        when(userClient.queryUserDetail(any(LoginFormDTO.class), anyBoolean())).thenReturn(user);
        when(jwtTool.createAccessToken(anyLong(), anyInt(), anyLong())).thenReturn("access-token");
        when(jwtTool.createRefreshToken(anyLong(), anyLong())).thenReturn("refresh-token");

        LoginResultVO result = accountService.login(form(), true, request);

        assertEquals(100L, result.getUserId());
        assertEquals("access-token", result.getAccessToken());
        assertEquals("refresh-token", result.getRefreshToken());
        assertEquals(30 * 60L, result.getExpireTime());
    }

    /* ==================== 统一登录：角色由账号属性决定，不由调用方指定 ==================== */

    private UserDTO user(Integer type, Long id) {
        UserDTO u = new UserDTO();
        u.setId(id);
        u.setName("u" + id);
        u.setCellPhone("1390000" + id);
        u.setType(type);
        u.setStatus(1);
        return u;
    }

    private void stubIssue() {
        mockRequestIp();
        when(jwtTool.createAccessToken(anyLong(), anyInt(), anyLong())).thenReturn("access-token");
        when(jwtTool.createRefreshToken(anyLong(), anyLong())).thenReturn("refresh-token");
    }

    @Test
    void loginAutoAlwaysAsksRemoteForNonStaffOnly() {
        // 统一入口必须固定 staffOnly=false：前端没有"我是管理员"这个选择项，
        // 一旦把它变成参数，就等于允许客户端自选鉴权强度。
        stubIssue();
        when(userClient.queryUserDetail(any(LoginFormDTO.class), anyBoolean()))
                .thenReturn(user(1, 100L));

        accountService.loginAuto(form(), request);

        verify(userClient).queryUserDetail(any(LoginFormDTO.class), eq(false));
    }

    @Test
    void loginAutoReportsStudentRole() {
        stubIssue();
        when(userClient.queryUserDetail(any(LoginFormDTO.class), anyBoolean()))
                .thenReturn(user(2, 2001L));

        LoginResultVO result = accountService.loginAuto(form(), request);

        assertEquals(2, result.getRole(), "角色必须直接取自 user.type");
        assertEquals("学员", result.getRoleLabel(), "角色中文名由后端下发，前端不维护映射表");
        assertFalse(AccountService.isStaffEntry(result), "学员不能走管理端语义（refresh cookie 形态不同）");
    }

    @Test
    void loginAutoReportsStaffRoleAndAdminEntry() {
        stubIssue();
        when(userClient.queryUserDetail(any(LoginFormDTO.class), anyBoolean()))
                .thenReturn(user(1, 100L));

        LoginResultVO result = accountService.loginAuto(form(), request);

        assertEquals(1, result.getRole());
        assertEquals("管理员", result.getRoleLabel());
        assertTrue(AccountService.isStaffEntry(result), "员工应被判为管理端登录（签发管理端 refresh cookie）");
    }

    @Test
    void loginAutoReportsTeacherRole() {
        stubIssue();
        when(userClient.queryUserDetail(any(LoginFormDTO.class), anyBoolean()))
                .thenReturn(user(3, 3001L));

        LoginResultVO result = accountService.loginAuto(form(), request);

        assertEquals(3, result.getRole());
        assertEquals("教师", result.getRoleLabel());
        assertFalse(AccountService.isStaffEntry(result));
    }

    @Test
    void unknownUserTypeGetsNoRoleLabelButStillLogsIn() {
        // user.type 出现脏值：fail-closed 的是**能力**（Capabilities 给空集），
        // 而不是登录本身 —— 拒登会让脏数据变成"谁也进不去"，无从修数据。
        stubIssue();
        when(userClient.queryUserDetail(any(LoginFormDTO.class), anyBoolean()))
                .thenReturn(user(99, 4001L));

        LoginResultVO result = accountService.loginAuto(form(), request);

        assertEquals(99, result.getRole());
        assertNull(result.getRoleLabel(), "未知类型没有中文名，安全降级为 null 而非乱猜");
        assertFalse(AccountService.isStaffEntry(result), "未知类型绝不能被当成员工");
    }

    @Test
    void isStaffEntryIsNullSafe() {
        assertFalse(AccountService.isStaffEntry(null));
        assertFalse(AccountService.isStaffEntry(new LoginResultVO()));
        LoginResultVO weird = new LoginResultVO();
        weird.setRole(0);
        assertFalse(AccountService.isStaffEntry(weird));
    }

    @Test
    void compatAdminEntryRejectsNonStaffAccount() {
        // 兼容端点 /accounts/admin/login 的额外门槛：非员工账号即使口令正确也必须被拒。
        // 这里由远程以 staffOnly=true 再校验一次实现，故断言该参数确实传了下去。
        mockRequestIp();
        when(userClient.queryUserDetail(any(LoginFormDTO.class), anyBoolean()))
                .thenThrow(new UnauthorizedException("用户名或密码错误"));

        assertThrows(UnauthorizedException.class, () -> accountService.login(form(), true, request));

        verify(userClient).queryUserDetail(any(LoginFormDTO.class), eq(true));
    }

    @Test
    void compatAdminEntryAndUnifiedEntryIssueIdenticalTokens() {
        // 旧端点不得退化成一 套独立逻辑：两者必须签发同样的 token/cookie 形态
        stubIssue();
        when(userClient.queryUserDetail(any(LoginFormDTO.class), anyBoolean()))
                .thenReturn(user(1, 100L));

        LoginResultVO unified = accountService.loginAuto(form(), request);
        LoginResultVO compat = accountService.login(form(), true, request);

        assertEquals(unified.getUserId(), compat.getUserId());
        assertEquals(unified.getRole(), compat.getRole());
        assertEquals(unified.getAccessToken(), compat.getAccessToken());
        assertEquals(unified.getRefreshToken(), compat.getRefreshToken());
        assertEquals(AccountService.isStaffEntry(unified), AccountService.isStaffEntry(compat));
    }

    /* ==================== 刷新（refresh）：续签前必须校验账号状态与吊销 ==================== */

    /** 单次验签解析载荷：type/userId/jti/iat 全部来自同一份 Claims（与实现一致） */
    private void stubRefreshParse(long userId) {
        Claims claims = mock(Claims.class);
        when(claims.get("type")).thenReturn("refresh");
        when(claims.get(JwtConstants.PAYLOAD_USER_KEY)).thenReturn(userId);
        when(claims.getId()).thenReturn("jti-" + userId);
        when(claims.getIssuedAt()).thenReturn(new Date());
        when(jwtTool.parse("refresh-token")).thenReturn(claims);
        // 默认未吊销；吊销场景在各自用例里覆盖
        lenient().when(tokenRevocation.isRefreshRevoked(eq(userId), eq("jti-" + userId), anyLong()))
                .thenReturn(false);
    }

    @Test
    void refreshDisabledUserThrows423() {
        // 续签前必须校验账号状态：被禁用账号即使持有未过期的 refresh token，
        // 也不允许换取新 access token（否则「禁用」操作对其形同虚设）。
        // 与登录入口同语义：专属业务码 423，前端据此弹出"请联系管理员"。
        stubRefreshParse(100L);
        when(userClient.queryUserStatus(100L)).thenReturn(0);

        AccountDisabledException e = assertThrows(AccountDisabledException.class,
                () -> accountService.refresh("refresh-token"));
        assertEquals(423, e.getCode());
    }

    @Test
    void refreshDeletedUserThrows401() {
        // 用户已被删除（状态查不到）：refresh token 随之失效，fail-closed
        stubRefreshParse(404L);
        when(userClient.queryUserStatus(404L)).thenReturn(null);

        assertThrows(UnauthorizedException.class, () -> accountService.refresh("refresh-token"));
    }

    @Test
    void refreshFailsClosedWhenStatusQueryUnavailable() {
        // judge-user 不可用时拒绝续签（fail-closed）：续签是授予权威的动作，
        // 无法确认账号仍有效时宁可拒绝 —— 与角色查询的 fail-open（仅降级）刻意不对称
        stubRefreshParse(100L);
        when(userClient.queryUserStatus(100L)).thenThrow(new RuntimeException("connection refused"));

        assertThrows(UnauthorizedException.class, () -> accountService.refresh("refresh-token"));
    }

    @Test
    void refreshHappyPathIssuesAccessTokenWithRole() {
        // 正常续签：新 access token 携带 role claim，续签不重发 refresh token（cookie 保持）
        stubRefreshParse(3001L);
        when(userClient.queryUserStatus(3001L)).thenReturn(1);
        when(userClient.queryUserType(3001L)).thenReturn(3);
        when(jwtTool.createAccessToken(3001L, 3, 30 * 60 * 1000L)).thenReturn("new-access");

        LoginResultVO result = accountService.refresh("refresh-token");

        assertEquals(3001L, result.getUserId());
        assertEquals("new-access", result.getAccessToken());
        assertEquals(3, result.getRole());
        assertEquals("教师", result.getRoleLabel());
        assertEquals(30 * 60L, result.getExpireTime());
    }

    @Test
    void refreshRejectsAccessTokenAsRefreshToken() {
        // access token 不能拿来续签（防止 access/refresh 互换延长会话）
        Claims claims = mock(Claims.class);
        when(claims.get("type")).thenReturn("access");
        when(jwtTool.parse("access-token")).thenReturn(claims);

        assertThrows(UnauthorizedException.class, () -> accountService.refresh("access-token"));
    }

    @Test
    void refreshRejectedWhenTokenBlacklisted() {
        // 登出吊销的 jti 黑名单对续签路径同样生效：被盗的 refresh cookie
        // 即使尚未过期，登出后也不能再换新 access token
        stubRefreshParse(100L);
        when(tokenRevocation.isRefreshRevoked(eq(100L), eq("jti-100"), anyLong())).thenReturn(true);

        assertThrows(UnauthorizedException.class, () -> accountService.refresh("refresh-token"));
    }
}
