package com.codejudge.api.client.auth;

import org.springframework.cloud.openfeign.FeignClient;

/**
 * 认证服务客户端
 * <p>历史的 {@code GET /jwks} 端点已随安全加固删除（HMAC 对称密钥没有公钥可分发，
 * 匿名返回密钥本体等于公开伪造身份能力），此处同步移除对应 Feign 方法 —— 全仓库无调用方。
 */
@FeignClient(value = "judge-auth", contextId = "authClient")
public interface AuthClient {
}
