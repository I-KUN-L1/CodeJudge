package com.codejudge.contest.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.codejudge.api.client.problem.ProblemClient;
import com.codejudge.api.dto.problem.ProblemSummaryDTO;
import com.codejudge.contest.config.ContestProperties;
import com.codejudge.contest.domain.dto.ContestFormDTO;
import com.codejudge.contest.domain.po.Contest;
import com.codejudge.contest.domain.po.ContestProblem;
import com.codejudge.contest.domain.vo.ContestDetailVO;
import com.codejudge.contest.mapper.ContestMapper;
import com.codejudge.contest.mapper.ContestProblemMapper;
import com.codejudge.contest.mapper.ContestRegistrationMapper;
import com.codejudge.common.exceptions.BizIllegalException;
import com.codejudge.common.utils.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ContestService 建赛校验 / 事务委托 / 内部上下文 / CAS 单测。
 *
 * <p>运行：mvn -pl judge-contest -am test
 *
 * <p>覆盖：建赛窗口/赛制/编排校验（题目必须存在且已发布）、doCreate 的
 * 封榜时刻推导与题号/满分缺省、context 的实时状态推导（供提交受理校验）、
 * updateStatus 的 CAS 语义（多实例扫描安全）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContestServiceCreateContextTest {

    private static final Long CONTEST_ID = 6001L;
    private static final Long OWNER_ID = 9L;

    @Mock
    private ContestMapper contestMapper;
    @Mock
    private ContestProblemMapper contestProblemMapper;
    @Mock
    private ContestRegistrationMapper registrationMapper;
    @Mock
    private ProblemClient problemClient;
    @Mock
    private ObjectProvider<ContestService> self;
    @Mock
    private ContestService selfService;

    private final ContestProperties properties = new ContestProperties();

    private ContestService service;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        // LambdaUpdateWrapper.set() 会急切解析实体列名；单测无 MyBatis 容器，需手动注册 Contest 元数据
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Contest.class);
    }

    @BeforeEach
    void setUp() {
        service = new ContestService(contestMapper, contestProblemMapper, registrationMapper,
                properties, problemClient, self);
        when(self.getObject()).thenReturn(selfService);
    }

    @AfterEach
    void clearContext() {
        UserContext.remove();
    }

    private ContestFormDTO form(String rule, LocalDateTime start, LocalDateTime end,
                                Integer freezeMinutes, Integer penaltyMinutes) {
        ContestFormDTO f = new ContestFormDTO();
        f.setTitle("周赛 #1");
        f.setRule(rule);
        f.setStartTime(start);
        f.setEndTime(end);
        f.setFreezeMinutes(freezeMinutes);
        f.setPenaltyMinutes(penaltyMinutes);
        ContestFormDTO.ContestProblemForm item = new ContestFormDTO.ContestProblemForm();
        item.setProblemId(2001L);
        f.setProblems(List.of(item));
        return f;
    }

    private ProblemSummaryDTO summary(Long problemId, Integer status) {
        ProblemSummaryDTO s = new ProblemSummaryDTO();
        s.setProblemId(problemId);
        s.setTitle("两数之和");
        s.setStatus(status);
        return s;
    }

    @Nested
    @DisplayName("create：窗口与编排校验")
    class CreateValidation {

        @Test
        @DisplayName("结束时间不晚于开始时间 → 拒绝")
        void endMustBeAfterStart() {
            assertThatThrownBy(() -> service.create(form("ACM", LocalDateTime.now(),
                    LocalDateTime.now().minusMinutes(1), 0, null)))
                    .isInstanceOf(BizIllegalException.class)
                    .hasMessageContaining("结束时间必须晚于开始时间");
        }

        @Test
        @DisplayName("封榜时长 ≥ 总时长 → 拒绝（等于整场封榜，通常是填错）")
        void freezeMustBeShorterThanDuration() {
            LocalDateTime start = LocalDateTime.now();
            assertThatThrownBy(() -> service.create(form("ACM", start,
                    start.plusMinutes(60), 60, null)))
                    .isInstanceOf(BizIllegalException.class)
                    .hasMessageContaining("封榜时长必须小于竞赛总时长");
        }

        @Test
        @DisplayName("封榜时长为负 / 罚时为负 → 拒绝")
        void negativesRejected() {
            LocalDateTime start = LocalDateTime.now();
            assertThatThrownBy(() -> service.create(form("ACM", start, start.plusMinutes(60), -1, null)))
                    .isInstanceOf(BizIllegalException.class)
                    .hasMessageContaining("封榜时长不能为负");
            assertThatThrownBy(() -> service.create(form("ACM", start, start.plusMinutes(60), 0, -5)))
                    .isInstanceOf(BizIllegalException.class)
                    .hasMessageContaining("罚时不能为负");
        }

        @Test
        @DisplayName("同一题目重复编排 → 拒绝")
        void duplicateProblemsRejected() {
            ContestFormDTO f = form("ACM", LocalDateTime.now(), LocalDateTime.now().plusHours(2), 0, null);
            ContestFormDTO.ContestProblemForm dup = new ContestFormDTO.ContestProblemForm();
            dup.setProblemId(2001L);
            f.setProblems(List.of(f.getProblems().get(0), dup));

            assertThatThrownBy(() -> service.create(f))
                    .isInstanceOf(BizIllegalException.class)
                    .hasMessageContaining("重复编排");
        }

        @Test
        @DisplayName("题目服务不可用 → 拒绝建赛（必须知道题目是否合法）")
        void problemServiceDown() {
            ContestFormDTO f = form("ACM", LocalDateTime.now(), LocalDateTime.now().plusHours(2), 0, null);
            when(problemClient.listSummaries(anyList())).thenThrow(new RuntimeException("feign timeout"));

            assertThatThrownBy(() -> service.create(f))
                    .isInstanceOf(BizIllegalException.class)
                    .hasMessageContaining("题目服务不可用");
        }

        @Test
        @DisplayName("题目不存在 / 未发布 → 拒绝编排")
        void missingOrUnpublishedProblem() {
            ContestFormDTO f = form("ACM", LocalDateTime.now(), LocalDateTime.now().plusHours(2), 0, null);

            when(problemClient.listSummaries(anyList())).thenReturn(List.of());
            assertThatThrownBy(() -> service.create(f))
                    .isInstanceOf(BizIllegalException.class)
                    .hasMessageContaining("题目不存在");

            when(problemClient.listSummaries(anyList())).thenReturn(List.of(summary(2001L, 0)));
            assertThatThrownBy(() -> service.create(f))
                    .isInstanceOf(BizIllegalException.class)
                    .hasMessageContaining("题目未发布");
        }

        @Test
        @DisplayName("校验通过 → 经自代理调用 doCreate（保证 @Transactional 生效）")
        void delegatesToTransactionalSelf() {
            UserContext.setUser(OWNER_ID);
            ContestFormDTO f = form("ACM", LocalDateTime.now(), LocalDateTime.now().plusHours(2), 0, null);
            when(problemClient.listSummaries(anyList())).thenReturn(List.of(summary(2001L, 1)));
            ContestDetailVO expected = new ContestDetailVO();
            when(selfService.doCreate(f, OWNER_ID)).thenReturn(expected);

            assertThat(service.create(f)).isSameAs(expected);
            verify(selfService).doCreate(f, OWNER_ID);
        }
    }

    @Nested
    @DisplayName("doCreate：写库与缺省推导")
    class DoCreate {

        @Test
        @DisplayName("封榜时刻=结束-封榜时长；题号/满分缺省；IOI 归一大写")
        void derivesDefaults() {
            LocalDateTime start = LocalDateTime.now().minusHours(1);
            LocalDateTime end = LocalDateTime.now().plusHours(1);
            ContestFormDTO f = form("ioi", start, end, 30, null);
            f.getProblems().get(0).setLabel("");

            doAnswer(inv -> {
                inv.getArgument(0, Contest.class).setId(CONTEST_ID);
                return 1;
            }).when(contestMapper).insert(any(Contest.class));
            when(contestProblemMapper.insert(any(ContestProblem.class))).thenReturn(1);
            when(contestMapper.selectById(CONTEST_ID)).thenAnswer(inv -> {
                Contest c = new Contest();
                c.setId(CONTEST_ID);
                c.setTitle("周赛 #1");
                c.setRule("IOI");
                c.setStartTime(start);
                c.setEndTime(end);
                c.setFreezeAt(end.minusMinutes(30));
                c.setStatus(Contest.ST_RUNNING);
                return c;
            });
            when(contestProblemMapper.selectList(any())).thenReturn(List.of(contestProblem()));
            when(problemClient.listSummaries(anyList())).thenReturn(List.of(summary(2001L, 1)));
            when(registrationMapper.selectCount(any())).thenReturn(0L);

            service.doCreate(f, OWNER_ID);

            ArgumentCaptor<Contest> saved = ArgumentCaptor.forClass(Contest.class);
            verify(contestMapper).insert(saved.capture());
            assertThat(saved.getValue().getRule()).isEqualTo("IOI");                 // 归一大写
            assertThat(saved.getValue().getFreezeAt()).isEqualTo(end.minusMinutes(30)); // 由结束时间推导
            assertThat(saved.getValue().getStatus()).isEqualTo(Contest.ST_RUNNING);  // 实时推导
            assertThat(saved.getValue().getPenaltyMinutes()).isEqualTo(20);          // ICPC 惯例缺省

            ArgumentCaptor<ContestProblem> savedProblem = ArgumentCaptor.forClass(ContestProblem.class);
            verify(contestProblemMapper).insert(savedProblem.capture());
            assertThat(savedProblem.getValue().getLabel()).isEqualTo("A");           // 缺省题号 A~Z
            assertThat(savedProblem.getValue().getFullScore()).isEqualTo(100);       // 满分缺省 100
            assertThat(savedProblem.getValue().getSubmitCount()).isZero();
        }
    }

    @Nested
    @DisplayName("updateStatus：CAS 语义")
    class CasUpdate {

        @Test
        @DisplayName("更新 1 行 = 迁移成功；0 行 = 其他实例已推进")
        void casSemantics() {
            when(contestMapper.update(eq(null), any())).thenReturn(1).thenReturn(0);
            Contest c = new Contest();
            c.setId(CONTEST_ID);

            assertThat(service.updateStatus(c, Contest.ST_NOT_STARTED, Contest.ST_RUNNING)).isTrue();
            assertThat(service.updateStatus(c, Contest.ST_NOT_STARTED, Contest.ST_RUNNING)).isFalse();
        }
    }

    @Nested
    @DisplayName("context：提交受理用内部上下文")
    class Context {

        @Test
        @DisplayName("状态按当前时间实时推导（快照列滞后不误拒）；题目/满分/报名要求齐备")
        void buildsRealtimeContext() {
            Contest c = new Contest();
            c.setId(CONTEST_ID);
            c.setTitle("周赛 #1");
            c.setRule("acm");
            c.setStartTime(LocalDateTime.now().minusMinutes(5)); // 刚开赛
            c.setEndTime(LocalDateTime.now().plusHours(2));
            c.setFreezeAt(LocalDateTime.now().plusHours(1));
            c.setFreezeMinutes(60);
            c.setPenaltyMinutes(20);
            c.setStatus(Contest.ST_NOT_STARTED); // 落库快照滞后
            when(contestMapper.selectById(CONTEST_ID)).thenReturn(c);
            when(contestProblemMapper.selectList(any())).thenReturn(List.of(contestProblem()));
            when(registrationMapper.selectCount(any())).thenReturn(1L);
            UserContext.setUser(1001L);

            var dto = service.context(CONTEST_ID, 1001L);

            assertThat(dto.getStatus()).isEqualTo(Contest.ST_RUNNING); // 实时推导而非快照列
            assertThat(dto.getRule()).isEqualTo("ACM");
            assertThat(dto.getNeedRegister()).isTrue();
            assertThat(dto.getProblemIds()).containsExactly(2001L);
            assertThat(dto.getFullScores()).containsEntry("2001", 100);
            assertThat(dto.getRegistered()).isTrue();
        }

        @Test
        @DisplayName("匿名内部调用（userId=null）→ 不查报名状态")
        void anonymousOmitsRegistered() {
            Contest c = new Contest();
            c.setId(CONTEST_ID);
            c.setRule("ACM");
            c.setStartTime(LocalDateTime.now().minusMinutes(5));
            c.setEndTime(LocalDateTime.now().plusHours(2));
            when(contestMapper.selectById(CONTEST_ID)).thenReturn(c);
            when(contestProblemMapper.selectList(any())).thenReturn(List.of());

            var dto = service.context(CONTEST_ID, null);

            assertThat(dto.getRegistered()).isNull();
            verify(registrationMapper, never()).selectCount(any());
        }
    }

    @Nested
    @DisplayName("requireContest：守卫")
    class RequireContest {

        @Test
        @DisplayName("id 为空 → 400；竞赛不存在 → 404")
        void guards() {
            assertThatThrownBy(() -> service.requireContest(null))
                    .isInstanceOf(BizIllegalException.class)
                    .hasMessageContaining("不能为空");
            when(contestMapper.selectById(CONTEST_ID)).thenReturn(null);
            assertThatThrownBy(() -> service.requireContest(CONTEST_ID))
                    .isInstanceOf(BizIllegalException.class)
                    .hasMessageContaining("竞赛不存在");
        }
    }

    @Nested
    @DisplayName("titlesOf：题目标题（降级不致命）")
    class Titles {

        @Test
        @DisplayName("空入参 → 空表")
        void emptyInputIsEmpty() {
            assertThat(service.titlesOf(null)).isEmpty();
            assertThat(service.titlesOf(List.of())).isEmpty();
        }

        @Test
        @DisplayName("Feign 失败 → 空表（页面降级为只显示题号）")
        void feignFailureFallsBackToEmpty() {
            when(problemClient.listSummaries(anyList())).thenThrow(new RuntimeException("down"));

            assertThat(service.titlesOf(List.of(2001L))).isEmpty();
        }

        @Test
        @DisplayName("null 标题 → 空串占位")
        void nullTitleMappedToEmptyString() {
            ProblemSummaryDTO s = summary(2001L, 1);
            s.setTitle(null);
            when(problemClient.listSummaries(anyList())).thenReturn(List.of(s));

            assertThat(service.titlesOf(List.of(2001L))).containsEntry(2001L, "");
        }
    }

    private ContestProblem contestProblem() {
        ContestProblem cp = new ContestProblem();
        cp.setContestId(CONTEST_ID);
        cp.setProblemId(2001L);
        cp.setLabel("A");
        cp.setDisplayOrder(0);
        cp.setFullScore(100);
        return cp;
    }
}
