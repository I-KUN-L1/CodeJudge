package com.codejudge.user.service;

import com.codejudge.common.constants.UserRole;
import com.codejudge.common.exceptions.BadRequestException;
import com.codejudge.common.utils.UserContext;
import com.codejudge.user.domain.po.User;
import com.codejudge.user.domain.po.UserDetail;
import com.codejudge.user.domain.vo.UserVO;
import com.codejudge.user.mapper.UserDetailMapper;
import com.codejudge.user.mapper.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code GET /users/{id}} 视角裁剪单元测试（BUG-001 / QA-E06 修复）。
 *
 * <p>缺陷：原先任意登录学员可读取任意用户全量资料（cellPhone/email/detail），
 * 构成水平越权 PII 泄露。修复约定：
 * <ul>
 *   <li>本人 / STAFF(1) / TEACHER(3) / 服务间 Feign 直连（无 user-info 头）→ 全量；</li>
 *   <li>其余（学员查看他人、role 缺失 fail-closed）→ 公开视图，隐私字段不下发。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class UserServiceQueryByIdDesensitizeTest {

    private static final long SECRET_USER_ID = 100L;
    private static final long OTHER_USER_ID = 200L;
    private static final long MISSING_USER_ID = 999L;

    @Mock
    private UserMapper userMapper;
    @Mock
    private UserDetailMapper userDetailMapper;

    @InjectMocks
    private UserService userService;

    private User secretUser() {
        User user = new User();
        user.setId(SECRET_USER_ID);
        user.setCellPhone("13800000000");
        user.setEmail("secret@codejudge.local");
        user.setName("张三");
        user.setUsername("zhangsan");
        user.setType(2);
        user.setStatus(1);
        return user;
    }

    private UserDetail secretDetail() {
        UserDetail detail = new UserDetail();
        detail.setUserId(SECRET_USER_ID);
        return detail;
    }

    @BeforeEach
    void stubExistingUser() {
        lenient().when(userMapper.selectById(SECRET_USER_ID)).thenReturn(secretUser());
        lenient().when(userDetailMapper.selectOne(any()))
                .thenReturn(secretDetail());
    }

    @AfterEach
    void clearContext() {
        UserContext.remove();
    }

    @Test
    @DisplayName("学员查看他人资料 → 公开视图：cellPhone/email/detail 不下发")
    void studentViewingOtherStripsPii() {
        UserContext.setUser(OTHER_USER_ID);
        UserContext.setRole(UserRole.STUDENT.getCode());

        UserVO vo = userService.queryById(SECRET_USER_ID);

        assertNotNull(vo);
        assertEquals("张三", vo.getName());
        assertNull(vo.getCellPhone(), "手机号绝不能下发给非本人/非管理角色");
        assertNull(vo.getEmail(), "邮箱绝不能下发给非本人/非管理角色");
        assertNull(vo.getDetail(), "用户扩展信息绝不能下发给非本人/非管理角色");
        assertNull(vo.getType(), "目标用户角色类型不下发（防运营面探测）");
        verify(userDetailMapper, never()).selectOne(any());
    }

    @Test
    @DisplayName("本人查看自己 → 全量资料（/users/me 依赖该路径）")
    void selfGetsFullProfile() {
        UserContext.setUser(SECRET_USER_ID);
        UserContext.setRole(UserRole.STUDENT.getCode());

        UserVO vo = userService.queryById(SECRET_USER_ID);

        assertEquals("13800000000", vo.getCellPhone());
        assertNotNull(vo.getDetail());
    }

    @Test
    @DisplayName("STAFF 查看他人 → 全量资料")
    void staffViewingOtherGetsFullProfile() {
        UserContext.setUser(OTHER_USER_ID);
        UserContext.setRole(UserRole.STAFF.getCode());

        UserVO vo = userService.queryById(SECRET_USER_ID);

        assertEquals("13800000000", vo.getCellPhone());
        assertNotNull(vo.getDetail());
    }

    @Test
    @DisplayName("TEACHER 查看他人 → 全量资料（与 /users/page 允许教师一致）")
    void teacherViewingOtherGetsFullProfile() {
        UserContext.setUser(OTHER_USER_ID);
        UserContext.setRole(UserRole.TEACHER.getCode());

        UserVO vo = userService.queryById(SECRET_USER_ID);

        assertEquals("13800000000", vo.getCellPhone());
        assertNotNull(vo.getDetail());
    }

    @Test
    @DisplayName("服务间 Feign 直连（无 user-info 头）→ 全量资料")
    void internalCallWithoutContextGetsFullProfile() {
        // 不设置 UserContext：模拟 judge-ai 等内部调用直连（网关流量必带 user-info 头）
        UserVO vo = userService.queryById(SECRET_USER_ID);

        assertEquals("13800000000", vo.getCellPhone());
        assertNotNull(vo.getDetail());
    }

    @Test
    @DisplayName("role 缺失（网关未透传）查看他人 → fail-closed 公开视图")
    void missingRoleFailsClosedToPublicView() {
        UserContext.setUser(OTHER_USER_ID);
        // 故意不设置 role

        UserVO vo = userService.queryById(SECRET_USER_ID);

        assertNull(vo.getCellPhone());
        assertNull(vo.getDetail());
    }

    @Test
    @DisplayName("用户不存在 → 400 业务拒绝")
    void missingUserThrowsBadRequest() {
        UserContext.setUser(OTHER_USER_ID);
        UserContext.setRole(UserRole.STAFF.getCode());
        when(userMapper.selectById(MISSING_USER_ID)).thenReturn(null);

        BadRequestException e = assertThrows(BadRequestException.class,
                () -> userService.queryById(MISSING_USER_ID));
        assertEquals("用户不存在", e.getMessage());
    }
}
