package com.codejudge.submission.mq;

import com.codejudge.api.dto.submission.SubmissionResultMessage;
import com.codejudge.common.constants.JudgeRedisKeys;
import com.codejudge.common.mq.MqHandler;
import com.codejudge.common.mq.MqTopics;
import com.codejudge.submission.service.SubmissionProgressPushService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 判题结果事件消费（topic=judge_submission，tag=RESULT）。
 *
 * <p>职责：
 * <ol>
 *   <li>从待判队列 ZSet 摘除已完成提交（队列积压指标归位）+ 结构化日志；</li>
 *   <li>向 {@code /ws/submissions/{id}} 的订阅者推送终态（P4 新增）。</li>
 * </ol>
 *
 * <p>judge-contest 用**另一个消费组**消费同一事件更新榜单 —— 同一份事件、多订阅者，
 * 互不影响（这正是把榜单更新做成独立消费组而不是让本服务转投的原因）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResultEventHandler implements MqHandler {

    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redis;
    private final SubmissionProgressPushService pushService;

    @Override
    public Set<String> subscribeTopics() {
        return Set.of(MqTopics.TOPIC_JUDGE_SUBMISSION);
    }

    @Override
    public Set<String> subscribeTags() {
        return Set.of(MqTopics.Tags.SUBMISSION_RESULT);
    }

    @Override
    public boolean supports(String topic, String tag) {
        return MqTopics.TOPIC_JUDGE_SUBMISSION.equals(topic)
                && MqTopics.Tags.SUBMISSION_RESULT.equals(tag);
    }

    @Override
    public void handle(MessageExt message) throws Exception {
        SubmissionResultMessage result;
        try {
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            result = objectMapper.readValue(body, SubmissionResultMessage.class);
        } catch (Exception e) {
            // 反序列化失败属毒消息：重投 16 次也解不出来，只会占用重试队列；
            // 与 SubmissionTaskHandler / ContestResultHandler 的处理对齐 —— 记录后丢弃
            log.error("判题结果报文解析失败，丢弃（不重投）：msgId={} err={}", message.getMsgId(), e.toString());
            return;
        }
        // 幂等：ZREM 本身幂等，重复消费无副作用
        redis.opsForZSet().remove(JudgeRedisKeys.JUDGE_QUEUE_ZSET, String.valueOf(result.getSubmissionId()));
        log.info("判题结果事件：submissionId={} verdict={} score={} time={}ms mem={}KB passed={}/{}",
                result.getSubmissionId(), result.getVerdict(), result.getScore(),
                result.getTimeMs(), result.getMemoryKb(), result.getPassedCount(), result.getTotalCount());
        // 推送失败不影响结果已落库这一事实（异常在此折叠，避免 MQ 无限重投）
        try {
            pushService.pushResult(result);
        } catch (Exception e) {
            log.warn("判题结果推送失败（不重投）：submissionId={} err={}", result.getSubmissionId(), e.getMessage());
        }
    }
}
