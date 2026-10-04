package com.codejudge.contest.ws;

import com.codejudge.common.constants.UserRole;
import com.codejudge.common.ws.WsIdentityInterceptor;
import com.codejudge.common.ws.WsSessionRegistry;
import com.codejudge.contest.domain.po.Contest;
import com.codejudge.contest.domain.vo.ContestRankVO;
import com.codejudge.contest.mapper.ContestMapper;
import com.codejudge.contest.service.ContestRankService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ContestRankWsHandler 端点单测（订阅模型与封榜视图隔离的安全逻辑）。
 *
 * <p>运行：mvn -pl judge-contest -am test
 *
 * <p>覆盖：路径解析竞赛 id、竞赛不存在拒绝、full 视图的特权门（fail-closed，
 * 非教师/管理员请求全量榜以 ERROR 关闭而非静默降级）、订阅上限、连接即推
 * CONNECTED + 快照、心跳 PONG、断开/异常注销。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContestRankWsHandlerTest {

    private static final Long CONTEST_ID = 42L;
    private static final Long USER_ID = 1001L;

    @Mock
    private ContestRankService rankService;
    @Mock
    private ContestMapper contestMapper;
    @Mock
    private WsSessionRegistry registry;
    @Mock
    private WebSocketSession session;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ContestRankWsHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        handler = new ContestRankWsHandler(rankService, contestMapper, registry, objectMapper);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(WsIdentityInterceptor.ATTR_USER_ID, USER_ID);
        attrs.put(WsIdentityInterceptor.ATTR_ROLE, UserRole.STAFF.getCode());
        when(session.getAttributes()).thenReturn(attrs);
        when(session.getId()).thenReturn("s1");
        when(session.getUri()).thenReturn(new URI("ws://localhost/ws/contests/42/rank"));
        when(session.isOpen()).thenReturn(true);
        Contest contest = new Contest();
        contest.setId(CONTEST_ID);
        contest.setTitle("周赛");
        contest.setStartTime(java.time.LocalDateTime.now().minusHours(1));
        contest.setEndTime(java.time.LocalDateTime.now().plusHours(1));
        when(contestMapper.selectById(CONTEST_ID)).thenReturn(contest);
        when(registry.register(anyString(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(true);
        // handler 内 topic 由 rankService 生成；mock 默认返回 null 会让 anyString() 桩不匹配
        when(rankService.topic(CONTEST_ID, false)).thenReturn("contest:42:rank:public");
        when(rankService.topic(CONTEST_ID, true)).thenReturn("contest:42:rank:full");
    }

    private ContestRankVO snapshot() {
        ContestRankVO vo = new ContestRankVO();
        vo.setContestId(CONTEST_ID);
        vo.setTitle("周赛");
        return vo;
    }

    private String pushedJson(int index) throws Exception {
        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(registry, org.mockito.Mockito.atLeast(index + 1)).pushOne(eq(session), captor.capture());
        return captor.getAllValues().get(index);
    }

    @Nested
    @DisplayName("握手拒绝")
    class Rejection {

        @Test
        @DisplayName("路径无法解析竞赛 id → ERROR + 1008 关闭")
        void invalidContestId() throws Exception {
            when(session.getUri()).thenReturn(new URI("ws://localhost/ws/other"));

            handler.afterConnectionEstablished(session);

            assertThat(pushedJson(0)).contains("竞赛 id 非法");
            verify(session).close(any(CloseStatus.class));
            verify(registry, never()).register(anyString(), any(), any(),
                    org.mockito.ArgumentMatchers.anyBoolean());
        }

        @Test
        @DisplayName("竞赛不存在 → 拒绝")
        void contestMissing() throws Exception {
            when(contestMapper.selectById(CONTEST_ID)).thenReturn(null);

            handler.afterConnectionEstablished(session);

            assertThat(pushedJson(0)).contains("竞赛不存在");
            verify(session).close(any(CloseStatus.class));
        }

        @Test
        @DisplayName("无角色（未过网关）请求 full → fail-closed 拒绝而非降级公开榜")
        void fullRequiresPrivilege() throws Exception {
            when(session.getAttributes()).thenReturn(Map.of(WsIdentityInterceptor.ATTR_USER_ID, USER_ID));
            when(session.getUri()).thenReturn(new URI("ws://localhost/ws/contests/42/rank?full=true"));

            handler.afterConnectionEstablished(session);

            assertThat(pushedJson(0)).contains("无权订阅全量榜单");
            verify(session).close(any(CloseStatus.class));
            verify(rankService, never()).currentVersion(anyString());
        }

        @Test
        @DisplayName("订阅数达上限 → 拒绝")
        void registerLimitReached() throws Exception {
            when(registry.register(anyString(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                    .thenReturn(false);

            handler.afterConnectionEstablished(session);

            assertThat(pushedJson(0)).contains("订阅数已达上限");
        }
    }

    @Nested
    @DisplayName("订阅建立")
    class Establishment {

        @Test
        @DisplayName("教师/管理员 full=true → 注册 full 主题，先 CONNECTED 后 SNAPSHOT")
        void privilegedFullView() throws Exception {
            when(session.getUri()).thenReturn(new URI("ws://localhost/ws/contests/42/rank?full=true"));
            when(rankService.currentVersion(anyString())).thenReturn(5L);
            when(rankService.rank(CONTEST_ID, null, true, USER_ID)).thenReturn(snapshot());

            handler.afterConnectionEstablished(session);

            verify(registry).register(eq("contest:42:rank:full"), eq(session), eq(USER_ID), eq(true));
            assertThat(pushedJson(0)).contains("CONNECTED");
            assertThat(pushedJson(1)).contains("SNAPSHOT");
            verify(rankService).rank(CONTEST_ID, null, true, USER_ID);
        }

        @Test
        @DisplayName("默认公开视图：注册 public 主题")
        void defaultPublicView() throws Exception {
            when(rankService.currentVersion(anyString())).thenReturn(5L);
            when(rankService.rank(CONTEST_ID, null, false, USER_ID)).thenReturn(snapshot());

            handler.afterConnectionEstablished(session);

            verify(registry).register(eq("contest:42:rank:public"), eq(session), eq(USER_ID), eq(false));
        }

        @Test
        @DisplayName("快照渲染失败 → 连接仍建立（CONNECTED 已发出，不炸会话）")
        void snapshotFailureKeepsConnection() throws Exception {
            when(rankService.currentVersion(anyString())).thenReturn(5L);
            when(rankService.rank(CONTEST_ID, null, false, USER_ID)).thenThrow(new RuntimeException("down"));

            assertThatCode(() -> handler.afterConnectionEstablished(session)).doesNotThrowAnyException();

            verify(registry).register(eq("contest:42:rank:public"), eq(session), eq(USER_ID), eq(false));
        }
    }

    @Nested
    @DisplayName("消息与会话生命周期")
    class Lifecycle {

        @Test
        @DisplayName("PING → PONG（带会话主题与当前版本）")
        void pingRepliesPong() throws Exception {
            when(registry.metaOf("s1"))
                    .thenReturn(new WsSessionRegistry.SessionMeta("contest:42:rank:public", USER_ID, false));
            when(rankService.currentVersion("contest:42:rank:public")).thenReturn(9L);

            handler.handleTextMessage(session, new TextMessage("{\"type\":\"PING\"}"));

            assertThat(pushedJson(0)).contains("PONG");
            verify(rankService).currentVersion("contest:42:rank:public");
        }

        @Test
        @DisplayName("空报文 / 非 PING → 忽略")
        void nonPingIgnored() {
            handler.handleTextMessage(session, new TextMessage("  "));
            handler.handleTextMessage(session, new TextMessage("{\"type\":\"OTHER\"}"));

            verify(registry, never()).pushOne(any(), anyString());
        }

        @Test
        @DisplayName("断开与传输异常 → 注销订阅")
        void unregisterOnCloseAndError() {
            handler.afterConnectionClosed(session, CloseStatus.NORMAL);
            handler.handleTransportError(session, new RuntimeException("io"));

            verify(registry, org.mockito.Mockito.times(2)).unregister(session);
        }
    }
}
