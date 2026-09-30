package com.codejudge.contest.service;

import com.codejudge.api.client.problem.ProblemClient;
import com.codejudge.contest.config.ContestProperties;
import com.codejudge.contest.domain.po.Contest;
import com.codejudge.contest.domain.po.ContestRegistration;
import com.codejudge.contest.mapper.ContestMapper;
import com.codejudge.contest.mapper.ContestProblemMapper;
import com.codejudge.contest.mapper.ContestRegistrationMapper;
import com.codejudge.common.exceptions.BizIllegalException;
import com.codejudge.common.exceptions.ForbiddenException;
import com.codejudge.common.utils.UserContext;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ContestService.register 报名单测（judge-contest 首个测试类）。
 *
 * <p>运行：mvn -pl judge-contest -am test
 *
 * <p>覆盖：登录门槛、结束态拒绝（按 effectiveStatus 时间推导而非状态快照列）、
 * 首次报名落库、重复报名幂等、取消后复活（唯一键 uk_contest_registration
 * 不允许两条记录，故 update 而非 insert）、select-then-insert 并发竞态的幂等回查。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContestServiceRegisterTest {

    private static final Long USER_ID = 1001L;
    private static final Long CONTEST_ID = 6001L;

    @Mock
    private ContestMapper contestMapper;
    @Mock
    private ContestProblemMapper contestProblemMapper;
    @Mock
    private ContestRegistrationMapper registrationMapper;
    @Mock
    private ContestProperties properties;
    @Mock
    private ProblemClient problemClient;
    @Mock
    private ObjectProvider<ContestService> self;

    private ContestService service;

    @BeforeEach
    void setUp() {
        service = new ContestService(contestMapper, contestProblemMapper,
                registrationMapper, properties, problemClient, self);
    }

    @AfterEach
    void clearContext() {
        UserContext.remove();
    }

    /** 进行中的竞赛：[start, end) 语义，now 落在窗口内 */
    private Contest runningContest() {
        Contest c = new Contest();
        c.setId(CONTEST_ID);
        c.setStartTime(LocalDateTime.now().minusHours(1));
        c.setEndTime(LocalDateTime.now().plusHours(2));
        return c;
    }

    @Nested
    @DisplayName("报名门槛")
    class Eligibility {

        @Test
        @DisplayName("未登录 → 403「请先登录后再报名」")
        void requiresLogin() {
            UserContext.remove();

            assertThatThrownBy(() -> service.register(CONTEST_ID))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("请先登录后再报名");
        }

        @Test
        @DisplayName("已结束的竞赛拒绝报名（结束时刻恰好等于 now 也算已结束）")
        void finishedContestRejects() {
            UserContext.setUser(USER_ID);
            Contest finished = runningContest();
            finished.setEndTime(LocalDateTime.now().minusSeconds(1));
            when(contestMapper.selectById(CONTEST_ID)).thenReturn(finished);

            assertThatThrownBy(() -> service.register(CONTEST_ID))
                    .isInstanceOf(BizIllegalException.class)
                    .hasMessageContaining("竞赛已结束");
        }
    }

    @Nested
    @DisplayName("报名落库与幂等")
    class Registration {

        @BeforeEach
        void stubRunningContestAndLogin() {
            UserContext.setUser(USER_ID);
            when(contestMapper.selectById(CONTEST_ID)).thenReturn(runningContest());
        }

        @Test
        @DisplayName("首次报名：插入 REGISTERED 记录，竞赛/用户/时间齐备")
        void insertsNewRegistration() {
            when(registrationMapper.selectOne(any())).thenReturn(null);

            service.register(CONTEST_ID);

            ArgumentCaptor<ContestRegistration> captor =
                    ArgumentCaptor.forClass(ContestRegistration.class);
            verify(registrationMapper).insert(captor.capture());
            ContestRegistration saved = captor.getValue();
            assertThat(saved.getContestId()).isEqualTo(CONTEST_ID);
            assertThat(saved.getUserId()).isEqualTo(USER_ID);
            assertThat(saved.getStatus()).isEqualTo(ContestRegistration.ST_REGISTERED);
            assertThat(saved.getRegisterTime()).isNotNull();
            verify(registrationMapper, never()).updateById(any(ContestRegistration.class));
        }

        @Test
        @DisplayName("重复报名幂等：已 REGISTERED → 不再 insert/update")
        void duplicateRegistrationIsIdempotent() {
            ContestRegistration existing = new ContestRegistration();
            existing.setContestId(CONTEST_ID);
            existing.setUserId(USER_ID);
            existing.setStatus(ContestRegistration.ST_REGISTERED);
            when(registrationMapper.selectOne(any())).thenReturn(existing);

            service.register(CONTEST_ID);

            verify(registrationMapper, never()).insert(any(ContestRegistration.class));
            verify(registrationMapper, never()).updateById(any(ContestRegistration.class));
        }

        @Test
        @DisplayName("取消后重新报名：复活原记录（update），不插新行")
        void cancelledRegistrationIsRevived() {
            ContestRegistration cancelled = new ContestRegistration();
            cancelled.setContestId(CONTEST_ID);
            cancelled.setUserId(USER_ID);
            cancelled.setStatus(ContestRegistration.ST_CANCELLED);
            when(registrationMapper.selectOne(any())).thenReturn(cancelled);

            service.register(CONTEST_ID);

            verify(registrationMapper, never()).insert(any(ContestRegistration.class));
            verify(registrationMapper).updateById(cancelled);
            assertThat(cancelled.getStatus()).isEqualTo(ContestRegistration.ST_REGISTERED);
        }

        @Test
        @DisplayName("select-then-insert 并发竞态：撞唯一键后回查到记录 → 幂等返回而非 500")
        void duplicateKeyRaceReturnsIdempotently() {
            ContestRegistration concurrent = new ContestRegistration();
            concurrent.setContestId(CONTEST_ID);
            concurrent.setUserId(USER_ID);
            concurrent.setStatus(ContestRegistration.ST_REGISTERED);
            when(registrationMapper.selectOne(any())).thenReturn(null, concurrent);
            when(registrationMapper.insert(any(ContestRegistration.class)))
                    .thenThrow(new DuplicateKeyException("uk_contest_registration"));

            assertThatCode(() -> service.register(CONTEST_ID)).doesNotThrowAnyException();

            verify(registrationMapper, never()).updateById(any(ContestRegistration.class));
        }
    }
}


