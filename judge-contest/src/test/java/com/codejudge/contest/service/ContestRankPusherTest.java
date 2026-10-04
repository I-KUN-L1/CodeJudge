package com.codejudge.contest.service;

import com.codejudge.common.constants.JudgeRedisKeys;
import com.codejudge.common.ws.RedisPushChannel;
import com.codejudge.common.ws.WsEnvelope;
import com.codejudge.common.ws.WsMessageType;
import com.codejudge.contest.config.ContestProperties;
import com.codejudge.contest.domain.po.Contest;
import com.codejudge.contest.domain.vo.ContestRankVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ContestRankPusher 推送合并单测。
 *
 * <p>运行：mvn -pl judge-contest -am test
 *
 * <p>覆盖：脏标记守卫与落 Redis、窗口合并（public/full 双视图各推一次）、
 * 内容签名去重（封榜期间公众榜恒定 → 自动静默）、渲染失败跳过不炸、
 * 状态事件绕过内容去重无条件推送（CONTEST_STATUS）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContestRankPusherTest {

    private static final Long CONTEST_ID = 6001L;
    private static final String TOPIC_PUBLIC = "contest:6001:rank:public";
    private static final String TOPIC_FULL = "contest:6001:rank:full";

    @Mock
    private ContestRankService rankService;
    @Mock
    private RedisPushChannel pushChannel;
    @Mock
    private StringRedisTemplate redis;

    private final ContestProperties properties = new ContestProperties();
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);

    private ContestRankPusher pusher;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valueOps);
        pusher = new ContestRankPusher(rankService, pushChannel, properties, redis);
    }

    private Contest contest() {
        Contest c = new Contest();
        c.setId(CONTEST_ID);
        c.setTitle("周赛");
        c.setStartTime(LocalDateTime.now().minusHours(1));
        c.setEndTime(LocalDateTime.now().plusHours(1));
        return c;
    }

    private ContestRankVO vo() {
        ContestRankVO vo = new ContestRankVO();
        vo.setContestId(CONTEST_ID);
        vo.setTitle("周赛");
        vo.setStatus(1);
        vo.setTotalParticipants(3L);
        vo.setEntries(List.of());
        return vo;
    }

    @Nested
    @DisplayName("markDirty：脏标记")
    class MarkDirty {

        @Test
        @DisplayName("null / 0 → 忽略，不触 Redis")
        void guards() {
            pusher.markDirty(null);
            pusher.markDirty(0L);

            verifyNoInteractions(redis);
        }

        @Test
        @DisplayName("有效登记 → 写运维观测用的脏标记 key")
        void writesFlag() {
            pusher.markDirty(CONTEST_ID);

            verify(valueOps).set(contains(JudgeRedisKeys.CONTEST_PUSH_DIRTY_PREFIX), eq("1"),
                    any(java.time.Duration.class));
        }
    }

    @Nested
    @DisplayName("flush：窗口合并推送")
    class Flush {

        @BeforeEach
        void stubViews() {
            properties.setPushFlushIntervalMs(0); // 窗口归零：登记即到期，便于同步断言
            when(rankService.topic(CONTEST_ID, false)).thenReturn(TOPIC_PUBLIC);
            when(rankService.topic(CONTEST_ID, true)).thenReturn(TOPIC_FULL);
            when(rankService.nextVersion(anyString())).thenReturn(1L);
        }

        @Test
        @DisplayName("无脏竞赛 → 空转，不渲染不推送")
        void emptyNoop() {
            pusher.flush();

            verifyNoInteractions(rankService, pushChannel);
        }

        @Test
        @DisplayName("到期冲刷：public 与 full 双视图各推一条 RANK_UPDATE，seq 来自 WsSequencer")
        void pushesBothViews() {
            when(rankService.rankForPush(CONTEST_ID, false)).thenReturn(vo());
            when(rankService.rankForPush(CONTEST_ID, true)).thenReturn(vo());

            pusher.markDirty(CONTEST_ID);
            pusher.flush();

            ArgumentCaptor<WsEnvelope> envelopes = ArgumentCaptor.forClass(WsEnvelope.class);
            verify(pushChannel, times(2)).publish(envelopes.capture());
            List<WsEnvelope> all = envelopes.getAllValues();
            assertThat(all).extracting(WsEnvelope::getType)
                    .containsExactly(WsMessageType.RANK_UPDATE.name(), WsMessageType.RANK_UPDATE.name());
            assertThat(all).extracting(WsEnvelope::getTopic).containsExactly(TOPIC_PUBLIC, TOPIC_FULL);
            assertThat(all).extracting(WsEnvelope::isFull).containsExactly(false, true);
        }

        @Test
        @DisplayName("内容签名未变（封榜公众榜恒定）→ 第二轮静默，不重复推送")
        void dedupsSameContent() {
            when(rankService.rankForPush(CONTEST_ID, false)).thenReturn(vo());
            when(rankService.rankForPush(CONTEST_ID, true)).thenReturn(vo());

            pusher.markDirty(CONTEST_ID);
            pusher.flush();
            pusher.markDirty(CONTEST_ID);
            pusher.flush();

            verify(pushChannel, times(2)).publish(any(WsEnvelope.class));
        }

        @Test
        @DisplayName("渲染失败 → 跳过该视图，不抛出")
        void renderFailureSkipsSilently() {
            when(rankService.rankForPush(CONTEST_ID, false)).thenThrow(new RuntimeException("db down"));

            assertThatCode(() -> {
                pusher.markDirty(CONTEST_ID);
                pusher.flush();
            }).doesNotThrowAnyException();

            verify(pushChannel, never()).publish(any(WsEnvelope.class));
        }
    }

    @Nested
    @DisplayName("pushStatus：状态事件（开赛/封榜/结束）")
    class PushStatus {

        @Test
        @DisplayName("广播两个视图，CONTEST_STATUS 不做内容去重")
        void broadcastsBothViews() {
            when(rankService.topic(CONTEST_ID, false)).thenReturn(TOPIC_PUBLIC);
            when(rankService.topic(CONTEST_ID, true)).thenReturn(TOPIC_FULL);
            when(rankService.nextVersion(anyString())).thenReturn(1L);

            pusher.pushStatus(contest(), "FROZEN", "已到封榜时刻");

            ArgumentCaptor<WsEnvelope> envelopes = ArgumentCaptor.forClass(WsEnvelope.class);
            verify(pushChannel, times(2)).publish(envelopes.capture());
            assertThat(envelopes.getAllValues()).extracting(WsEnvelope::getType)
                    .containsOnly(WsMessageType.CONTEST_STATUS.name());
            assertThat(envelopes.getAllValues()).extracting(WsEnvelope::getTopic)
                    .containsExactly(TOPIC_PUBLIC, TOPIC_FULL);
        }

        @Test
        @DisplayName("contest 为 null 或无 id → 直接返回")
        void nullSafe() {
            pusher.pushStatus(null, "STARTED", "x");
            Contest noId = contest();
            noId.setId(null);
            pusher.pushStatus(noId, "STARTED", "x");

            verifyNoInteractions(rankService, pushChannel);
        }
    }
}
