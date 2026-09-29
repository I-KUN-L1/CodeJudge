package com.codejudge.user.service;

import com.codejudge.common.exceptions.BadRequestException;
import com.codejudge.user.domain.dto.UserFormDTO;
import com.codejudge.user.domain.po.User;
import com.codejudge.user.mapper.UserDetailMapper;
import com.codejudge.user.mapper.UserMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 展示型字段（name/username）白名单校验测试（QA-B06 修复）。
 *
 * <p>缺陷：注册名 {@code <script>alert(1)</script>} 曾原样入库并经 /users/me、
 * /users/page 等接口回显（存储型 XSS，前端 Vue 转义是唯一兜底）。
 * 修复：saveUser / updateUser / updateProfile 三条写路径统一在服务层入口
 * 按白名单拒绝（中英文、数字、· . _ - 空格，≤32 字符）。
 */
@ExtendWith(MockitoExtension.class)
class UserServiceDisplayNameValidationTest {

    @Mock
    private UserMapper userMapper;
    @Mock
    private UserDetailMapper userDetailMapper;

    @InjectMocks
    private UserService userService;

    private UserFormDTO form(String name, String username) {
        UserFormDTO form = new UserFormDTO();
        form.setCellPhone("13900000099");
        form.setPassword("123456");
        form.setName(name);
        form.setUsername(username);
        return form;
    }

    @Test
    @DisplayName("saveUser：name 带 script 载荷 → 400，绝不入库")
    void saveUserRejectsScriptName() {
        BadRequestException e = assertThrows(BadRequestException.class,
                () -> userService.saveUser(form("<script>alert(1)</script>", "qa_user")));
        assertTrue(e.getMessage().contains("姓名"));
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    @DisplayName("saveUser：username 带 img/svg 载荷 → 400")
    void saveUserRejectsXssUsername() {
        assertThrows(BadRequestException.class,
                () -> userService.saveUser(form("张三", "tom\"><svg onload=alert(1)>")));
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    @DisplayName("saveUser：32 字符边界放行，33 字符拒绝")
    void saveUserEnforcesLengthBoundary() {
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(userMapper.insert(any(User.class))).thenReturn(1);

        // 32 个合法字符：放行
        userService.saveUser(form("张".repeat(32), "user32"));

        // 33 个合法字符：拒绝
        assertThrows(BadRequestException.class,
                () -> userService.saveUser(form("张".repeat(33), "user33")));
    }

    @Test
    @DisplayName("updateUser：载荷 name → 400（校验先于查询），绝不落库")
    void updateUserRejectsScriptName() {
        assertThrows(BadRequestException.class,
                () -> userService.updateUser(100L, form("<img src=x onerror=alert(1)>", null)));
        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    @DisplayName("updateProfile：学员自助改名的载荷 → 400（校验先于查询）")
    void updateProfileRejectsScriptName() {
        assertThrows(BadRequestException.class,
                () -> userService.updateProfile(200L, form("<script>alert(1)</script>", null)));
        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    @DisplayName("合法姓名放行：中英文、数字、· . _ - 空格")
    void legitimateNamesPassThrough() {
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(userMapper.insert(any(User.class))).thenReturn(1);

        for (String ok : new String[]{"张三", "Abdullah · Al-Sayed", "QA学员_01.zhang-李四", "O'Brien 探月 3 组"}) {
            // O'Brien 含非常规符号 → 白名单外，应被拒；其余放行
            if (ok.equals("O'Brien 探月 3 组")) {
                assertThrows(BadRequestException.class, () -> userService.saveUser(form(ok, "u-ok")),
                        "撇号不在白名单内，应拒绝");
            } else {
                userService.saveUser(form(ok, "u-ok"));
            }
        }
        verify(userMapper, org.mockito.Mockito.times(3)).insert(any(User.class));
    }
}
