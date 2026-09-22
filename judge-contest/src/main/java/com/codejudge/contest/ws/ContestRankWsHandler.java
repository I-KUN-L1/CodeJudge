package com.codejudge.contest.ws;

import com.codejudge.common.constants.UserRole;
import com.codejudge.common.ws.WsEnvelope;
import com.codejudge.common.ws.WsIdentityInterceptor;
import com.codejudge.common.ws.WsMessageType;
import com.codejudge.common.ws.WsSessionRegistry;
import com.codejudge.contest.domain.po.Contest;
import com.codejudge.contest.domain.vo.ContestRankVO;
import com.codejudge.contest.mapper.ContestMapper;
import com.codejudge.contest.service.ContestRankService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 竞赛榜单 WebSocket 端点：{@code /ws/contests/{contestId}/rank}。
 *
 * <p><b>订阅模型</b>：连接即订阅，无需客户端再发 SUBSCRIBE —— 路径里已经包含了
 * 「看哪个竞赛」，再要求一轮订阅握手只会多一个失败点与一段时序（订阅前收到的推送该丢还是该缓？）。
 *
 * <p><b>封榜期间的视图隔离（本端点的核心安全逻辑）</b>：
 * <ul>
 *   <li>公开会话订阅 {@code contest:{id}:rank:public} —— 封榜期间收到的是**冻结榜**
 *       （内容恒定，因此合并推送会自动静默，不会泄漏「封榜后有人通过」这一事实）；</li>
 *   <li>教师/管理员可带 {@code ?full=true} 订阅 {@code contest:{id}:rank:full} —— 收到实时榜；
 *       非特权角色请求 full 直接以 ERROR 关闭连接（fail-closed，而不是静默降级成公开榜，
 *       否则调用方会以为自己在看全量数据）。</li>
 * </ul>
 *
 * <p><b>握手鉴权</b>：JWT 校验在网关完成，身份经 {@code user-info}/{@code role-info} 头透传，
 * 由 {@link WsIdentityInterceptor} 落到会话属性。没有身份头 = 绕过了网关，握手阶段即 401。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContestRankWsHandler extends TextWebSocketHandler {

    private final ContestRankService rankService;
    private final ContestMapper contestMapper;
    private final WsSessionRegistry registry;
    private final ObjectMapper objectMapper;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long userId = (Long) session.getAttributes().get(WsIdentityInterceptor.ATTR_USER_ID);
        Integer role = (Integer) session.getAttributes().get(WsIdentityInterceptor.ATTR_ROLE);
        Long contestId = parseContestId(session);
        if (contestId == null) {
            fail(session, "竞赛 id 非法：" + session.getUri());
            return;
        }
        Contest contest = contestMapper.selectById(contestId);
        if (contest == null) {
            fail(session, "竞赛不存在：" + contestId);
            return;
        }
        boolean privileged = role != null
                && (role == UserRole.STAFF.getCode() || role == UserRole.TEACHER.getCode());
        boolean wantFull = "true".equalsIgnoreCase(queryParam(session, "full"));
        if (wantFull && !privileged) {
            fail(session, "无权订阅全量榜单（仅教师/管理员）");
            return;
        }
        boolean fullView = wantFull;

        String topic = rankService.topic(contestId, fullView);
        if (!registry.register(topic, session, userId, fullView)) {
            fail(session, "订阅数已达上限，请稍后重试");
            return;
        }

        Map<String, Object> hello = new LinkedHashMap<>();
        hello.put("contestId", contestId);
        hello.put("title", contest.getTitle());
        hello.put("topic", topic);
        hello.put("full", fullView);
        hello.put("role", role);
        hello.put("hint", "榜单变更将以 RANK_UPDATE 推送；封榜/解封以 CONTEST_STATUS 推送");
        sendOne(session, WsEnvelope.of(WsMessageType.CONNECTED, topic, hello), rankService.currentVersion(topic));

        // 连接即推快照：客户端可能在中途才连上，没有快照就会漏掉此前的名次变化
        try {
            ContestRankVO snapshot = rankService.rank(contestId, null, fullView, userId);
            sendOne(session, WsEnvelope.of(WsMessageType.SNAPSHOT, topic, snapshot),
                    rankService.currentVersion(topic));
        } catch (Exception e) {
            log.warn("推送榜单快照失败：contestId={} sessionId={} err={}", contestId, session.getId(), e.getMessage());
        }
        log.info("榜单订阅建立：contestId={} userId={} full={} topic={} sessionId={}",
                contestId, userId, fullView, topic, session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String payload = message.getPayload();
        if (payload == null || payload.isBlank()) {
            return;
        }
        // 客户端 → 服务端只有心跳与控制消息；订阅关系在连接建立时已确定，重复订阅一律忽略
        if (payload.contains("\"PING\"") || payload.toLowerCase().contains("ping")) {
            WsSessionRegistry.SessionMeta meta = registry.metaOf(session.getId());
            String topic = meta == null ? "*" : meta.topic();
            sendOne(session, WsEnvelope.of(WsMessageType.PONG, topic, Map.of("ts", System.currentTimeMillis())),
                    rankService.currentVersion(topic));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        registry.unregister(session);
        log.info("榜单订阅断开：sessionId={} status={}", session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("WS 传输异常：sessionId={} err={}", session.getId(), exception.getMessage());
        registry.unregister(session);
    }

    /** 从路径 {@code /ws/contests/{contestId}/rank} 解析竞赛 id */
    private Long parseContestId(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri == null) {
            return null;
        }
        String[] parts = uri.getPath().split("/");
        // ["", "ws", "contests", "{contestId}", "rank"]
        if (parts.length < 5) {
            return null;
        }
        try {
            return Long.valueOf(parts[3]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String queryParam(WebSocketSession session, String name) {
        URI uri = session.getUri();
        if (uri == null || uri.getQuery() == null) {
            return null;
        }
        for (String pair : uri.getQuery().split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0 && name.equals(pair.substring(0, idx))) {
                return pair.substring(idx + 1);
            }
        }
        return null;
    }

    /** 发送单条消息（握手应答 / 快照 / 错误）：不经过广播通道，只发给当前连接 */
    private void sendOne(WebSocketSession session, WsEnvelope envelope, long seq) {
        envelope.setSeq(seq);
        try {
            registry.pushOne(session, objectMapper.writeValueAsString(envelope));
        } catch (Exception e) {
            log.debug("WS 单发失败：sessionId={} err={}", session.getId(), e.getMessage());
        }
    }

    /** 鉴权/参数失败：先回 ERROR 说明原因，再以策略性关闭码断开（便于客户端区分「别重连」） */
    private void fail(WebSocketSession session, String message) {
        log.warn("WS 订阅被拒：sessionId={} reason={}", session.getId(), message);
        try {
            registry.pushOne(session, objectMapper.writeValueAsString(WsEnvelope.error("*", message)));
            session.close(new CloseStatus(1008, message));
        } catch (Exception e) {
            log.debug("关闭会话失败：sessionId={} err={}", session.getId(), e.getMessage());
        }
    }
}
