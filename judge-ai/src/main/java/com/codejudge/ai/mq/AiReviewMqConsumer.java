package com.codejudge.ai.mq;

import com.codejudge.ai.domain.ReviewType;
import com.codejudge.ai.service.ReviewService;
import com.codejudge.common.constants.UserRole;
import com.codejudge.common.mq.MqHandler;
import com.codejudge.common.mq.MqTopics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * AI 点评异步预生成消费者（{@code ai_review} 主题的 {@code REQUESTED} Tag）。
 *
 * <h3>它解决什么问题</h3>
 * 同步点评必须等 LLM 生成完（数秒到数十秒）。若平台希望学员在「提交 → 判题完成」
 * 之后一进入详情页就能看到点评，就需要在判题结束时**提前**生成好。
 * 这条异步链路正是为此存在：判题完成事件触发 → 投递 {@code ai_review#REQUESTED}
 * → 本消费者在后台把点评生成并落库 → 学员打开页面时直接读历史记录。
 *
 * <h3>为什么默认关闭（{@code cj.review.mq-enabled=false}）</h3>
 * 本消费者是**增强项**，不是点评功能的必要路径（点评主路径是 SSE 实时生成）。
 * 默认开启会让 judge-ai 在启动时强依赖 RocketMQ broker 的可达性 ——
 * 一个可选的增强项不该成为服务能否启动的前提。需要时显式开启。
 *
 * <h3>幂等</h3>
 * 消费失败会 RECONSUME_LATER 重投，因此同一提交可能被生成多次。
 * 这里不做「先查有没有」的乐观去重，而是**允许重复生成**：
 * 每次点评都是独立的一行 {@code ai_review} 记录，多生成一次只是多一条历史，
 * 不会破坏任何不变式（而「查了再写」在并发下本就不成立，需要唯一索引兜底，
 * 为一条增强链路引入唯一约束得不偿失）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "cj.review", name = "mq-enabled", havingValue = "true")
public class AiReviewMqConsumer implements MqHandler {

    private final ReviewService reviewService;
    private final ObjectMapper objectMapper;

    @Override
    public Set<String> subscribeTopics() {
        return Set.of(MqTopics.TOPIC_AI_REVIEW);
    }

    @Override
    public Set<String> subscribeTags() {
        return Set.of(MqTopics.Tags.AI_REVIEW_REQUESTED);
    }

    @Override
    public boolean supports(String topic, String tag) {
        return MqTopics.TOPIC_AI_REVIEW.equals(topic)
                && MqTopics.Tags.AI_REVIEW_REQUESTED.equals(tag);
    }

    /**
     * 处理预生成请求。
     *
     * <p>报文形如：{@code {"submissionId":123,"reviewType":1,"question":"..."}}。
     * 缺失字段的语义：{@code reviewType} 缺省按「错误诊断」，{@code question} 缺省为空。
     *
     * <p>以**员工角色**调用生成：预生成是系统行为，需要跨越「学员只能点评自己提交」的归属校验
     * （被点评的提交属于学员本人，而消费线程没有登录态）。只传 submissionId、
     * 由上游在投递前完成授权判断 —— 本服务不再重复鉴权，但也不接受外部直投：
     * MQ 的 topic 不对外暴露。
     */
    @Override
    public void handle(MessageExt message) throws Exception {
        String raw = new String(message.getBody(), StandardCharsets.UTF_8);
        JsonNode node = objectMapper.readTree(raw);
        Long submissionId = node.hasNonNull("submissionId") ? node.get("submissionId").asLong() : null;
        if (submissionId == null) {
            log.warn("ai_review#REQUESTED 报文缺少 submissionId，丢弃：{}", raw);
            return;
        }
        Integer reviewTypeCode = node.hasNonNull("reviewType") ? node.get("reviewType").asInt() : null;
        String question = node.hasNonNull("question") ? node.get("question").asText() : null;

        long start = System.currentTimeMillis();
        try {
            var vo = reviewService.doReviewOnce(submissionId, 0L, UserRole.STAFF.getCode(),
                    ReviewType.of(reviewTypeCode), question);
            log.info("AI 点评预生成完成 submissionId={}, reviewId={}, 耗时 {}ms",
                    submissionId, vo == null ? null : vo.getId(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            // 抛出 → RECONSUME_LATER 重投。点评是「可重试的幂等操作」（每次生成一行新记录），
            // 因此这里选择重试而不是降级吞掉。重试次数上限由 RocketMQ 的
            // maxReconsumeTimes 与业务 DLQ（judge_submission_dlq 之外的 ai_review DLQ）兜底。
            log.error("AI 点评预生成失败，将重试 submissionId={}：{}", submissionId, e.toString());
            throw e;
        }
    }
}
