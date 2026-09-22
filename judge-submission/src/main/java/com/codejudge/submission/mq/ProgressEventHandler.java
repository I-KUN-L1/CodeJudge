package com.codejudge.submission.mq;

import com.codejudge.api.dto.submission.SubmissionProgressMessage;
import com.codejudge.common.mq.MqHandler;
import com.codejudge.common.mq.MqTopics;
import com.codejudge.submission.service.SubmissionProgressPushService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 判题进度事件消费（topic=judge_submission，tag=PROGRESS）→ WebSocket 推送。
 *
 * <p>与 {@link ResultEventHandler} 并列：同样是 judge_submission 主题，
 * 但按 Tag 分流 —— 进度是「高频、可丢」，终态是「低频、必须送达」，
 * 两者的处理策略（是否重试、是否去重）不同，放在一起会互相牵制。
 *
 * <p>异常策略：进度推送失败**不重投**（推送是旁路能力，客户端可从 REST 详情补齐），
 * 因此这里吞掉异常并记录日志，避免因某个订阅者异常导致 MQ 反复重投该消息。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProgressEventHandler implements MqHandler {

    private final ObjectMapper objectMapper;
    private final SubmissionProgressPushService pushService;

    @Override
    public Set<String> subscribeTopics() {
        return Set.of(MqTopics.TOPIC_JUDGE_SUBMISSION);
    }

    @Override
    public Set<String> subscribeTags() {
        return Set.of(MqTopics.Tags.SUBMISSION_PROGRESS);
    }

    @Override
    public boolean supports(String topic, String tag) {
        return MqTopics.TOPIC_JUDGE_SUBMISSION.equals(topic)
                && MqTopics.Tags.SUBMISSION_PROGRESS.equals(tag);
    }

    @Override
    public void handle(MessageExt message) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            SubmissionProgressMessage progress = objectMapper.readValue(body, SubmissionProgressMessage.class);
            pushService.pushProgress(progress);
        } catch (Exception e) {
            log.warn("判题进度推送失败（不重投）：msgId={} err={}", message.getMsgId(), e.getMessage());
        }
    }
}
