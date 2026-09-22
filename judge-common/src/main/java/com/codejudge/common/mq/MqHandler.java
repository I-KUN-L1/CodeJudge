package com.codejudge.common.mq;

import org.apache.rocketmq.common.message.MessageExt;

import java.util.Set;

/**
 * RocketMQ 消息处理器 SPI（沉淀到 judge-common，供判题链路各消费端复用）。
 * <p>
 * 实现方注册为 Spring Bean 后，由 {@link RocketMQConsumerContainer} 自动发现并注册订阅关系；
 * 实现方需在内部做好消费幂等（消费流水表 + 业务唯一键）。
 */
public interface MqHandler {

    /** 声明需要订阅的主题集合（容器据此建立订阅关系） */
    Set<String> subscribeTopics();

    /**
     * 声明需要订阅的 Tag 集合（可选）。返回空集合 = 订阅该主题的**全部** Tag。
     *
     * <p><b>用于收窄订阅关系</b>：一个主题常被多个消费组共享，各组的兴趣并不相同。
     * 例如 {@code judge_submission} 上有 CREATED / RETRY / PROGRESS / RESULT 四种 Tag，
     * 而 judge-contest 只关心 RESULT。若一律以 {@code *} 订阅，无关消息也会被投递过来、
     * 在容器里判空后丢弃（每次一条 WARN），既浪费带宽也淹没日志。
     *
     * <p>声明语义是**按处理器**而非按主题：一个处理器声明的一组 Tag 会应用到它
     * {@link #subscribeTopics()} 里的每个主题上。同一主题下多个处理器的声明取并集。
     *
     * <p>默认返回空集合 —— 保证既有实现（未声明 Tag）继续以 {@code *} 订阅，行为不变。
     */
    default Set<String> subscribeTags() {
        return Set.of();
    }

    /** 是否处理该 topic+tag 的消息 */
    boolean supports(String topic, String tag);

    /** 处理消息；抛出异常将触发 RECONSUME_LATER 重投 */
    void handle(MessageExt message) throws Exception;
}
