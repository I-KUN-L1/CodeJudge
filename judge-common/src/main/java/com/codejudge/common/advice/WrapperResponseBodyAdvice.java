package com.codejudge.common.advice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.codejudge.common.annotation.NoWrapper;
import com.codejudge.common.domain.R;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 统一响应包装：将 Controller 返回值包装为 R<T>。
 * 跳过：@NoWrapper 标注、返回类型已是 R、springdoc 文档路径。
 */
@RestControllerAdvice
public class WrapperResponseBodyAdvice implements ResponseBodyAdvice<Object> {

    private final ObjectMapper objectMapper;

    public WrapperResponseBodyAdvice(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        // 方法或类上标注 @NoWrapper 则不包装
        if (returnType.hasMethodAnnotation(NoWrapper.class)
                || returnType.getContainingClass().isAnnotationPresent(NoWrapper.class)) {
            return false;
        }
        // 返回类型已是 R 则不包装
        return !R.class.isAssignableFrom(returnType.getParameterType());
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        String path = request.getURI().getPath();
        // springdoc 文档路径：包装后会破坏 OpenAPI 规范解析，必须跳过
        if (path != null && (path.contains("api-docs") || path.contains("swagger") || path.contains("v3/api-docs"))) {
            return body;
        }
        /*
         * actuator 端点：必须跳过包装。
         *
         * 背景（P2 实测发现）：此前 /actuator/health 返回的是
         *   {"code":200,"msg":"OK","data":{"status":"UP",...}}
         * 而不是 actuator 的原生契约 {"status":"UP",...}。
         * 后果（都会在 P6 可观测性阶段集中爆发）：
         *   1. Docker/K8s 的 healthcheck 按 {"status":"UP"} 解析会直接判定不健康；
         *   2. Prometheus 抓取 /actuator/prometheus 拿到的是被包了一层的 JSON，
         *      文本 exposition 格式被破坏，指标全部采不到；
         *   3. Spring Boot Admin / 各类探针同样无法识别。
         * actuator 是面向「机器」的运维接口，不属于业务响应契约，不应套业务统一响应体。
         */
        if (path != null && path.startsWith("/actuator")) {
            return body;
        }
        if (body instanceof String) {
            try {
                return objectMapper.writeValueAsString(R.ok(body));
            } catch (Exception e) {
                return body;
            }
        }
        return R.ok(body);
    }
}
