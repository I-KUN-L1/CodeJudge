package com.codejudge.submission.config;

import com.codejudge.common.ws.WsIdentityInterceptor;
import com.codejudge.submission.ws.SubmissionProgressWsHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 端点注册（判题进度）。
 *
 * <p>路径 {@code /ws/submissions/{submissionId}} 与竞赛榜的
 * {@code /ws/contests/{contestId}/rank} 是两个**独立服务**的端点，
 * 网关按前缀分流（更具体的 {@code /ws/contests/**} 必须先于 {@code /ws/**} 匹配）。
 *
 * <p><b>注意</b>：{@link SubmissionProgressWsHandler} 由 {@code @Component} 独立注册，
 * 此处只做端点绑定。切勿再添加 {@code @Bean} 工厂方法 —— 会与
 * {@code @RequiredArgsConstructor} 的构造注入形成循环依赖。
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class SubmissionWsConfig implements WebSocketConfigurer {

    private final SubmissionProgressWsHandler submissionProgressWsHandler;
    private final WsIdentityInterceptor wsIdentityInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(submissionProgressWsHandler, "/ws/submissions/*")
                .addInterceptors(wsIdentityInterceptor)
                .setAllowedOriginPatterns("*");
    }
}
