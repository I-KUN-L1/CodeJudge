package com.codejudge.user.controller;

import com.codejudge.common.annotation.RequireRole;
import com.codejudge.common.constants.UserRole;
import com.codejudge.common.domain.PageDTO;
import com.codejudge.common.domain.PageQuery;
import com.codejudge.common.domain.R;
import com.codejudge.user.domain.vo.UserVO;
import com.codejudge.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 员工管理
 */
@RestController
@RequestMapping("/staffs")
@RequiredArgsConstructor
@RequireRole(UserRole.STAFF)
public class StaffController {

    private final UserService userService;

    @GetMapping("/page")
    @Operation(summary = "员工（管理员）分页；支持姓名/用户名/手机号关键字")
    public R<PageDTO<UserVO>> page(PageQuery query, @RequestParam(required = false) String keyword) {
        return R.ok(userService.pageQueryUsers(1, query, keyword));
    }
}
