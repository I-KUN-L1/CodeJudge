package com.codejudge.gateway.common;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 网关统一错误响应（重构：与下游 R 结构对齐，携带 requestId 便于链路追踪）。
 *
 * <p><b>包络码契约（BUG-003，网关侧半边）</b>：本类只处理「请求未达业务层」的失败 ——
 * 未登录/token 无效或已吊销（401）、无权限（403）、无路由（404）、限流（429）等。
 * 此类错误写<b>真实 HTTP 状态码 + 同值的包络 {@code code}</b>：没有业务语义可给，
 * 浏览器/监控/重试策略依赖标准 HTTP 语义。
 *
 * <p>与之相对，到达业务层后的业务失败（403 越权、404 不存在、423 禁用、400 参数…）
 * 由各服务以 <b>HTTP 200 + {@code body.code}</b> 返回（见 judge-common
 * {@code R} 的类注释）—— 那里 {@code body.code} 是唯一事实来源。两层的包络
 * JSON 结构一致（code/msg/requestId），客户端用同一套解析逻辑。
 */
public final class GatewayErrorResponse {

    private GatewayErrorResponse() {
    }

    public static Mono<Void> write(ServerWebExchange exchange, HttpStatus status, int code, String msg) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String requestId = UUID.randomUUID().toString().replace("-", "");
        response.getHeaders().set("requestId", requestId);
        String body = "{\"code\":" + code + ",\"msg\":\"" + escape(msg)
                + "\",\"requestId\":\"" + requestId + "\"}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
