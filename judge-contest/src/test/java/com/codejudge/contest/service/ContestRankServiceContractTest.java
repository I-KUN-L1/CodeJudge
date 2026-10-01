package com.codejudge.contest.service;

import com.codejudge.common.ws.WsSequencer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ContestRankService 的键位 / 主题 / 编码常量契约单测。
 *
 * <p>运行：mvn -pl judge-contest -am test
 *
 * <p>这些字面量是**跨端契约**：Redis 键位被 Lua 脚本与运维脚本共用、
 * 三段编码常量必须与 contest_rank_update.lua 的同名常量一致（类注释明确要求同步修改）、
 * WS 主题格式被前端订阅。挂了即等于榜单全链路挂了，所以逐条固化。
 */
@ExtendWith(MockitoExtension.class)
class ContestRankServiceContractTest {

    @Mock
    private WsSequencer sequencer;

    /** 依赖只被键位/主题方法之外的路径使用，传 null 即可实例化 */
    private ContestRankService service() {
        return new ContestRankService(null, null, null, null, null, null,
                null, null, null, sequencer);
    }

    @Nested
    @DisplayName("Redis 键位契约")
    class Keys {

        @Test
        @DisplayName("实时榜 / 冻结榜 / 用户状态 / 冻结副本四类键互不冲突且含竞赛 id")
        void distinctKeys() {
            ContestRankService s = service();
            String live = s.liveKey(42L);
            String frozen = s.frozenKey(42L);
            String status = s.userStatusKey(42L, 1001L);
            String frozenStatus = s.frozenUserStatusKey(42L, 1001L);

            assertThat(live).contains("42").isNotEqualTo(frozen);
            assertThat(status).contains("42").contains("1001").isNotEqualTo(frozenStatus);
            // 冻结副本必须区别于实时状态：封榜期间对外渲染只能读副本（明细泄漏防线）
            assertThat(frozenStatus).isNotEqualTo(status);
        }
    }

    @Nested
    @DisplayName("推送主题契约")
    class Topics {

        @Test
        @DisplayName("公开榜与全量榜主题不同，均含竞赛 id 与 rank 标识")
        void topicFormats() {
            ContestRankService s = service();
            String pub = s.topic(7L, false);
            String full = s.topic(7L, true);

            assertThat(pub).contains("contest:7").contains("rank").isNotEqualTo(full);
            assertThat(full).contains("contest:7").contains("rank");
        }

        @Test
        @DisplayName("版本号读写委托给 WsSequencer（REST 与 WS 共用同一套版本）")
        void versionDelegation() {
            when(sequencer.current("contest:7:rank:public")).thenReturn(15L);
            when(sequencer.next("contest:7:rank:public")).thenReturn(16L);

            ContestRankService s = service();
            assertThat(s.currentVersion("contest:7:rank:public")).isEqualTo(15L);
            assertThat(s.nextVersion("contest:7:rank:public")).isEqualTo(16L);
            verify(sequencer).next("contest:7:rank:public");
        }
    }

    @Nested
    @DisplayName("三段编码常量（与 lua/contest_rank_update.lua 必须一致）")
    class EncodingConstants {

        @Test
        @DisplayName("段基数与移位常量的算术关系成立（权重 ≤ 8999 的精度前提）")
        void segmentArithmetic() {
            // 权重段与罚时段之间的跨度由两基数相除得出
            assertThat(ContestRankService.WEIGHT_FACTOR).isEqualTo(1_000_000_000_000L);
            assertThat(ContestRankService.PENALTY_FACTOR).isEqualTo(1_000_000L);
            assertThat(ContestRankService.WEIGHT_FACTOR / ContestRankService.PENALTY_FACTOR)
                    .isEqualTo(1_000_000L);
            // 段内取反基数：解码 = SEGMENT_BASE - 段值
            assertThat(ContestRankService.SEGMENT_BASE).isEqualTo(999_999L);

            // 编码→解码 roundtrip：权重 3、罚时 1200s、末次通过偏移 300s
            long encoded = 3L * ContestRankService.WEIGHT_FACTOR
                    + (ContestRankService.SEGMENT_BASE - 1200) * ContestRankService.PENALTY_FACTOR
                    + (ContestRankService.SEGMENT_BASE - 300);
            long weight = encoded / ContestRankService.WEIGHT_FACTOR;
            long penalty = ContestRankService.SEGMENT_BASE
                    - (encoded / ContestRankService.PENALTY_FACTOR) % 1_000_000L;
            long tieOffset = ContestRankService.SEGMENT_BASE - (encoded % ContestRankService.PENALTY_FACTOR);
            assertThat(weight).isEqualTo(3L);
            assertThat(penalty).isEqualTo(1200L);
            assertThat(tieOffset).isEqualTo(300L);
        }
    }
}
