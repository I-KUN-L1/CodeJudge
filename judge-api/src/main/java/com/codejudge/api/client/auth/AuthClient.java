package com.codejudge.api.client.auth;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 认证服务客户端
 * <p>历史的 {@code GET /jwks} 端点已随安全加固删除（HMAC 对称密钥没有公钥可分发，
 * 匿名返回密钥本体等于公开伪造身份能力），此处同步移除对应 Feign 方法 —— 全仓库无调用方。</p>
 *
 * <p>{@link #consumeBootstrapCredential}：tracked 账号（管理员/演示学生/演示教师）在
 * judge-user 侧改密成功后回调 judge-auth，删除 .bootstrap-credentials 凭据文件。
 * 调用方必须自行兜底（judge-auth 不可达不应影响改密主流程，见 UserService.changePassword）。</p>
 */
@FeignClient(value = "judge-auth", contextId = "authClient")
public interface AuthClient {

    /** tracked 账号改密成功回调；非 tracked 手机号无副作用，端点幂等 */
    @PostMapping("/internal/bootstrap/credential-consumed")
    void consumeBootstrapCredential(@RequestParam("phone") String phone);
}
