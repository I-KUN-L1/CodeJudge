package com.codejudge.contest.config;

import com.codejudge.common.ws.WsIdentityInterceptor;
import com.codejudge.contest.ws.ContestRankWsHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 端点注册（仅榜单订阅）。
 *
 * <p>路径设计 {@code /ws/contests/{contestId}/rank}：把「资源」编码进路径而非让客户端
 * 发订阅报文，网关只需按前缀分流（{@code /ws/contests/**} → 本服务），
 * 权限也就能在**握手阶段**一次性判定。
 *
 * <p>{@code setAllowedOriginPatterns("*")} 的取舍：跨域来源控制在网关完成
 * （judge-gateway 的 globalcors 白名单），本服务只对内网开放；若在此再配一份来源白名单，
 * 部署时会出现「网关放行、下游拒绝」的两处配置漂移。生产建议由网关 + 网络策略收口。
 *
 * <p><b>注意</b>：{@link ContestRankWsHandler} 由 {@code @Component} 独立注册，
 * 此处**只做端点绑定**。不要在此类里用 {@code @Bean} 工厂方法再造一遍 handler ——
 * 那会让本配置类的构造注入（{@code @RequiredArgsConstructor}）与工厂方法互相等待，
 * 形成 {@code contestWsConfig} 自引用的循环依赖，启动直接失败。
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class ContestWsConfig implements WebSocketConfigurer {

    private final ContestRankWsHandler contestRankWsHandler;
    private final WsIdentityInterceptor wsIdentityInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(contestRankWsHandler, "/ws/contests/*/rank")
                .addInterceptors(wsIdentityInterceptor)
                .setAllowedOriginPatterns("*");
    }
}
