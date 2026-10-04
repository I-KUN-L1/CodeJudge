package com.codejudge.contest.mq;

import com.codejudge.api.dto.submission.SubmissionResultMessage;
import com.codejudge.common.mq.MqTopics;
import com.codejudge.contest.service.ContestRankPusher;
import com.codejudge.contest.service.ContestRankService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ContestResultHandler 榜单更新消费单测。
 *
 * <p>运行：mvn -pl judge-contest -am test
 *
 * <p>覆盖：订阅契约（topic=judge_submission 只收 RESULT tag）、坏报文丢弃不抛出、
 * 榜单变更才登记脏标记、消费异常向外吞掉（榜单是派生数据，不触发 MQ 无限重投）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContestResultHandlerTest {

    private static final Long CONTEST_ID = 6001L;

    private final ObjectMapper mapper = new ObjectMapper();

    @Mock
    private ContestRankService rankService;
    @Mock
    private ContestRankPusher pusher;

    private ContestResultHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ContestResultHandler(mapper, rankService, pusher);
    }

    private MessageExt message(String body) {
        MessageExt msg = new MessageExt();
        msg.setBody(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        msg.setMsgId("msg-1");
        return msg;
    }

    private String bodyOf(SubmissionResultMessage result) throws Exception {
        return mapper.writeValueAsString(result);
    }

    @Nested
    @DisplayName("订阅契约")
    class Subscription {

        @Test
        @DisplayName("只订阅 judge_submission 的 RESULT tag")
        void subscribesResultOnly() {
            assertThat(handler.subscribeTopics()).containsExactly(MqTopics.TOPIC_JUDGE_SUBMISSION);
            assertThat(handler.subscribeTags()).containsExactly(MqTopics.Tags.SUBMISSION_RESULT);
        }

        @Test
        @DisplayName("supports：topic 与 tag 同时匹配才为 true（其余组合均拒绝）")
        void supportsMatrix() {
            assertThat(handler.supports(MqTopics.TOPIC_JUDGE_SUBMISSION, MqTopics.Tags.SUBMISSION_RESULT))
                    .isTrue();
            assertThat(handler.supports(MqTopics.TOPIC_JUDGE_SUBMISSION, MqTopics.Tags.SUBMISSION_CREATED))
                    .isFalse();
            assertThat(handler.supports(MqTopics.TOPIC_AI_REVIEW, MqTopics.Tags.SUBMISSION_RESULT))
                    .isFalse();
            assertThat(handler.supports(null, null)).isFalse();
        }
    }

    @Nested
    @DisplayName("handle：结果 → 榜单")
    class Handle {

        @Test
        @DisplayName("坏报文 → 丢弃并告警，不触碰榜单服务")
        void invalidJsonDropped() {
            assertThatCode(() -> handler.handle(message("{not json"))).doesNotThrowAnyException();

            verifyNoInteractions(rankService, pusher);
        }

        @Test
        @DisplayName("榜单变更 → 登记 dirty 触发合并推送")
        void changedMarksDirty() throws Exception {
            SubmissionResultMessage result = new SubmissionResultMessage();
            result.setSubmissionId(9001L);
            result.setContestId(CONTEST_ID);
            result.setUserId(1001L);
            result.setProblemId(2001L);
            result.setVerdict("AC");
            when(rankService.applyResult(any(SubmissionResultMessage.class))).thenReturn(true);

            handler.handle(message(bodyOf(result)));

            verify(pusher).markDirty(CONTEST_ID);
        }

        @Test
        @DisplayName("榜单未变（幂等重投）→ 不登记 dirty")
        void unchangedSkipsDirty() throws Exception {
            SubmissionResultMessage result = new SubmissionResultMessage();
            result.setContestId(CONTEST_ID);
            result.setUserId(1001L);
            result.setProblemId(2001L);
            result.setVerdict("WA");
            when(rankService.applyResult(any(SubmissionResultMessage.class))).thenReturn(false);

            handler.handle(message(bodyOf(result)));

            verify(pusher, never()).markDirty(any());
        }

        @Test
        @DisplayName("榜单更新抛异常 → 吞掉不重投（派生数据失败不影响判题终态）")
        void rankFailureSwallowed() throws Exception {
            SubmissionResultMessage result = new SubmissionResultMessage();
            result.setSubmissionId(9001L);
            result.setContestId(CONTEST_ID);
            result.setUserId(1001L);
            result.setProblemId(2001L);
            result.setVerdict("AC");
            when(rankService.applyResult(any(SubmissionResultMessage.class)))
                    .thenThrow(new RuntimeException("redis down"));

            assertThatCode(() -> handler.handle(message(bodyOf(result)))).doesNotThrowAnyException();

            verify(pusher, never()).markDirty(any());
        }
    }
}
