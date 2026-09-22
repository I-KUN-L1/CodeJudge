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
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 学员管理
 */
@RestController
@RequestMapping("/students")
@RequiredArgsConstructor
public class StudentController {

    private final UserService userService;

    @GetMapping("/page")
    @RequireRole(UserRole.STAFF)
    @Operation(summary = "学员分页（管理员；支持姓名/用户名/手机号关键字）")
    public R<PageDTO<UserVO>> page(PageQuery query, @RequestParam(required = false) String keyword) {
        return R.ok(userService.pageQueryUsers(2, query, keyword));
    }

    @PostMapping("/register")
    public R<Void> register(@RequestBody UserFormDTO form) {
        validateRegister(form);
        // 角色由后端强制指定为学员(2)，忽略客户端传入的 type，防止越权注册管理员
        form.setType(2);
        userService.saveUser(form);
        return R.ok();
    }

    /**
     * 注册参数校验：手机号格式、密码强度（与前端规则一致）。
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

    @PutMapping("/password")
    public R<Void> changePassword(@RequestBody Map<String, String> body) {
        userService.changePassword(body.get("oldPassword"), body.get("newPassword"));
        return R.ok();
    }
}
