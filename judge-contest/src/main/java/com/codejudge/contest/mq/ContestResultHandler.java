package com.codejudge.contest.mq;

import com.codejudge.api.dto.submission.SubmissionResultMessage;
import com.codejudge.common.mq.MqHandler;
import com.codejudge.common.mq.MqTopics;
import com.codejudge.contest.service.ContestRankPusher;
import com.codejudge.contest.service.ContestRankService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 判题结果消费 → 榜单更新（topic=judge_submission，tag=RESULT）。
 *
 * <p><b>为什么直接消费 RESULT 而不用专门的 contest_rank 主题</b>：
 * judge-submission 已经在消费同一事件（用于队列指标）。judge-contest 用**自己独立的
 * consumer-group** 订阅同一个 topic，RocketMQ 会把消息各投一份给两个消费组
 * —— 这就是标准的发布订阅语义。多插一跳「submission 转投 contest_rank」只会
 * 增加一个丢失点、多一份报文，换不来任何保证。
 *
 * <p><b>幂等</b>：RocketMQ 至少一次投递，同一结果可能被重复消费。榜单更新的幂等
 * 由 Lua 脚本保证（ACM 已通过的题重复 AC 不改变状态、IOI 只在提高最高分时变更），
 * 因此重复消费不会把罚时算重 —— 这是把「重算」而非「累加」写进脚本的直接收益。
 *
 * <p><b>异常策略</b>：消费者不向外抛异常。榜单是派生数据，更新失败不应触发无限重投；
 * 真正需要重算时用 {@code POST /contests/{id}/rank/rebuild} 从提交表回放。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContestResultHandler implements MqHandler {

    private final ObjectMapper objectMapper;
    private final ContestRankService rankService;
    private final ContestRankPusher pusher;

    @Override
    public Set<String> subscribeTopics() {
        return Set.of(MqTopics.TOPIC_JUDGE_SUBMISSION);
    }

    @Override
    public Set<String> subscribeTags() {
        // 只订阅 RESULT：本服务是「只消费不生产」的榜单更新方，
        // CREATED / RETRY / PROGRESS 与它无关，收下来也只会被丢弃
        return Set.of(MqTopics.Tags.SUBMISSION_RESULT);
    }

    @Override
    public boolean supports(String topic, String tag) {
        return MqTopics.TOPIC_JUDGE_SUBMISSION.equals(topic)
                && MqTopics.Tags.SUBMISSION_RESULT.equals(tag);
    }

    @Override
    public void handle(MessageExt message) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        SubmissionResultMessage result;
        try {
            result = objectMapper.readValue(body, SubmissionResultMessage.class);
        } catch (Exception e) {
            log.error("判题结果反序列化失败，丢弃：msgId={} body={}", message.getMsgId(), body, e);
            return;
        }
        try {
            boolean changed = rankService.applyResult(result);
            if (changed) {
                // 只登记脏标记，真正的推送在合并窗口到期后由 Pusher 统一发出
                pusher.markDirty(result.getContestId());
            }
        } catch (Exception e) {
            log.error("榜单更新失败（不影响判题终态）：submissionId={} contestId={}",
                    result.getSubmissionId(), result.getContestId(), e);
        }
    }
}
