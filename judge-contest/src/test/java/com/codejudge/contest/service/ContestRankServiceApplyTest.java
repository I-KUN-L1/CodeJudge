package com.codejudge.contest.service;

import com.codejudge.api.client.submission.SubmissionClient;
import com.codejudge.api.client.user.UserClient;
import com.codejudge.api.dto.submission.SubmissionResultMessage;
import com.codejudge.api.dto.user.UserDTO;
import com.codejudge.common.exceptions.BizIllegalException;
import com.codejudge.common.constants.JudgeRedisKeys;
import com.codejudge.common.ws.WsSequencer;
import com.codejudge.contest.config.ContestProperties;
import com.codejudge.contest.domain.po.Contest;
import com.codejudge.contest.domain.po.ContestProblem;
import com.codejudge.contest.domain.po.ContestRankSnapshot;
import com.codejudge.contest.domain.vo.ContestRankVO;
import com.codejudge.contest.mapper.ContestMapper;
import com.codejudge.contest.mapper.ContestProblemMapper;
import com.codejudge.contest.mapper.ContestRankSnapshotMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ContestRankService 核心业务单测：applyResult（Lua 参数契约）、freeze（幂等/锁回滚）、
 * rank（三段解码与渲染）。
 *
 * <p>运行：mvn -pl judge-contest -am test
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContestRankServiceApplyTest {

    private static final Long CONTEST_ID = 6001L;
    private static final Long USER_ID = 1001L;
    private static final Long PROBLEM_ID = 2001L;

    @Mock
    private ContestMapper contestMapper;
    @Mock
    private ContestProblemMapper contestProblemMapper;
    @Mock
    private ContestRankSnapshotMapper snapshotMapper;
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private RedisScript<List> script;
    @Mock
    private UserClient userClient;
    @Mock
    private SubmissionClient submissionClient;
    @Mock
    private WsSequencer sequencer;

    private final ContestProperties properties = new ContestProperties();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    private final ZSetOperations<String, String> zsetOps = mock(ZSetOperations.class);
    private final HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);

    private ContestRankService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new ContestRankService(contestMapper, contestProblemMapper, snapshotMapper,
                redis, script, objectMapper, properties, userClient, submissionClient, sequencer);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.opsForZSet()).thenReturn(zsetOps);
        when(redis.opsForHash()).thenReturn(hashOps);
    }

    private Contest contest(String rule, long startOffsetSec, long endOffsetSec, LocalDateTime freezeAt) {
        Contest c = new Contest();
        c.setId(CONTEST_ID);
        c.setTitle("周赛");
        c.setRule(rule);
        c.setStartTime(LocalDateTime.now().plusSeconds(startOffsetSec));
        c.setEndTime(LocalDateTime.now().plusSeconds(endOffsetSec));
        c.setFreezeAt(freezeAt);
        return c;
    }

    private ContestProblem contestProblem() {
        ContestProblem cp = new ContestProblem();
        cp.setContestId(CONTEST_ID);
        cp.setProblemId(PROBLEM_ID);
        cp.setLabel("A");
        cp.setDisplayOrder(0);
        cp.setFullScore(100);
        return cp;
    }

    private SubmissionResultMessage result(String verdict, Integer score, Long submitEpochMs) {
        SubmissionResultMessage msg = new SubmissionResultMessage();
        msg.setSubmissionId(9001L);
        msg.setContestId(CONTEST_ID);
        msg.setUserId(USER_ID);
        msg.setProblemId(PROBLEM_ID);
        msg.setVerdict(verdict);
        msg.setScore(score);
        msg.setSubmitTimeEpochMs(submitEpochMs);
        return msg;
    }

    /** 三段编码（与 lua/contest_rank_update.lua 同一公式） */
    private double encoded(long weight, long penaltySec, long tieOffsetSec) {
        return weight * ContestRankService.WEIGHT_FACTOR
                + (ContestRankService.SEGMENT_BASE - penaltySec) * ContestRankService.PENALTY_FACTOR
                + (ContestRankService.SEGMENT_BASE - tieOffsetSec);
    }

    @Nested
    @DisplayName("applyResult：参数守卫")
    class ApplyGuards {

        @Test
        @DisplayName("null / 缺 contestId / contestId=0 → false，不触库")
        void nullAndZeroGuards() {
            assertThat(service.applyResult(null)).isFalse();

            SubmissionResultMessage noContest = result("AC", 0, System.currentTimeMillis());
            noContest.setContestId(null);
            assertThat(service.applyResult(noContest)).isFalse();

            SubmissionResultMessage zeroContest = result("AC", 0, System.currentTimeMillis());
            zeroContest.setContestId(0L);
            assertThat(service.applyResult(zeroContest)).isFalse();

            SubmissionResultMessage noUser = result("AC", 0, System.currentTimeMillis());
            noUser.setUserId(null);
            assertThat(service.applyResult(noUser)).isFalse();

            SubmissionResultMessage noProblem = result("AC", 0, System.currentTimeMillis());
            noProblem.setProblemId(null);
            assertThat(service.applyResult(noProblem)).isFalse();

            verifyNoInteractions(contestMapper, redis);
        }

        @Test
        @DisplayName("竞赛不存在 / 题目不属于该竞赛 → false")
        void missingContestOrProblem() {
            SubmissionResultMessage msg = result("AC", 0, System.currentTimeMillis());

            when(contestMapper.selectById(CONTEST_ID)).thenReturn(null);
            assertThat(service.applyResult(msg)).isFalse();

            when(contestMapper.selectById(CONTEST_ID)).thenReturn(contest(Contest.RULE_ACM, -3600, 7200, null));
            when(contestProblemMapper.selectOne(any())).thenReturn(null);
            assertThat(service.applyResult(msg)).isFalse();

            verify(zsetOps, never()).unionAndStore(anyString(), anyList(), anyString());
        }

        @Test
        @DisplayName("提交时刻在竞赛结束后 → 不计入（提交时刻是权威依据）")
        void submitAfterEndIgnored() {
            Contest c = contest(Contest.RULE_ACM, -7200, -60, null);
            when(contestMapper.selectById(CONTEST_ID)).thenReturn(c);
            when(contestProblemMapper.selectOne(any())).thenReturn(contestProblem());

            assertThat(service.applyResult(result("AC", 0, System.currentTimeMillis()))).isFalse();
            verify(zsetOps, never()).unionAndStore(anyString(), anyList(), anyString());
        }
    }

    @Nested
    @DisplayName("applyResult：Lua 参数契约")
    class ApplyScriptContract {

        @Test
        @DisplayName("ACM 默认罚时 20min：脚本第 3/4/7 参为 ACM/1/1200")
        void acmArgs() {
            Contest c = contest(Contest.RULE_ACM, -3600, 7200, null);
            when(contestMapper.selectById(CONTEST_ID)).thenReturn(c);
            when(contestProblemMapper.selectOne(any())).thenReturn(contestProblem());
            when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(List.of(1L));

            assertThat(service.applyResult(result("AC", null, System.currentTimeMillis()))).isTrue();

            ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
            verify(redis).execute(eq(script), anyList(), args.capture());
            assertThat(args.getValue()[0]).isEqualTo("1001");          // userId
            assertThat(args.getValue()[1]).isEqualTo("2001");          // problemId
            assertThat(args.getValue()[2]).isEqualTo("ACM");           // rule
            assertThat(args.getValue()[3]).isEqualTo("1");             // accepted
            assertThat(args.getValue()[4]).isEqualTo("0");             // score null → 0
            assertThat(args.getValue()[5]).isEqualTo("100");           // fullScore
            assertThat(args.getValue()[6]).isEqualTo("1200");          // 20min × 60
        }

        @Test
        @DisplayName("IOI 显式分数：脚本第 2/3/4 参为 2001/IOI/0（未通过）")
        void ioiArgs() {
            Contest c = contest(Contest.RULE_IOI, -3600, 7200, null);
            when(contestMapper.selectById(CONTEST_ID)).thenReturn(c);
            when(contestProblemMapper.selectOne(any())).thenReturn(contestProblem());
            when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(List.of(1L));

            assertThat(service.applyResult(result("WA", 80, System.currentTimeMillis()))).isTrue();

            ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
            verify(redis).execute(eq(script), anyList(), args.capture());
            assertThat(args.getValue()[1]).isEqualTo("2001");
            assertThat(args.getValue()[2]).isEqualTo("IOI");
            assertThat(args.getValue()[3]).isEqualTo("0");
            assertThat(args.getValue()[4]).isEqualTo("80");
        }

        @Test
        @DisplayName("脚本返回 null（未加载）/ 0（未变更）→ false；字符串 '1' → true")
        void scriptResultInterpretation() {
            Contest c = contest(Contest.RULE_ACM, -3600, 7200, null);
            when(contestMapper.selectById(CONTEST_ID)).thenReturn(c);
            when(contestProblemMapper.selectOne(any())).thenReturn(contestProblem());
            SubmissionResultMessage msg = result("AC", 0, System.currentTimeMillis());

            when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(null);
            assertThat(service.applyResult(msg)).isFalse();

            when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(List.of(0L));
            assertThat(service.applyResult(msg)).isFalse();

            when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(List.of("1"));
            assertThat(service.applyResult(msg)).isTrue();
        }

        @Test
        @DisplayName("老版本消息缺提交时间 → 按当前时间计入（赛后提交兜底仍受 endSec 拦截）")
        void missingSubmitTimeFallsBackToNow() {
            Contest c = contest(Contest.RULE_ACM, -3600, 7200, null);
            when(contestMapper.selectById(CONTEST_ID)).thenReturn(c);
            when(contestProblemMapper.selectOne(any())).thenReturn(contestProblem());
            when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(List.of(1L));

            assertThat(service.applyResult(result("AC", 0, null))).isTrue();
        }
    }

    @Nested
    @DisplayName("freeze：封榜幂等与锁回滚")
    class Freeze {

        @Test
        @DisplayName("竞赛不存在 → 404")
        void contestNotFound() {
            when(contestMapper.selectById(CONTEST_ID)).thenReturn(null);

            assertThatThrownBy(() -> service.freeze(CONTEST_ID, "manual"))
                    .isInstanceOf(BizIllegalException.class)
                    .hasMessageContaining("竞赛不存在");
        }

        @Test
        @DisplayName("已存在 FROZEN 快照 → false，不取锁")
        void alreadyFrozenSkips() {
            when(contestMapper.selectById(CONTEST_ID)).thenReturn(contest(Contest.RULE_ACM, -3600, 7200, null));
            when(snapshotMapper.selectCount(any())).thenReturn(1L);

            assertThat(service.freeze(CONTEST_ID, "manual")).isFalse();
            verifyNoInteractions(redis);
        }

        @Test
        @DisplayName("SETNX 未命中（其他实例处理中）→ false；已有 freezeAt 不回填")
        void lockHeldByOtherInstance() {
            Contest c = contest(Contest.RULE_ACM, -3600, 7200, LocalDateTime.now().minusMinutes(5));
            when(contestMapper.selectById(CONTEST_ID)).thenReturn(c);
            when(snapshotMapper.selectCount(any())).thenReturn(0L);
            when(valueOps.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class))).thenReturn(false);

            assertThat(service.freeze(CONTEST_ID, "manual")).isFalse();
            verify(contestMapper, never()).updateById(any(Contest.class));
        }

        @Test
        @DisplayName("手动封榜（未配 freezeAt）→ 回填封榜时刻")
        void manualFreezeBackfillsFreezeAt() {
            Contest c = contest(Contest.RULE_ACM, -3600, 7200, null);
            when(contestMapper.selectById(CONTEST_ID)).thenReturn(c);
            when(snapshotMapper.selectCount(any())).thenReturn(0L);
            when(valueOps.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class))).thenReturn(false);

            assertThat(service.freeze(CONTEST_ID, "manual")).isFalse();
            verify(contestMapper).updateById(c);
            assertThat(c.getFreezeAt()).isNotNull();
        }

        @Test
        @DisplayName("封榜成功：冻结状态副本 → ZUNIONSTORE → 快照落库 → 加 TTL")
        void successPath() {
            Contest c = contest(Contest.RULE_ACM, -3600, 7200, LocalDateTime.now().minusMinutes(5));
            when(contestMapper.selectById(CONTEST_ID)).thenReturn(c);
            when(snapshotMapper.selectCount(any())).thenReturn(0L);
            when(valueOps.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class))).thenReturn(true);
            when(zsetOps.range(service.liveKey(CONTEST_ID), 0, -1)).thenReturn(Set.of("1001"));
            when(zsetOps.range(service.frozenKey(CONTEST_ID), 0, -1)).thenReturn(Set.of());
            when(hashOps.entries(service.userStatusKey(CONTEST_ID, USER_ID)))
                    .thenReturn(Map.of("2001", "ac:1200:2"));
            when(zsetOps.unionAndStore(service.liveKey(CONTEST_ID), List.of(),
                    service.frozenKey(CONTEST_ID))).thenReturn(5L);
            when(zsetOps.reverseRangeWithScores(service.frozenKey(CONTEST_ID), 0, 4999L)).thenReturn(null);

            assertThat(service.freeze(CONTEST_ID, "manual")).isTrue();

            // 冻结副本复制（明细泄漏防线）
            verify(hashOps).putAll(eq(service.frozenUserStatusKey(CONTEST_ID, USER_ID)), any());
            // 单命令原子快照 + TTL
            verify(zsetOps).unionAndStore(service.liveKey(CONTEST_ID), List.of(), service.frozenKey(CONTEST_ID));
            verify(redis).expire(eq(service.frozenKey(CONTEST_ID)), any(java.time.Duration.class));
            // 快照落库（渲染自冻结榜，空榜 rankJson 为 []）
            ArgumentCaptor<ContestRankSnapshot> snapshot = ArgumentCaptor.forClass(ContestRankSnapshot.class);
            verify(snapshotMapper).insert(snapshot.capture());
            assertThat(snapshot.getValue().getContestId()).isEqualTo(CONTEST_ID);
            assertThat(snapshot.getValue().getSnapshotType()).isEqualTo(ContestRankSnapshot.TYPE_FROZEN);
            assertThat(snapshot.getValue().getRankJson()).isEqualTo("[]");
        }

        @Test
        @DisplayName("封榜时无任何成绩 → 冻结榜 key 清掉（防上届残留），快照仍留档")
        void emptyFreezeClearsKey() {
            Contest c = contest(Contest.RULE_ACM, -3600, 7200, LocalDateTime.now().minusMinutes(5));
            when(contestMapper.selectById(CONTEST_ID)).thenReturn(c);
            when(snapshotMapper.selectCount(any())).thenReturn(0L);
            when(valueOps.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class))).thenReturn(true);
            when(zsetOps.range(service.liveKey(CONTEST_ID), 0, -1)).thenReturn(Set.of());
            when(zsetOps.range(service.frozenKey(CONTEST_ID), 0, -1)).thenReturn(Set.of());
            when(zsetOps.unionAndStore(service.liveKey(CONTEST_ID), List.of(),
                    service.frozenKey(CONTEST_ID))).thenReturn(0L);
            when(zsetOps.reverseRangeWithScores(service.frozenKey(CONTEST_ID), 0, 4999L)).thenReturn(null);

            assertThat(service.freeze(CONTEST_ID, "manual")).isTrue();

            verify(redis).delete(service.frozenKey(CONTEST_ID));
            verify(redis, never()).expire(eq(service.frozenKey(CONTEST_ID)), any(java.time.Duration.class));
            verify(snapshotMapper).insert(any(ContestRankSnapshot.class));
        }

        @Test
        @DisplayName("工作失败 → 回滚封榜锁（否则 48h 内不可自愈）")
        void lockRolledBackOnFailure() {
            Contest c = contest(Contest.RULE_ACM, -3600, 7200, LocalDateTime.now().minusMinutes(5));
            when(contestMapper.selectById(CONTEST_ID)).thenReturn(c);
            when(snapshotMapper.selectCount(any())).thenReturn(0L);
            when(valueOps.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class))).thenReturn(true);
            when(zsetOps.range(service.liveKey(CONTEST_ID), 0, -1)).thenReturn(Set.of("1001"));
            when(zsetOps.range(service.frozenKey(CONTEST_ID), 0, -1)).thenReturn(Set.of());
            when(hashOps.entries(service.userStatusKey(CONTEST_ID, USER_ID))).thenReturn(Map.of());
            when(zsetOps.unionAndStore(anyString(), anyList(), anyString()))
                    .thenThrow(new IllegalStateException("redis broke"));

            assertThatThrownBy(() -> service.freeze(CONTEST_ID, "manual"))
                    .isInstanceOf(IllegalStateException.class);
            // 精确匹配封榜锁 key（成员副本清理也会 delete，不能宽匹配）
            verify(redis).delete(JudgeRedisKeys.CONTEST_FREEZE_LOCK_PREFIX + CONTEST_ID);
        }
    }

    @Nested
    @DisplayName("rank：三段解码与渲染")
    class RankView {

        @Test
        @DisplayName("竞赛不存在 → 404")
        void contestNotFound() {
            when(contestMapper.selectById(CONTEST_ID)).thenReturn(null);

            assertThatThrownBy(() -> service.rank(CONTEST_ID, null, false, null))
                    .isInstanceOf(BizIllegalException.class)
                    .hasMessageContaining("竞赛不存在");
        }

        @Test
        @DisplayName("解码渲染：权重/罚时/逐题状态/用户名；榜内用户复用条目作为我的名次")
        void decodesEntriesAndMyRank() {
            Contest c = contest(Contest.RULE_ACM, -3600, 7200, null);
            when(contestMapper.selectById(CONTEST_ID)).thenReturn(c);
            when(contestProblemMapper.selectList(any())).thenReturn(List.of(contestProblem()));
            when(zsetOps.zCard(service.liveKey(CONTEST_ID))).thenReturn(2L);
            when(sequencer.current("contest:6001:rank:public")).thenReturn(7L);
            when(zsetOps.reverseRangeWithScores(service.liveKey(CONTEST_ID), 0, 99L))
                    .thenReturn(Set.of(ZSetOperations.TypedTuple.of("1001", encoded(1, 1200, 300))));
            when(redis.executePipelined(any(org.springframework.data.redis.core.RedisCallback.class)))
                    .thenReturn(List.of(Map.of("2001", "ac:1200:2")));
            UserDTO u = new UserDTO();
            u.setId(USER_ID);
            u.setName("张三");
            when(userClient.queryUserByIds(List.of(USER_ID))).thenReturn(List.of(u));
            when(zsetOps.reverseRank(service.liveKey(CONTEST_ID), "1001")).thenReturn(0L);

            ContestRankVO vo = service.rank(CONTEST_ID, null, false, USER_ID);

            assertThat(vo.getTotalParticipants()).isEqualTo(2L);
            assertThat(vo.getVersion()).isEqualTo(7L);
            assertThat(vo.getEntries()).hasSize(1);
            var e = vo.getEntries().get(0);
            assertThat(e.getRank()).isEqualTo(1);
            assertThat(e.getUserId()).isEqualTo(USER_ID);
            assertThat(e.getUserName()).isEqualTo("张三");
            assertThat(e.getWeight()).isEqualTo(1);
            assertThat(e.getPenaltySeconds()).isEqualTo(1200);
            assertThat(e.getSolvedCount()).isEqualTo(1);   // ACM：solved=权重
            assertThat(e.getTotalScore()).isZero();
            assertThat(e.getProblemStatus()).containsEntry("A", "+2"); // 2 次错误后通过
            assertThat(e.getLastAcceptedOffsetSeconds()).isNotNull();
            assertThat(vo.getMyRank()).isEqualTo(1);
            assertThat(vo.getMyEntry()).isSameAs(e);
        }

        @Test
        @DisplayName("榜外用户：只渲染自己那一行（rank=reverseRank+1），不整榜渲染")
        void myRankOutsideTopN() {
            Contest c = contest(Contest.RULE_ACM, -3600, 7200, null);
            when(contestMapper.selectById(CONTEST_ID)).thenReturn(c);
            when(contestProblemMapper.selectList(any())).thenReturn(List.of(contestProblem()));
            when(zsetOps.zCard(service.liveKey(CONTEST_ID))).thenReturn(1L);
            when(sequencer.current("contest:6001:rank:public")).thenReturn(7L);
            when(zsetOps.reverseRangeWithScores(service.liveKey(CONTEST_ID), 0, 99L)).thenReturn(Set.of());
            when(zsetOps.reverseRank(service.liveKey(CONTEST_ID), "1001")).thenReturn(3L);
            when(zsetOps.reverseRangeWithScores(service.liveKey(CONTEST_ID), 3L, 3L))
                    .thenReturn(Set.of(ZSetOperations.TypedTuple.of("1001", encoded(1, 60, 30))));
            when(redis.executePipelined(any(org.springframework.data.redis.core.RedisCallback.class)))
                    .thenReturn(List.of());
            when(userClient.queryUserByIds(List.of(USER_ID))).thenReturn(null);

            ContestRankVO vo = service.rank(CONTEST_ID, null, false, USER_ID);

            assertThat(vo.getEntries()).isEmpty();
            assertThat(vo.getMyRank()).isEqualTo(4);
            assertThat(vo.getMyEntry()).isNotNull();
            assertThat(vo.getMyEntry().getRank()).isEqualTo(4);
            assertThat(vo.getMyEntry().getUserId()).isEqualTo(USER_ID);
            assertThat(vo.getMyEntry().getUserName()).isEqualTo("1001"); // 用户服务无名字 → 回落 id
            assertThat(vo.getMyEntry().getSolvedCount()).isEqualTo(1);
        }
    }
}
