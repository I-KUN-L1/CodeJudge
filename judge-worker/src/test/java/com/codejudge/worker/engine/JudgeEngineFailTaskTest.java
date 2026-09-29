package com.codejudge.worker.engine;

import com.codejudge.api.dto.submission.SubmissionTaskMessage;
import com.codejudge.common.constants.JudgeRedisKeys;
import com.codejudge.common.mq.MqTopics;
import com.codejudge.common.mq.RocketMQTemplate;
import com.codejudge.worker.config.LanguageProfiles;
import com.codejudge.worker.config.WorkerProperties;
import com.codejudge.worker.domain.po.JudgeTask;
import com.codejudge.worker.domain.po.Submission;
import com.codejudge.worker.mapper.CompileInfoMapper;
import com.codejudge.worker.mapper.JudgeResultMapper;
import com.codejudge.worker.mapper.WorkerJudgeTaskMapper;
import com.codejudge.worker.mapper.WorkerSubmissionMapper;
import com.codejudge.worker.mq.ProgressPublisher;
import com.codejudge.worker.sandbox.SandboxExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JudgeEngine.failTask 平台故障处理单元测试（judge-worker 首个测试类）。
 *
 * <p>运行：mvn -pl judge-worker -am test
 *
 * <p>覆盖三条分支：未达最大尝试 → 交还重试（退避 5s）；达到最大尝试 → 转死信
 * （提交终态化 + 待判队列摘除，防止积压告警失真）；CAS 失败 → 任务已被接管，静默放弃。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JudgeEngineFailTaskTest {

    private static final Long TASK_ID = 9L;
    private static final Long SUBMISSION_ID = 77L;
    private static final String WORKER_ID = "worker-1";
    private static final String REASON = "sandbox crash";

    @Mock
    private SandboxExecutor sandbox;
    @Mock
    private LanguageProfiles languageProfiles;
    @Mock
    private WorkerProperties properties;
    @Mock
    private WorkerJudgeTaskMapper taskMapper;
    @Mock
    private WorkerSubmissionMapper submissionMapper;
    @Mock
    private JudgeResultMapper judgeResultMapper;
    @Mock
    private CompileInfoMapper compileInfoMapper;
    @Mock
    private RocketMQTemplate mqTemplate;
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ProgressPublisher progressPublisher;
    @Mock
    private TransactionTemplate txTemplate;

    @InjectMocks
    private JudgeEngine engine;

    @BeforeEach
    void setUp() {
        ZSetOperations<String, String> zSetOps = mock(ZSetOperations.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForZSet()).thenReturn(zSetOps);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(mqTemplate.send(anyString(), anyString(), any(Object.class), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(true);
    }

    private JudgeTask task(int attempt, int maxAttempt) {
        JudgeTask t = new JudgeTask();
        t.setId(TASK_ID);
        t.setAttempt(attempt);
        t.setMaxAttempt(maxAttempt);
        return t;
    }

    private Submission submission() {
        Submission s = new Submission();
        s.setId(SUBMISSION_ID);
        s.setUserId(1001L);
        s.setProblemId(4001L);
        return s;
    }

    @Nested
    @DisplayName("未达最大尝试：交还任务等待重试")
    class RetryBranch {

        @Test
        @DisplayName("attempt+1 < maxAttempt：CAS 交还成功 → 提交回 PENDING，重试消息延迟级别 2（5s 退避）")
        void handsBackForRetry() {
            when(taskMapper.handBackForRetry(TASK_ID, WORKER_ID, REASON)).thenReturn(1);

            boolean handedBack = engine.failTask(task(0, 3), submission(), WORKER_ID, REASON);

            assertThat(handedBack).isTrue();
            verify(submissionMapper).backToPending(SUBMISSION_ID);
            verify(progressPublisher).clear(SUBMISSION_ID);
            ArgumentCaptor<SubmissionTaskMessage> captor =
                    ArgumentCaptor.forClass(SubmissionTaskMessage.class);
            verify(mqTemplate).send(eq(MqTopics.TOPIC_JUDGE_SUBMISSION),
                    eq(MqTopics.Tags.SUBMISSION_RETRY), captor.capture(), eq(2));
            assertThat(captor.getValue().getSubmissionId()).isEqualTo(SUBMISSION_ID);
            assertThat(captor.getValue().getAttempt()).isEqualTo(1);
        }

        @Test
        @DisplayName("CAS 失败（任务已被其他 worker 接管）→ 返回 false，不触碰提交状态")
        void casFailureGivesUpQuietly() {
            when(taskMapper.handBackForRetry(TASK_ID, WORKER_ID, REASON)).thenReturn(0);

            boolean handedBack = engine.failTask(task(0, 3), submission(), WORKER_ID, REASON);

            assertThat(handedBack).isFalse();
            verify(submissionMapper, never()).backToPending(SUBMISSION_ID);
            verify(mqTemplate, never()).send(anyString(), anyString(), any(Object.class), org.mockito.ArgumentMatchers.anyInt());
        }
    }

    @Nested
    @DisplayName("达到最大尝试：转死信（终态）")
    class DeadLetterBranch {

        @Test
        @DisplayName("attempt+1 ≥ maxAttempt：任务标死、提交终态化、待判队列摘除、DLQ 投递")
        void deadLettersAndRemovesFromQueue() {
            when(taskMapper.markDead(TASK_ID, WORKER_ID, REASON)).thenReturn(1);

            boolean handedBack = engine.failTask(task(2, 3), submission(), WORKER_ID, REASON);

            assertThat(handedBack).isFalse();
            verify(taskMapper).markDead(TASK_ID, WORKER_ID, REASON);
            verify(submissionMapper).finishFailed(SUBMISSION_ID);
            // 死信走 DLQ topic，不会触发消费端 ZREM，必须在这里主动摘除
            verify(redis.opsForZSet()).remove(eq(JudgeRedisKeys.JUDGE_QUEUE_ZSET), eq(String.valueOf(SUBMISSION_ID)));
            verify(mqTemplate).send(eq(MqTopics.TOPIC_JUDGE_DLQ),
                    eq(MqTopics.Tags.SUBMISSION_DEAD), any(Object.class));
            verify(submissionMapper, never()).backToPending(SUBMISSION_ID);
        }

        @Test
        @DisplayName("markDead CAS 失败 → 不终态化提交，不投递 DLQ")
        void markDeadCasFailureKeepsSubmissionUntouched() {
            when(taskMapper.markDead(TASK_ID, WORKER_ID, REASON)).thenReturn(0);

            boolean handedBack = engine.failTask(task(2, 3), submission(), WORKER_ID, REASON);

            assertThat(handedBack).isFalse();
            verify(submissionMapper, never()).finishFailed(SUBMISSION_ID);
            verify(mqTemplate, never()).send(anyString(), anyString(), any(Object.class), org.mockito.ArgumentMatchers.anyInt());
        }
    }
}
