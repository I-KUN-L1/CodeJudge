package com.codejudge.user.controller;

import com.codejudge.common.domain.R;
import com.codejudge.user.domain.dto.UserFormDTO;
import com.codejudge.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

/**
 * 管理端建号策略测试：POST /users 只产出教师（type=3）。
 * <p>角色策略的三条边界里，本端点守住第二条：
 * <ul>
 *   <li>管理员 —— 无在线创建路径（仅 AdminBootstrapRunner 引导 / DBA 脚本，
 *       见 {@code AdminBootstrapServiceTest}）；</li>
 *   <li><b>教师 —— 本端点唯一合法产物，后端强制覆盖客户端传入的 type；</b></li>
 *   <li>学员 —— 仅 {@code POST /students/register} 自助注册（StudentController 已强制 type=2）。</li>
 * </ul>
 * 前端 UserAdminView 只提供教师选项，是同规的 UI 面；此处防的是绕过前端直接调接口。
 */
@ExtendWith(MockitoExtension.class)
class UserControllerAddUserTest {

    @Mock
    private UserService userService;

    @Test
    void addUserForcesTeacherRoleRegardlessOfClientType() {
        UserController ctrl = new UserController(userService, null);

        // 客户端（哪怕持有管理员凭据）尝试越权创建管理员/学员，均被强制改写为教师
        for (Integer attempted : new Integer[]{1, 2, 3, null, 99}) {
            UserFormDTO form = new UserFormDTO();
            form.setCellPhone("13900000999");
            form.setName("越权尝试");
            form.setPassword("123456");
            form.setType(attempted);

            R<Void> resp = ctrl.addUser(form);

            assertTrue(resp.getCode() == 200 || resp.getCode() == 0,
                    "code=" + resp.getCode());
            ArgumentCaptor<UserFormDTO> captor = ArgumentCaptor.forClass(UserFormDTO.class);
            verify(userService, org.mockito.Mockito.times(1)).saveUser(captor.capture());
            captor.getAllValues().forEach(f ->
                    assertEquals(3, f.getType(), "客户端传入 type=" + attempted + " 也必须落为教师(3)"));
            org.mockito.Mockito.clearInvocations(userService);
        }
    }
}
