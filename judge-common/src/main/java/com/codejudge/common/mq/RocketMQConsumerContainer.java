package com.codejudge.common.mq;

import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * RocketMQ 通用消费者容器（沉淀到 judge-common）。
 * <p>
 * 自动收集 Spring 容器中所有 {@link MqHandler} Bean，按其声明的主题建立订阅关系，
 * 并按 topic+tag 分发消息；任一消息处理异常整体 RECONSUME_LATER 重投。
 * <p>
 * MQ 不可用时启动失败但不抛出（由各业务的本地消息表 / 定时兜底扫描保证最终一致）。
 */
@Slf4j
public class RocketMQConsumerContainer {

    private final RocketMQProperties properties;
    private final List<MqHandler> handlers;

    private DefaultMQPushConsumer consumer;

    public RocketMQConsumerContainer(RocketMQProperties properties, List<MqHandler> handlers) {
        this.properties = properties;
        this.handlers = handlers;
    }

    /** 启动消费者；MQ 不可用时不抛出，记录降级日志。 */
    public void start() {
        if (handlers == null || handlers.isEmpty()) {
            log.info("未发现 MqHandler，跳过 RocketMQ 消费者启动");
            return;
        }
        try {
            DefaultMQPushConsumer c = new DefaultMQPushConsumer(properties.getConsumerGroup());
            c.setNamesrvAddr(properties.getNameServer());
            c.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET);
            Map<String, Set<String>> tagsByTopic = tagsByTopic();
            tagsByTopic.forEach((topic, tags) -> {
                // 任一处理器未声明 Tag（记入 "*"）即退化为订阅全部 Tag —— 宁可多收，
                // 也不能因为某个处理器漏声明而静默丢消息。
                String subExpression = tags.contains("*") ? "*" : String.join("||", tags);
                try {
                    c.subscribe(topic, subExpression);
                } catch (Exception e) {
                    throw new IllegalStateException("订阅主题失败：" + topic, e);
                }
            });
            c.registerMessageListener((MessageListenerConcurrently) (msgs, context) -> {
                for (MessageExt msg : msgs) {
                    try {
                        MqHandler handler = handlers.stream()
                                .filter(h -> h.supports(msg.getTopic(), msg.getTags()))
                                .findFirst().orElse(null);
                        if (handler == null) {
                            log.warn("未找到处理器，丢弃消息：topic={}, tag={}", msg.getTopic(), msg.getTags());
                            continue;
                        }
                        handler.handle(msg);
                    } catch (Exception e) {
                        log.error("消费消息失败，稍后重试：topic={}, tag={}, key={}, err={}",
                                msg.getTopic(), msg.getTags(), msg.getKeys(), e.getMessage());
                        return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                    }
                }
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            });
            c.start();
            this.consumer = c;
            log.info("RocketMQ 消费者启动成功，group={}, 订阅关系={}",
                    properties.getConsumerGroup(),
                    tagsByTopic.entrySet().stream()
                            .map(e -> e.getKey() + "[" + String.join(",", e.getValue()) + "]")
                            .toList());
        } catch (Exception e) {
            log.error("RocketMQ 消费者启动失败（MQ 不可用），将由业务定时兜底任务补偿：{}", e.getMessage());
        }
    }

    public void shutdown() {
        if (consumer != null) {
            consumer.shutdown();
        }
    }

    /**
     * 汇总「主题 → 订阅 Tag 集合」。
     *
     * <p>任一处理器对该主题未声明 Tag（{@link MqHandler#subscribeTags()} 为空）即写入
     * {@code "*"}，最终该主题退化为订阅全部 Tag —— 收窄订阅是优化，不是正确性前提，
     * 不能让「漏声明」变成「收不到消息」。
     */
    private Map<String, Set<String>> tagsByTopic() {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (MqHandler handler : handlers) {
            Set<String> declared = handler.subscribeTags();
            boolean all = declared == null || declared.isEmpty();
            for (String topic : handler.subscribeTopics()) {
                Set<String> acc = out.computeIfAbsent(topic, k -> new TreeSet<>());
                if (all) {
                    acc.add("*");
                } else if (!acc.contains("*")) {
                    acc.addAll(declared);
                }
            }
        }
        return out;
    }
}
