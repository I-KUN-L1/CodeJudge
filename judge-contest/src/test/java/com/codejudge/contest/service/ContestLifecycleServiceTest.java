package com.codejudge.contest.service;

import com.codejudge.contest.domain.po.Contest;
import com.codejudge.contest.mapper.ContestMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ContestLifecycleService 状态扫描推进单测。
 *
 * <p>运行：mvn -pl judge-contest -am test
 *
 * <p>覆盖：扫描查询失败吞掉不拖垮调度、开赛/结束的 CAS 推进（成功才推送，CAS 失败不重复推送）、
 * 封榜时点的幂等触发（已有 FROZEN 快照不再封）、终榜留档（已有 FINAL 快照不再写）、
 * 单个竞赛异常不影响同一轮其余竞赛推进。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContestLifecycleServiceTest {

    private static final Long CONTEST_ID = 6001L;
    private static final Long OTHER_CONTEST_ID = 6002L;

    @Mock
    private ContestMapper contestMapper;
    @Mock
    private ContestService contestService;
    @Mock
    private ContestRankService rankService;
    @Mock
    private ContestRankPusher pusher;

    private ContestLifecycleService service;

    @BeforeEach
    void setUp() {
        service = new ContestLifecycleService(contestMapper, contestService, rankService, pusher);
    }

    /** 按相对时间构造竞赛：startOffset/endOffset/freezeOffset 相对当前的秒数，null 表示不封榜 */
    private Contest contest(long startOffsetSec, long endOffsetSec, Long freezeOffsetSec, Integer stored) {
        Contest c = new Contest();
        c.setId(CONTEST_ID);
        c.setTitle("周赛");
        c.setStartTime(LocalDateTime.now().plusSeconds(startOffsetSec));
        c.setEndTime(LocalDateTime.now().plusSeconds(endOffsetSec));
        c.setFreezeAt(freezeOffsetSec == null ? null : LocalDateTime.now().plusSeconds(freezeOffsetSec));
        c.setStatus(stored);
        return c;
    }

    @Nested
    @DisplayName("scan：扫描入口的健壮性")
    class Scan {

        @Test
        @DisplayName("查询失败 → 记录告警返回，不抛出、不触碰状态推进")
        void queryFailureSwallowed() {
            when(contestMapper.selectList(any())).thenThrow(new RuntimeException("db down"));

            assertThatCode(() -> service.scan()).doesNotThrowAnyException();

            verifyNoInteractions(contestService, pusher);
        }

        @Test
        @DisplayName("单个竞赛推进抛异常 → 不影响同一轮其余竞赛")
        void singleFailureDoesNotKillLoop() {
            // broken：已运行、处于封榜窗口 → 冻结分支的 hasSnapshot 抛异常（被逐竞赛 catch）
            Contest broken = contest(-3600, 7200, -60L, Contest.ST_RUNNING);
            // good：待推进（未开始 → 进行中），须与本轮无关地正常完成
            Contest good = contest(-60, 7200, null, Contest.ST_NOT_STARTED);
            good.setId(OTHER_CONTEST_ID);
            when(contestMapper.selectList(any())).thenReturn(List.of(broken, good));
            when(rankService.hasSnapshot(CONTEST_ID, "FROZEN")).thenThrow(new RuntimeException("redis down"));
            when(contestService.updateStatus(eq(good), eq(Contest.ST_NOT_STARTED),
                    eq(Contest.ST_RUNNING))).thenReturn(true);

            assertThatCode(() -> service.scan()).doesNotThrowAnyException();

            verify(contestService).updateStatus(eq(good), eq(Contest.ST_NOT_STARTED),
                    eq(Contest.ST_RUNNING));
            verify(pusher).pushStatus(eq(good), eq("STARTED"), any());
        }
    }

    @Nested
    @DisplayName("开赛推进")
    class Opening {

        @Test
        @DisplayName("未开始 → 进行中：CAS 成功推送 STARTED")
        void pushesStartedOnCasSuccess() {
            Contest c = contest(-60, 7200, null, Contest.ST_NOT_STARTED);
            when(contestMapper.selectList(any())).thenReturn(List.of(c));
            when(contestService.updateStatus(c, Contest.ST_NOT_STARTED, Contest.ST_RUNNING)).thenReturn(true);

            service.scan();

            verify(contestService).updateStatus(c, Contest.ST_NOT_STARTED, Contest.ST_RUNNING);
            verify(pusher).pushStatus(c, "STARTED", "竞赛已开始");
        }

        @Test
        @DisplayName("CAS 失败（其他实例已推进）→ 不重复推送")
        void casFailureNoPush() {
            Contest c = contest(-60, 7200, null, Contest.ST_NOT_STARTED);
            when(contestMapper.selectList(any())).thenReturn(List.of(c));
            when(contestService.updateStatus(c, Contest.ST_NOT_STARTED, Contest.ST_RUNNING)).thenReturn(false);

            service.scan();

            verify(pusher, never()).pushStatus(any(), any(), any());
        }

        @Test
        @DisplayName("状态快照已是进行中（重复扫描）→ 无状态迁移、无推送")
        void storedAlreadyRunningNoOp() {
            Contest c = contest(-60, 7200, null, Contest.ST_RUNNING);
            when(contestMapper.selectList(any())).thenReturn(List.of(c));

            service.scan();

            verify(contestService, never()).updateStatus(any(), anyInt(), anyInt());
            verifyNoInteractions(pusher);
        }
    }

    @Nested
    @DisplayName("结束推进与终榜留档")
    class Finishing {

        @Test
        @DisplayName("结束：推送 FINISHED + 落一条 FINAL 快照")
        void pushesFinishedAndArchivesFinal() {
            Contest c = contest(-7200, -60, null, Contest.ST_RUNNING);
            when(contestMapper.selectList(any())).thenReturn(List.of(c));
            when(contestService.updateStatus(c, Contest.ST_RUNNING, Contest.ST_FINISHED)).thenReturn(true);
            when(rankService.hasSnapshot(CONTEST_ID, "FINAL")).thenReturn(false);

            service.scan();

            verify(pusher).pushStatus(c, "FINISHED", "竞赛已结束，榜单解封");
            verify(rankService).writeSnapshot(c, "FINAL", rankService.liveKey(CONTEST_ID));
        }

        @Test
        @DisplayName("终榜快照已存在 → 不重复留档")
        void finalSnapshotSkippedWhenExists() {
            Contest c = contest(-7200, -60, null, Contest.ST_RUNNING);
            when(contestMapper.selectList(any())).thenReturn(List.of(c));
            when(contestService.updateStatus(c, Contest.ST_RUNNING, Contest.ST_FINISHED)).thenReturn(true);
            when(rankService.hasSnapshot(CONTEST_ID, "FINAL")).thenReturn(true);

            service.scan();

            verify(rankService, never()).writeSnapshot(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("封榜时点（幂等）")
    class Freezing {

        @Test
        @DisplayName("进入封榜窗口且无快照 → freeze 成功后推送 FROZEN")
        void freezesAndPushes() {
            Contest c = contest(-3600, 7200, -60L, Contest.ST_RUNNING);
            when(contestMapper.selectList(any())).thenReturn(List.of(c));
            when(rankService.hasSnapshot(CONTEST_ID, "FROZEN")).thenReturn(false);
            when(rankService.freeze(CONTEST_ID, "lifecycle")).thenReturn(true);

            service.scan();

            verify(rankService).freeze(CONTEST_ID, "lifecycle");
            verify(pusher).pushStatus(c, "FROZEN", "已到封榜时刻，公开榜单冻结（内部继续记录）");
        }

        @Test
        @DisplayName("已有 FROZEN 快照 → 不再封榜（幂等）")
        void freezeSkippedWhenSnapshotExists() {
            Contest c = contest(-3600, 7200, -60L, Contest.ST_RUNNING);
            when(contestMapper.selectList(any())).thenReturn(List.of(c));
            when(rankService.hasSnapshot(CONTEST_ID, "FROZEN")).thenReturn(true);

            service.scan();

            verify(rankService, never()).freeze(anyLong(), any());
        }

        @Test
        @DisplayName("freeze 返回 false（其他实例已处理）→ 不推送")
        void freezeFalseNoPush() {
            Contest c = contest(-3600, 7200, -60L, Contest.ST_RUNNING);
            when(contestMapper.selectList(any())).thenReturn(List.of(c));
            when(rankService.hasSnapshot(CONTEST_ID, "FROZEN")).thenReturn(false);
            when(rankService.freeze(CONTEST_ID, "lifecycle")).thenReturn(false);

            service.scan();

            verify(pusher, never()).pushStatus(any(), any(), any());
        }
    }
}
