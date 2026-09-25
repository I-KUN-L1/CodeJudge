package com.codejudge.user.controller;

import com.codejudge.common.annotation.RequireRole;
import com.codejudge.common.constants.UserRole;
import com.codejudge.common.domain.PageDTO;
import com.codejudge.common.domain.PageQuery;
import com.codejudge.common.domain.R;
import com.codejudge.common.exceptions.BadRequestException;
import com.codejudge.common.utils.StringUtils;
import com.codejudge.user.domain.dto.UserFormDTO;
import com.codejudge.user.domain.vo.UserVO;
import com.codejudge.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 教师管理
 */
@RestController
@RequestMapping("/teachers")
@RequiredArgsConstructor
public class TeacherController {

    private final UserService userService;

    /**
     * 教师分页查询（员工专用）。
     */
    @GetMapping("/page")
    @RequireRole(UserRole.STAFF)
    @Operation(summary = "教师分页（管理员；支持姓名/用户名/手机号关键字）")
    public R<PageDTO<UserVO>> page(PageQuery query, @RequestParam(required = false) String keyword) {
        return R.ok(userService.pageQueryUsers(3, query, keyword));
    }

    /**
     * 开通教师账号（仅员工/管理员）。
     * <p>
     * 与 zx-learn 底座的差异（安全加固）：底座此处是「教师自助注册」且位于网关白名单，
     * 任何人都能不登录就把自己注册成教师 —— 而教师可创建题目、查看隐藏测试用例，
     * 在判题平台里等于公开题库存取权。CodeJudge 改为仅员工可开通，
     * 同时把 {@code /teachers/register} 移出网关白名单（见 {@code JwtProperties}）。
     * 学员自助注册不受影响（{@code /students/register} 仍匿名放行）。
     */
    @PostMapping("/register")
    @RequireRole(UserRole.STAFF)
    public R<Void> register(@RequestBody UserFormDTO form) {
        validateRegister(form);
        // 角色由后端强制指定为教师(3)，忽略客户端传入的 type，防止越权注册管理员
        form.setType(3);
        userService.saveUser(form);
        return R.ok();
    }

    /**
     * 注册参数校验：手机号格式、密码强度（与学员注册保持一致）。
     */
    private void validateRegister(UserFormDTO form) {
        if (StringUtils.isBlank(form.getCellPhone()) || StringUtils.isBlank(form.getPassword())) {
            throw new BadRequestException("手机号或密码不能为空");
        }
        if (!form.getCellPhone().matches("^1\\d{10}$")) {
            throw new BadRequestException("手机号格式不正确");
        }
        if (form.getPassword().length() < 6) {
            throw new BadRequestException("密码至少 6 位");
        }
    }
}
