package com.codejudge.submission.service;

import com.codejudge.api.client.contest.ContestClient;
import com.codejudge.api.client.problem.ProblemClient;
import com.codejudge.api.dto.contest.ContestContextDTO;
import com.codejudge.api.dto.problem.JudgeCaseDTO;
import com.codejudge.api.dto.problem.JudgeInfoDTO;
import com.codejudge.api.dto.submission.SubmissionReviewContextDTO;
import com.codejudge.common.domain.PageDTO;
import com.codejudge.common.exceptions.BadRequestException;
import com.codejudge.common.exceptions.BizIllegalException;
import com.codejudge.common.exceptions.ForbiddenException;
import com.codejudge.common.utils.UserContext;
import com.codejudge.submission.config.JudgeProperties;
import com.codejudge.submission.domain.dto.SubmissionFormDTO;
import com.codejudge.submission.domain.po.CompileInfo;
import com.codejudge.submission.domain.po.JudgeResult;
import com.codejudge.submission.domain.po.JudgeTask;
import com.codejudge.submission.domain.po.Submission;
import com.codejudge.submission.domain.vo.CaseResultVO;
import com.codejudge.submission.domain.vo.SubmissionDetailVO;
import com.codejudge.submission.domain.vo.SubmissionVO;
import com.codejudge.submission.mapper.CompileInfoMapper;
import com.codejudge.submission.mapper.JudgeResultMapper;
import com.codejudge.submission.mapper.JudgeTaskMapper;
import com.codejudge.submission.mapper.SubmissionMapper;
import com.codejudge.submission.mq.JudgeEventPublisher;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SubmissionService 单元测试（judge-submission 首个测试类）。
 *
 * <p>运行：mvn -pl judge-submission -am test
 *
 * <p>覆盖：提交受理校验（语言/代码长度/限流/题目状态/竞赛上下文）、
 * 双层幂等（Redis 快路径 + DB 在途兜底 + 唯一索引竞态）、
 * 详情越权与隐藏用例 fail-closed 遮蔽、点评上下文采样、重判归属校验。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SubmissionServiceTest {

    private static final Long USER = 1001L;
    private static final Long OTHER_USER = 2002L;
    private static final Long PROBLEM_ID = 4001L;

    @Mock private SubmissionMapper submissionMapper;
    @Mock private JudgeTaskMapper judgeTaskMapper;
    @Mock private JudgeResultMapper judgeResultMapper;
    @Mock private CompileInfoMapper compileInfoMapper;
    @Mock private ProblemClient problemClient;
    @Mock private ContestClient contestClient;
    @Mock private JudgeEventPublisher eventPublisher;
    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private ZSetOperations<String, String> zSetOps;
    @Mock private ObjectProvider<SubmissionService> self;

    private final JudgeProperties judgeProperties = new JudgeProperties();
    private SubmissionService service;

    @org.junit.jupiter.api.BeforeAll
    static void initMpTableInfo() {
        // LambdaUpdateWrapper.set(SFunction,...) 需要 MP 的实体 lambda 缓存，纯单测环境手工初始化
        com.baomidou.mybatisplus.core.MybatisConfiguration cfg = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        org.apache.ibatis.builder.MapperBuilderAssistant assistant =
                new org.apache.ibatis.builder.MapperBuilderAssistant(cfg, "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, Submission.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, JudgeTask.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, JudgeResult.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, CompileInfo.class);
    }

    @BeforeEach
    void setUp() {
        UserContext.setUser(USER);
        UserContext.setRole(2); // 学员

        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.opsForZSet()).thenReturn(zSetOps);
        when(valueOps.increment(anyString())).thenReturn(1L);
        when(valueOps.get(anyString())).thenReturn(null);
        when(redis.getExpire(anyString())).thenReturn(60L);

        service = new SubmissionService(submissionMapper, judgeTaskMapper, judgeResultMapper,
                compileInfoMapper, problemClient, contestClient, eventPublisher, redis,
                judgeProperties, self);
    }

    @AfterEach
    void tearDown() {
        UserContext.remove();
    }

    // ---------- 测试数据工厂 ----------

    private SubmissionFormDTO form(String language, String code) {
        SubmissionFormDTO f = new SubmissionFormDTO();
        f.setProblemId(PROBLEM_ID);
        f.setLanguage(language);
        f.setCode(code);
        return f;
    }

    private void mockPublishedProblem() {
        JudgeInfoDTO info = new JudgeInfoDTO();
        info.setProblemId(PROBLEM_ID);
        info.setStatus(1);
        info.setOwnerId(3L);
        when(problemClient.getJudgeInfo(PROBLEM_ID)).thenReturn(info);
    }

    private Submission existingSubmission(long id, String status) {
        Submission s = new Submission();
        s.setId(id);
        s.setUserId(USER);
        s.setProblemId(PROBLEM_ID);
        s.setContestId(0L);
        s.setLanguage("PYTHON");
        s.setCode("print(1)");
        s.setCodeHash(SubmissionService.sha256Hex("print(1)"));
        s.setSubmitRound(0);
        s.setStatus(status);
        s.setScore(0);
        s.setSubmitTime(LocalDateTime.now());
        return s;
    }

    /** 让 self.getObject() 返回一个可拦截 createSubmissionWithTask 的替身 */
    private void stubCreateReturns(Submission saved) {
        SubmissionService delegate = mock(SubmissionService.class);
        when(delegate.createSubmissionWithTask(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                any(), any(), anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(saved);
        when(self.getObject()).thenReturn(delegate);
    }

    // ==================== submit：参数校验 ====================

    @Nested
    @DisplayName("提交受理 - 参数校验")
    class SubmitValidation {

        @Test
        @DisplayName("语言为 null → 400")
        void nullLanguage() {
            assertThatThrownBy(() -> service.submit(form(null, "print(1)")))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("不支持的语言");
            verify(problemClient, never()).getJudgeInfo(any());
        }

        @Test
        @DisplayName("不支持的语言 RUST → 400")
        void unsupportedLanguage() {
            assertThatThrownBy(() -> service.submit(form("RUST", "println!(1)")))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("RUST");
        }

        @Test
        @DisplayName("小写 python 等价类 → 归一化为 PYTHON 通过")
        void lowercaseLanguageAccepted() {
            mockPublishedProblem();
            stubCreateReturns(savedSubmission(501L));
            SubmissionVO vo = service.submit(form("python", "print(1)"));
            assertThat(vo.getStatus()).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("代码恰好 32KB（边界 max）→ 通过")
        void codeExactly32kb() {
            mockPublishedProblem();
            stubCreateReturns(savedSubmission(502L));
            String code = "a".repeat(32 * 1024);
            SubmissionVO vo = service.submit(form("PYTHON", code));
            assertThat(vo.getId()).isEqualTo(502L);
        }

        @Test
        @DisplayName("代码 32KB+1 字节（边界 max+1）→ 拒绝")
        void codeOver32kb() {
            String code = "a".repeat(32 * 1024 + 1);
            assertThatThrownBy(() -> service.submit(form("PYTHON", code)))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("32KB");
        }

        @Test
        @DisplayName("32K 个 CJK 字符按字符数不超限但按字节数超限 → 拒绝（TEXT 64KB 字节上限）")
        void cjkCodeByteLimit() {
            String code = "测".repeat(32 * 1024); // 32768 字符 = 98304 字节
            assertThatThrownBy(() -> service.submit(form("PYTHON", code)))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("98304");
        }
    }

    // ==================== submit：限流 ====================

    @Nested
    @DisplayName("提交受理 - 频控")
    class RateLimit {

        @Test
        @DisplayName("超过每分钟 30 次 → 400")
        void overLimit() {
            when(valueOps.increment(anyString())).thenReturn(31L);
            assertThatThrownBy(() -> service.submit(form("PYTHON", "print(1)")))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("提交过于频繁");
        }

        @Test
        @DisplayName("首次提交 INCR=1 → 补设 60s TTL")
        void firstIncrSetsTtl() {
            mockPublishedProblem();
            stubCreateReturns(savedSubmission(503L));
            service.submit(form("PYTHON", "print(1)"));
            verify(redis).expire(contains("rate:" + USER), eq(java.time.Duration.ofSeconds(60)));
        }

        @Test
        @DisplayName("INCR/EXPIRE 非原子残留无 TTL key（getExpire=-1）→ 补偿 TTL（防永久限流）")
        void missingTtlCompensated() {
            when(valueOps.increment(anyString())).thenReturn(5L);
            when(redis.getExpire(anyString())).thenReturn(-1L);
            mockPublishedProblem();
            stubCreateReturns(savedSubmission(504L));
            service.submit(form("PYTHON", "print(1)"));
            verify(redis, org.mockito.Mockito.times(1))
                    .expire(anyString(), eq(java.time.Duration.ofSeconds(60)));
        }
    }

    // ==================== submit：题目与竞赛校验 ====================

    @Nested
    @DisplayName("提交受理 - 题目/竞赛校验")
    class ProblemAndContest {

        @Test
        @DisplayName("题目服务不可用（Feign 异常）→ 快速失败 400")
        void problemServiceDown() {
            when(problemClient.getJudgeInfo(PROBLEM_ID)).thenThrow(new RuntimeException("connect timeout"));
            assertThatThrownBy(() -> service.submit(form("PYTHON", "print(1)")))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("题目服务暂不可用");
        }

        @Test
        @DisplayName("题目草稿(0)/下线(2)/状态null → 拒绝提交")
        void problemNotPublished() {
            for (Integer status : new Integer[]{0, 2, null}) {
                JudgeInfoDTO info = new JudgeInfoDTO();
                info.setProblemId(PROBLEM_ID);
                info.setStatus(status);
                when(problemClient.getJudgeInfo(PROBLEM_ID)).thenReturn(info);
                assertThatThrownBy(() -> service.submit(form("PYTHON", "print(1)")))
                        .as("status=%s 应拒绝", status)
                        .isInstanceOf(BizIllegalException.class)
                        .hasMessageContaining("题目不存在或未发布");
            }
        }

        @Test
        @DisplayName("竞赛未开始(status=0) → 拒绝")
        void contestNotStarted() {
            mockPublishedProblem();
            ContestContextDTO ctx = new ContestContextDTO();
            ctx.setStatus(0);
            when(contestClient.getContext(77L, USER)).thenReturn(ctx);
            SubmissionFormDTO f = form("PYTHON", "print(1)");
            f.setContestId(77L);
            assertThatThrownBy(() -> service.submit(f))
                    .isInstanceOf(BizIllegalException.class)
                    .hasMessageContaining("尚未开始");
        }

        @Test
        @DisplayName("竞赛已结束(status=2) → 拒绝")
        void contestEnded() {
            mockPublishedProblem();
            ContestContextDTO ctx = new ContestContextDTO();
            ctx.setStatus(2);
            when(contestClient.getContext(77L, USER)).thenReturn(ctx);
            SubmissionFormDTO f = form("PYTHON", "print(1)");
            f.setContestId(77L);
            assertThatThrownBy(() -> service.submit(f))
                    .isInstanceOf(BizIllegalException.class)
                    .hasMessageContaining("已结束");
        }

        @Test
        @DisplayName("题目不属于该竞赛 → 拒绝（防污染榜单）")
        void problemNotInContest() {
            mockPublishedProblem();
            ContestContextDTO ctx = new ContestContextDTO();
            ctx.setStatus(1);
            ctx.setProblemIds(List.of(9999L));
            when(contestClient.getContext(77L, USER)).thenReturn(ctx);
            SubmissionFormDTO f = form("PYTHON", "print(1)");
            f.setContestId(77L);
            assertThatThrownBy(() -> service.submit(f))
                    .isInstanceOf(BizIllegalException.class)
                    .hasMessageContaining("不属于竞赛");
        }

        @Test
        @DisplayName("需要报名且未报名 → 403")
        void contestNotRegistered() {
            mockPublishedProblem();
            ContestContextDTO ctx = new ContestContextDTO();
            ctx.setStatus(1);
            ctx.setProblemIds(List.of(PROBLEM_ID));
            ctx.setNeedRegister(true);
            ctx.setRegistered(false);
            when(contestClient.getContext(77L, USER)).thenReturn(ctx);
            SubmissionFormDTO f = form("PYTHON", "print(1)");
            f.setContestId(77L);
            assertThatThrownBy(() -> service.submit(f))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("先报名");
        }

        @Test
        @DisplayName("竞赛服务不可用 → 快速失败 400")
        void contestServiceDown() {
            mockPublishedProblem();
            when(contestClient.getContext(77L, USER)).thenThrow(new RuntimeException("timeout"));
            SubmissionFormDTO f = form("PYTHON", "print(1)");
            f.setContestId(77L);
            assertThatThrownBy(() -> service.submit(f))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("竞赛服务暂不可用");
        }
    }

    // ==================== submit：三层幂等 ====================

    @Nested
    @DisplayName("提交受理 - 幂等")
    class Idempotency {

        @Test
        @DisplayName("Redis 幂等 key 命中 → 返回已有提交，不再落库")
        void redisFastPathHit() {
            mockPublishedProblem();
            when(valueOps.get(anyString())).thenReturn("999");
            when(submissionMapper.selectById(999L)).thenReturn(existingSubmission(999L, "JUDGING"));

            SubmissionVO vo = service.submit(form("PYTHON", "print(1)"));
            assertThat(vo.getId()).isEqualTo(999L);
            assertThat(vo.getIdempotent()).isTrue();
            verify(submissionMapper, never()).insert(any(Submission.class));
        }

        @Test
        @DisplayName("Redis 命中但记录已被删除（陈旧 key）→ 穿透继续走 DB/落库")
        void staleRedisKeyFallsThrough() {
            mockPublishedProblem();
            when(valueOps.get(anyString())).thenReturn("999");
            when(submissionMapper.selectById(999L)).thenReturn(null);
            stubCreateReturns(savedSubmission(505L));

            SubmissionVO vo = service.submit(form("PYTHON", "print(1)"));
            assertThat(vo.getId()).isEqualTo(505L);
            assertThat(vo.getIdempotent()).isFalse();
        }

        @Test
        @DisplayName("DB 在途同码（PENDING）→ 幂等返回并回写 Redis key")
        void inFlightSameCode() {
            mockPublishedProblem();
            when(submissionMapper.selectOne(any(LambdaQueryWrapper.class)))
                    .thenReturn(existingSubmission(777L, "PENDING"));
            SubmissionVO vo = service.submit(form("PYTHON", "print(1)"));
            assertThat(vo.getId()).isEqualTo(777L);
            assertThat(vo.getIdempotent()).isTrue();
            verify(valueOps).set(anyString(), eq("777"),
                    eq(java.time.Duration.ofSeconds(judgeProperties.getIdempotentTtlSeconds())));
            verify(eventPublisher, never()).publishTaskCreated(any(), any(), org.mockito.ArgumentMatchers.anyInt());
        }

        @Test
        @DisplayName("同码已终态（SUCCESS）→ 轮次+1 落新行")
        void terminalSameCodeNewRound() {
            mockPublishedProblem();
            when(submissionMapper.selectOne(any(LambdaQueryWrapper.class)))
                    .thenReturn(existingSubmission(777L, "SUCCESS"));
            stubCreateReturns(savedSubmission(506L));

            SubmissionVO vo = service.submit(form("PYTHON", "print(1)"));
            assertThat(vo.getIdempotent()).isFalse();

            ArgumentCaptor<Submission> captor = ArgumentCaptor.forClass(Submission.class);
            SubmissionService delegate = self.getObject();
            verify(delegate).createSubmissionWithTask(eq(USER), eq(PROBLEM_ID), eq(0L), any(), any(), anyString(), eq(1));
        }

        @Test
        @DisplayName("并发穿透：唯一索引拦截 + 回查已有 → 返回已有记录（注：当前 VO idempotent=false，见缺陷报告）")
        void duplicateKeyRace() {
            mockPublishedProblem();
            // selectOne：submit 主流程首查返回 null（无在途记录）→ DuplicateKey 回查返回已有
            java.util.concurrent.atomic.AtomicBoolean raced = new java.util.concurrent.atomic.AtomicBoolean(false);
            when(submissionMapper.selectOne(any(LambdaQueryWrapper.class)))
                    .thenAnswer(inv -> raced.getAndSet(true) ? existingSubmission(888L, "PENDING") : null);
            when(submissionMapper.insert(any(Submission.class)))
                    .thenThrow(new DuplicateKeyException("uk_submission_idempotent"));

            SubmissionService realService = new SubmissionService(submissionMapper, judgeTaskMapper,
                    judgeResultMapper, compileInfoMapper, problemClient, contestClient, eventPublisher,
                    redis, judgeProperties, self);
            SubmissionService selfDelegate = mock(SubmissionService.class);
            when(selfDelegate.createSubmissionWithTask(org.mockito.ArgumentMatchers.anyLong(),
                    org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                    any(), any(), anyString(), org.mockito.ArgumentMatchers.anyInt()))
                    .thenAnswer(inv -> realService.createSubmissionWithTask(
                            inv.getArgument(0), inv.getArgument(1), inv.getArgument(2),
                            inv.getArgument(3), inv.getArgument(4), inv.getArgument(5), inv.getArgument(6)));
            when(self.getObject()).thenReturn(selfDelegate);

            SubmissionVO vo = realService.submit(form("PYTHON", "print(1)"));
            assertThat(vo.getId()).isEqualTo(888L);
        }

        @Test
        @DisplayName("落库成功 → 发布 CREATED 事件 + 写幂等 key + 入待判 ZSet")
        void successfulSubmissionSideEffects() {
            mockPublishedProblem();
            Submission saved = savedSubmission(507L);
            saved.setTaskId(707L);
            stubCreateReturns(saved);

            SubmissionVO vo = service.submit(form("PYTHON", "print(1)"));

            assertThat(vo.getStatus()).isEqualTo("PENDING");
            assertThat(vo.getScore()).isZero();
            verify(valueOps).set(contains(USER + ":" + PROBLEM_ID), eq("507"), any());
            verify(zSetOps).add(eq("judge:judge:queue:zset"), eq("507"), anyDouble());
        }

        @Test
        @DisplayName("真实落库方法：submission+judge_task 同生共死，落库后立即发布 CREATED（无事务上下文）")
        void createSubmissionWithTaskPublishesAfterCommit() {
            when(submissionMapper.insert(any(Submission.class))).thenAnswer(inv -> {
                Submission s = inv.getArgument(0);
                s.setId(600L);
                return 1;
            });
            when(judgeTaskMapper.insert(any(JudgeTask.class))).thenAnswer(inv -> {
                JudgeTask t = inv.getArgument(0);
                t.setId(800L);
                return 1;
            });
            SubmissionService realService = new SubmissionService(submissionMapper, judgeTaskMapper,
                    judgeResultMapper, compileInfoMapper, problemClient, contestClient, eventPublisher,
                    redis, judgeProperties, self);

            Submission saved = realService.createSubmissionWithTask(USER, PROBLEM_ID, 0L,
                    com.codejudge.api.dto.submission.Language.PYTHON, "print(1)",
                    SubmissionService.sha256Hex("print(1)"), 0);

            assertThat(saved.getId()).isEqualTo(600L);
            assertThat(saved.getTaskId()).isEqualTo(800L);
            verify(judgeTaskMapper).insert(any(JudgeTask.class));
            verify(eventPublisher).publishTaskCreated(600L, 800L, 0);
        }
    }

    // ==================== queryDetail：越权与隐藏用例 ====================

    @Nested
    @DisplayName("提交详情 - 越权与信息遮蔽")
    class QueryDetail {

        @Test
        @DisplayName("学员查看他人提交 → 403")
        void otherStudentForbidden() {
            Submission s = existingSubmission(900L, "SUCCESS");
            s.setUserId(OTHER_USER);
            when(submissionMapper.selectById(900L)).thenReturn(s);
            assertThatThrownBy(() -> service.queryDetail(900L))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("无权查看他人提交");
        }

        @Test
        @DisplayName("提交不存在 → 404 语义业务异常")
        void notFound() {
            when(submissionMapper.selectById(1L)).thenReturn(null);
            assertThatThrownBy(() -> service.queryDetail(1L))
                    .isInstanceOf(BizIllegalException.class)
                    .hasMessageContaining("提交不存在");
        }

        @Test
        @DisplayName("教师(role=3)可查看他人提交")
        void teacherCanViewOthers() {
            UserContext.setRole(3);
            Submission s = existingSubmission(900L, "SUCCESS");
            s.setUserId(OTHER_USER);
            when(submissionMapper.selectById(900L)).thenReturn(s);
            when(judgeResultMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
            when(compileInfoMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
            assertThat(service.queryDetail(900L).getUserId()).isEqualTo(OTHER_USER);
        }

        @Test
        @DisplayName("管理员(role=1)可查看他人提交")
        void adminCanViewOthers() {
            UserContext.setRole(1);
            Submission s = existingSubmission(900L, "SUCCESS");
            s.setUserId(OTHER_USER);
            when(submissionMapper.selectById(900L)).thenReturn(s);
            when(judgeResultMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
            when(compileInfoMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
            assertThat(service.queryDetail(900L).getUserId()).isEqualTo(OTHER_USER);
        }

        @Test
        @DisplayName("学员视角：隐藏用例摘要置 null 且 hidden=true；可见用例正常下发")
        void hiddenCaseMaskedForStudent() {
            UserContext.setRole(2);
            Submission s = existingSubmission(900L, "SUCCESS");
            s.setUserId(USER);
            when(submissionMapper.selectById(900L)).thenReturn(s);

            JudgeCaseDTO hiddenCase = new JudgeCaseDTO();
            hiddenCase.setCaseId(11L);
            hiddenCase.setIsHidden(1);
            JudgeCaseDTO openCase = new JudgeCaseDTO();
            openCase.setCaseId(22L);
            openCase.setIsHidden(0);
            JudgeInfoDTO info = new JudgeInfoDTO();
            info.setTestCases(List.of(hiddenCase, openCase));
            when(problemClient.getJudgeInfo(PROBLEM_ID)).thenReturn(info);

            when(judgeResultMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                    result(900L, 11L, 1, "WA", "expected-output", "stderr"),
                    result(900L, 22L, 2, "AC", "actual-output", null)));
            when(compileInfoMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            SubmissionDetailVO vo = service.queryDetail(900L);
            CaseResultVO h = vo.getCaseResults().stream()
                    .filter(c -> c.getCaseId() == 11L).findFirst().orElseThrow();
            CaseResultVO o = vo.getCaseResults().stream()
                    .filter(c -> c.getCaseId() == 22L).findFirst().orElseThrow();
            assertThat(h.getHidden()).isTrue();
            assertThat(h.getOutputDigest()).isNull();
            assertThat(h.getStderrDigest()).isNull();
            assertThat(o.getHidden()).isFalse();
            assertThat(o.getOutputDigest()).isEqualTo("actual-output");
        }

        @Test
        @DisplayName("题目服务不可用 → fail-closed：全部用例摘要屏蔽（不泄露隐藏答案）")
        void problemServiceDownFailClosed() {
            UserContext.setRole(2);
            Submission s = existingSubmission(900L, "SUCCESS");
            s.setUserId(USER);
            when(submissionMapper.selectById(900L)).thenReturn(s);
            when(problemClient.getJudgeInfo(PROBLEM_ID)).thenThrow(new RuntimeException("down"));
            when(judgeResultMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                    result(900L, 11L, 1, "WA", "secret-digest", "err")));
            when(compileInfoMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            SubmissionDetailVO vo = service.queryDetail(900L);
            assertThat(vo.getCaseResults().get(0).getOutputDigest()).isNull();
            assertThat(vo.getCaseResults().get(0).getHidden()).isTrue();
        }

        @Test
        @DisplayName("角色缺失(role=null)按学员处理：本人可见、他人 403")
        void nullRoleTreatedAsStudent() {
            UserContext.setRole(null);
            Submission mine = existingSubmission(900L, "SUCCESS");
            mine.setUserId(USER);
            when(submissionMapper.selectById(900L)).thenReturn(mine);
            when(judgeResultMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
            when(compileInfoMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
            assertThat(service.queryDetail(900L)).isNotNull();

            Submission other = existingSubmission(901L, "SUCCESS");
            other.setUserId(OTHER_USER);
            when(submissionMapper.selectById(901L)).thenReturn(other);
            assertThatThrownBy(() -> service.queryDetail(901L))
                    .isInstanceOf(ForbiddenException.class);
        }
    }

    // ==================== reviewContext：点评上下文 ====================

    @Nested
    @DisplayName("AI 点评上下文")
    class ReviewContext {

        private JudgeResult ac(long caseId, int seq) {
            return result(900L, caseId, seq, "AC", "d" + caseId, null);
        }

        @Test
        @DisplayName("maskHidden=true：隐藏用例摘要被遮蔽")
        void maskHiddenTrue() {
            Submission s = existingSubmission(900L, "SUCCESS");
            when(submissionMapper.selectById(900L)).thenReturn(s);
            JudgeCaseDTO hidden = new JudgeCaseDTO();
            hidden.setCaseId(11L);
            hidden.setIsHidden(1);
            JudgeInfoDTO info = new JudgeInfoDTO();
            info.setTestCases(List.of(hidden));
            when(problemClient.getJudgeInfo(PROBLEM_ID)).thenReturn(info);
            when(judgeResultMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of(result(900L, 11L, 1, "WA", "leak", "err")));
            when(compileInfoMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            SubmissionReviewContextDTO dto = service.reviewContext(900L, true);
            assertThat(dto.getCaseSamples().get(0).getOutputDigest()).isNull();
            assertThat(dto.getCaseSamples().get(0).getHidden()).isTrue();
        }

        @Test
        @DisplayName("maskHidden=false（教师/员工内部链路）：摘要不遮蔽")
        void maskHiddenFalse() {
            Submission s = existingSubmission(900L, "SUCCESS");
            when(submissionMapper.selectById(900L)).thenReturn(s);
            when(judgeResultMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of(result(900L, 11L, 1, "WA", "ok", null)));
            when(compileInfoMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            SubmissionReviewContextDTO dto = service.reviewContext(900L, false);
            assertThat(dto.getCaseSamples().get(0).getOutputDigest()).isEqualTo("ok");
        }

        @Test
        @DisplayName("样本上限 20 条：caseTotal 保留全量，caseSamples 截前 20")
        void sampleLimit() {
            Submission s = existingSubmission(900L, "SUCCESS");
            when(submissionMapper.selectById(900L)).thenReturn(s);
            List<JudgeResult> many = new java.util.ArrayList<>();
            for (int i = 1; i <= 25; i++) {
                many.add(ac(i, i));
            }
            when(judgeResultMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(many);
            when(compileInfoMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            SubmissionReviewContextDTO dto = service.reviewContext(900L, false);
            assertThat(dto.getCaseTotal()).isEqualTo(25);
            assertThat(dto.getCaseAcCount()).isEqualTo(25);
            assertThat(dto.getCaseSamples()).hasSize(20);
        }

        @Test
        @DisplayName("题目服务不可用 + maskHidden=true → fail-closed 全遮蔽")
        void failClosedOnProblemDown() {
            Submission s = existingSubmission(900L, "SUCCESS");
            when(submissionMapper.selectById(900L)).thenReturn(s);
            when(problemClient.getJudgeInfo(PROBLEM_ID)).thenThrow(new RuntimeException("down"));
            when(judgeResultMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of(result(900L, 11L, 1, "WA", "leak", null)));
            when(compileInfoMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            SubmissionReviewContextDTO dto = service.reviewContext(900L, true);
            assertThat(dto.getCaseSamples().get(0).getOutputDigest()).isNull();
        }
    }

    // ==================== rejudge：重判权限 ====================

    @Nested
    @DisplayName("重判权限")
    class Rejudge {

        @Test
        @DisplayName("教师重判非本人题目 → 403")
        void teacherNotOwner() {
            UserContext.setRole(3);
            Submission s = existingSubmission(900L, "SUCCESS");
            when(submissionMapper.selectById(900L)).thenReturn(s);
            JudgeInfoDTO info = new JudgeInfoDTO();
            info.setOwnerId(OTHER_USER); // 题目归属他人
            when(problemClient.getJudgeInfo(PROBLEM_ID)).thenReturn(info);

            assertThatThrownBy(() -> service.rejudge(900L))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("仅可重判本人题目");
        }

        @Test
        @DisplayName("教师重判本人题目 → 放行并复位")
        void teacherOwner() {
            UserContext.setRole(3);
            Submission s = existingSubmission(900L, "SUCCESS");
            when(submissionMapper.selectById(900L)).thenReturn(s);
            JudgeInfoDTO info = new JudgeInfoDTO();
            info.setOwnerId(USER);
            when(problemClient.getJudgeInfo(PROBLEM_ID)).thenReturn(info);
            when(judgeTaskMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(task(700L));
            SubmissionService selfDelegate = mock(SubmissionService.class);
            when(self.getObject()).thenReturn(selfDelegate);

            service.rejudge(900L);
            verify(selfDelegate).doRejudgeReset(900L, 700L);
        }

        @Test
        @DisplayName("管理员重判任意题目 → 放行")
        void adminAnyProblem() {
            UserContext.setRole(1);
            Submission s = existingSubmission(900L, "SUCCESS");
            when(submissionMapper.selectById(900L)).thenReturn(s);
            JudgeInfoDTO info = new JudgeInfoDTO();
            info.setOwnerId(OTHER_USER);
            when(problemClient.getJudgeInfo(PROBLEM_ID)).thenReturn(info);
            when(judgeTaskMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(task(700L));
            SubmissionService selfDelegate = mock(SubmissionService.class);
            when(self.getObject()).thenReturn(selfDelegate);

            service.rejudge(900L);
            verify(selfDelegate).doRejudgeReset(900L, 700L);
        }

        @Test
        @DisplayName("题目已删除 → 拒绝重判")
        void problemMissing() {
            UserContext.setRole(1);
            Submission s = existingSubmission(900L, "SUCCESS");
            when(submissionMapper.selectById(900L)).thenReturn(s);
            when(problemClient.getJudgeInfo(PROBLEM_ID)).thenReturn(null);
            assertThatThrownBy(() -> service.rejudge(900L))
                    .isInstanceOf(BizIllegalException.class)
                    .hasMessageContaining("题目不存在");
        }

        @Test
        @DisplayName("判题任务不存在 → 拒绝重判")
        void taskMissing() {
            UserContext.setRole(1);
            Submission s = existingSubmission(900L, "SUCCESS");
            when(submissionMapper.selectById(900L)).thenReturn(s);
            JudgeInfoDTO info = new JudgeInfoDTO();
            info.setOwnerId(USER);
            when(problemClient.getJudgeInfo(PROBLEM_ID)).thenReturn(info);
            when(judgeTaskMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
            assertThatThrownBy(() -> service.rejudge(900L))
                    .isInstanceOf(BizIllegalException.class)
                    .hasMessageContaining("判题任务不存在");
        }
    }

    @Nested
    @DisplayName("重判复位（doRejudgeReset）")
    class DoRejudgeReset {

        @Test
        @DisplayName("复位：任务 PENDING/attempt 归零、清历史结果、提交状态回 PENDING、重新入队、投递 RETRY")
        void resetLifecycle() {
            service.doRejudgeReset(900L, 700L);

            verify(judgeTaskMapper).update(any(), any(LambdaUpdateWrapper.class));
            verify(judgeResultMapper).delete(any(LambdaQueryWrapper.class));
            verify(compileInfoMapper).delete(any(LambdaQueryWrapper.class));
            verify(submissionMapper).update(any(), any(LambdaUpdateWrapper.class));
            verify(zSetOps).add(eq("judge:judge:queue:zset"), eq("900"), anyDouble());
            verify(eventPublisher).publishRetry(900L, 700L, 0, "manual-rejudge");
        }
    }

    // ==================== 纯函数 ====================

    @Test
    @DisplayName("sha256Hex 已知向量：sha256('abc')")
    void sha256KnownVector() {
        assertThat(SubmissionService.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    @DisplayName("page：学员视角构造查询不抛异常且走分页")
    void pageStudentBasic() {
        when(submissionMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenReturn(new Page<Submission>());
        PageDTO<SubmissionVO> page = service.page(new com.codejudge.submission.domain.dto.SubmissionQuery());
        assertThat(page).isNotNull();
    }

    // ---------- 工具 ----------

    private Submission savedSubmission(long id) {
        Submission s = new Submission();
        s.setId(id);
        s.setUserId(USER);
        s.setProblemId(PROBLEM_ID);
        s.setContestId(0L);
        s.setLanguage("PYTHON");
        s.setCode("print(1)");
        s.setCodeHash(SubmissionService.sha256Hex("print(1)"));
        s.setSubmitRound(0);
        s.setStatus(SubmissionService.ST_PENDING);
        s.setScore(0);
        s.setSubmitTime(LocalDateTime.now());
        return s;
    }

    private JudgeResult result(long submissionId, long caseId, int seq, String verdict,
                               String outputDigest, String stderrDigest) {
        JudgeResult r = new JudgeResult();
        r.setSubmissionId(submissionId);
        r.setCaseId(caseId);
        r.setSeq(seq);
        r.setVerdict(verdict);
        r.setOutputDigest(outputDigest);
        r.setStderrDigest(stderrDigest);
        r.setTimeMs(10);
        r.setMemoryKb(1024);
        return r;
    }

    private JudgeTask task(long id) {
        JudgeTask t = new JudgeTask();
        t.setId(id);
        t.setSubmissionId(900L);
        t.setStatus(SubmissionService.ST_PENDING);
        t.setAttempt(0);
        t.setMaxAttempt(3);
        return t;
    }
}
