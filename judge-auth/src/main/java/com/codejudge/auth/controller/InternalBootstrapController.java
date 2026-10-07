package com.codejudge.auth.controller;

import com.codejudge.auth.service.AdminBootstrapService;
import com.codejudge.common.annotation.NoWrapper;
import com.codejudge.common.utils.InternalOnlyGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内部引导接口（仅供服务间 Feign 调用；路径未在网关路由，外部流量到不了这里）。
 *
 * <p>为什么存在：演示账号的初始凭据记录在 judge-auth 管理的
 * {@code .bootstrap-credentials} 文件里（见 {@link AdminBootstrapService}），但演示账号
 * 走的是 judge-user 的普通改密端点（PUT /students/password），流量不经过 judge-auth。
 * 改密成功后由 judge-user 回调本端点，凭据文件随之自动删除。</p>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/bootstrap")
@Tag(name = "内部引导接口", description = "仅供服务间 Feign 调用，外部访问一律 403")
public class InternalBootstrapController {

    private final AdminBootstrapService adminBootstrapService;

    @NoWrapper
    @PostMapping("/credential-consumed")
    @Operation(summary = "tracked 账号改密成功回调", description = "手机号属于管理员/演示学生/演示教师时删除初始凭据文件；其余手机号不产生任何副作用")
    public void credentialConsumed(@RequestParam("phone") String phone) {
        InternalOnlyGuard.checkInternal();
        adminBootstrapService.consumeCredentialIfTracked(phone);
    }
}
