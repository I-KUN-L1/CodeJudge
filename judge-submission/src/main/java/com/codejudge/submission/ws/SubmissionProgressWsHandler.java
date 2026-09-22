package com.codejudge.submission.ws;

import com.codejudge.common.constants.UserRole;
import com.codejudge.common.ws.WsEnvelope;
import com.codejudge.common.ws.WsIdentityInterceptor;
import com.codejudge.common.ws.WsMessageType;
import com.codejudge.common.ws.WsSessionRegistry;
import com.codejudge.submission.domain.po.Submission;
import com.codejudge.submission.mapper.SubmissionMapper;
import com.codejudge.submission.service.SubmissionProgressPushService;
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
import java.util.Set;

/**
 * 判题进度 WebSocket 端点：{@code /ws/submissions/{submissionId}}。
 *
 * <p><b>归属校验（本端点的核心安全逻辑）</b>：只有「提交者本人」与教师/管理员可订阅。
 * 没有这道校验，任何人猜一个雪花 id 就能实时围观别人代码的判题过程与逐用例结论
 * （配合错误摘要可以反推隐藏用例的期望输出）。
 *
 * <p><b>连接即推快照</b>：判题可能在 1 秒内就跑完，客户端从「POST 提交」到「建立 WS」
 * 之间的进度必然错过。因此连接时先推一条 SNAPSHOT（当前阶段 + 已落库的结论），
 * 若已是终态则直接告知，客户端不必再等推送。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubmissionProgressWsHandler extends TextWebSocketHandler {

    private static final Set<String> TERMINAL_STATUSES = Set.of("SUCCESS", "FAILED");

    private final SubmissionMapper submissionMapper;
    private final SubmissionProgressPushService pushService;
    private final WsSessionRegistry registry;
    private final ObjectMapper objectMapper;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long userId = (Long) session.getAttributes().get(WsIdentityInterceptor.ATTR_USER_ID);
        Integer role = (Integer) session.getAttributes().get(WsIdentityInterceptor.ATTR_ROLE);
        Long submissionId = parseSubmissionId(session);
        if (submissionId == null) {
            fail(session, "提交 id 非法：" + session.getUri());
            return;
        }
        Submission submission = submissionMapper.selectById(submissionId);
        if (submission == null) {
            fail(session, "提交不存在：" + submissionId);
            return;
        }
        boolean privileged = role != null && (role == UserRole.STAFF.getCode() || role == UserRole.TEACHER.getCode());
        if (!privileged && !submission.getUserId().equals(userId)) {
            fail(session, "无权订阅他人提交的判题进度");
            return;
        }

        String topic = pushService.topic(submissionId);
        if (!registry.register(topic, session, userId, privileged)) {
            fail(session, "订阅数已达上限，请稍后重试");
            return;
        }

        Map<String, Object> hello = new LinkedHashMap<>();
        hello.put("submissionId", submissionId);
        hello.put("topic", topic);
        hello.put("message", "已订阅判题进度；进度以 SUB_PROGRESS 推送，终态以 SUB_RESULT 推送");
        sendOne(session, WsEnvelope.of(WsMessageType.CONNECTED, topic, hello));

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("submissionId", submissionId);
        snapshot.put("status", submission.getStatus());
        snapshot.put("verdict", submission.getVerdict());
        snapshot.put("score", submission.getScore());
        snapshot.put("timeMs", submission.getTimeMs());
        snapshot.put("memoryKb", submission.getMemoryKb());
        snapshot.put("submitTime", submission.getSubmitTime());
        boolean terminal = submission.getStatus() != null && TERMINAL_STATUSES.contains(submission.getStatus());
        snapshot.put("terminal", terminal);
        snapshot.put("message", terminal ? "判题已结束，无需等待推送" : "判题进行中，请等待推送");
        sendOne(session, WsEnvelope.of(WsMessageType.SNAPSHOT, topic, snapshot));

        log.info("判题进度订阅建立：submissionId={} userId={} role={} status={} sessionId={}",
                submissionId, userId, role, submission.getStatus(), session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String payload = message.getPayload();
        if (payload == null || payload.isBlank()) {
            return;
        }
        if (payload.toLowerCase().contains("ping")) {
            WsSessionRegistry.SessionMeta meta = registry.metaOf(session.getId());
            String topic = meta == null ? "*" : meta.topic();
            sendOne(session, WsEnvelope.of(WsMessageType.PONG, topic, Map.of("ts", System.currentTimeMillis())));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        registry.unregister(session);
        log.info("判题进度订阅断开：sessionId={} status={}", session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("WS 传输异常：sessionId={} err={}", session.getId(), exception.getMessage());
        registry.unregister(session);
    }

    /** 从路径 {@code /ws/submissions/{id}} 解析提交 id */
    private Long parseSubmissionId(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri == null) {
            return null;
        }
        String[] parts = uri.getPath().split("/");
        // ["", "ws", "submissions", "{submissionId}"]
        if (parts.length < 4) {
            return null;
        }
        try {
            return Long.valueOf(parts[3]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void sendOne(WebSocketSession session, WsEnvelope envelope) {
        try {
            registry.pushOne(session, objectMapper.writeValueAsString(envelope));
        } catch (Exception e) {
            log.debug("WS 单发失败：sessionId={} err={}", session.getId(), e.getMessage());
        }
    }

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
