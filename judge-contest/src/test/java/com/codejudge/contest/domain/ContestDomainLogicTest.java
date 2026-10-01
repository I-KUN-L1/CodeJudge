package com.codejudge.contest.domain;

import com.codejudge.contest.domain.po.Contest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contest 领域逻辑单测（生命周期/封窗/赛制/罚时的纯函数部分）。
 *
 * <p>运行：mvn -pl judge-contest -am test
 *
 * <p>覆盖：effectiveStatus 的边界语义（恰等于 end 即已结束，与快照扫描一致）、
 * frozenAt 的三态（未配置封榜恒 false / 窗口内 true / 赛后 false）、
 * isAcm 的未知赛制回退 ACM、penaltyMinutesOrDefault 的默认罚时。
 */
class ContestDomainLogicTest {

    private Contest contest(LocalDateTime start, LocalDateTime end, LocalDateTime freezeAt) {
        Contest c = new Contest();
        c.setStartTime(start);
        c.setEndTime(end);
        c.setFreezeAt(freezeAt);
        return c;
    }

    @Nested
    @DisplayName("effectiveStatus：按时间推导真实状态")
    class EffectiveStatus {

        private final LocalDateTime start = LocalDateTime.of(2026, 10, 1, 9, 0);
        private final LocalDateTime end = LocalDateTime.of(2026, 10, 1, 11, 0);

        @Test
        @DisplayName("开始前 → 未开始")
        void beforeStart() {
            assertThat(contest(start, end, null).effectiveStatus(start.minusSeconds(1)))
                    .isEqualTo(Contest.ST_NOT_STARTED);
        }

        @Test
        @DisplayName("[start, end) 内 → 进行中（含恰好 start）")
        void running() {
            Contest c = contest(start, end, null);
            assertThat(c.effectiveStatus(start)).isEqualTo(Contest.ST_RUNNING);
            assertThat(c.effectiveStatus(end.minusSeconds(1))).isEqualTo(Contest.ST_RUNNING);
        }

        @Test
        @DisplayName("恰等于 end 即已结束（边界语义，与快照扫描一致）")
        void exactlyEndIsFinished() {
            assertThat(contest(start, end, null).effectiveStatus(end))
                    .isEqualTo(Contest.ST_FINISHED);
            assertThat(contest(start, end, null).effectiveStatus(end.plusSeconds(1)))
                    .isEqualTo(Contest.ST_FINISHED);
        }
    }

    @Nested
    @DisplayName("frozenAt：封榜窗口三态")
    class FrozenAt {

        private final LocalDateTime start = LocalDateTime.of(2026, 10, 1, 9, 0);
        private final LocalDateTime end = LocalDateTime.of(2026, 10, 1, 11, 0);
        private final LocalDateTime freezeAt = LocalDateTime.of(2026, 10, 1, 10, 30);

        @Test
        @DisplayName("未配置封榜（freezeAt=null）→ 恒为 false")
        void noFreezeConfigured() {
            assertThat(contest(start, end, null).frozenAt(end.minusSeconds(1))).isFalse();
        }

        @Test
        @DisplayName("封榜时刻之前 → false；窗口内（含恰好 freezeAt）→ true")
        void window() {
            Contest c = contest(start, end, freezeAt);
            assertThat(c.frozenAt(freezeAt.minusSeconds(1))).isFalse();
            assertThat(c.frozenAt(freezeAt)).isTrue();
            assertThat(c.frozenAt(end.minusSeconds(1))).isTrue();
        }

        @Test
        @DisplayName("竞赛已结束 → 不再处于封榜窗口")
        void afterEndNotFrozen() {
            assertThat(contest(start, end, freezeAt).frozenAt(end)).isFalse();
        }
    }

    @Nested
    @DisplayName("赛制与罚时")
    class RuleAndPenalty {

        @Test
        @DisplayName("isAcm：IOI 忽略大小写；未知/null 赛制按 ACM 处理（fail-safe）")
        void ruleParsing() {
            Contest c = new Contest();
            c.setRule(Contest.RULE_ACM);
            assertThat(c.isAcm()).isTrue();
            c.setRule(Contest.RULE_IOI);
            assertThat(c.isAcm()).isFalse();
            c.setRule("ioi");
            assertThat(c.isAcm()).isFalse();
            c.setRule(null);
            assertThat(c.isAcm()).isTrue();
        }

        @Test
        @DisplayName("罚时未配置 → 默认 20 分钟（ICPC 惯例）")
        void penaltyDefault() {
            assertThat(new Contest().penaltyMinutesOrDefault()).isEqualTo(20);
            Contest c = new Contest();
            c.setPenaltyMinutes(10);
            assertThat(c.penaltyMinutesOrDefault()).isEqualTo(10);
        }
    }
}
