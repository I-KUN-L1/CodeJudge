package com.codejudge.common.mq;

/**
 * RocketMQ 主题 / Tag 全局命名规范（judge_ 前缀 + 下划线分隔，Tag 全大写）。
 * <p>
 * 所有业务模块统一引用此类，禁止散落硬编码；新增主题须在此登记。
 * <p>
 * 命名对齐说明：底座 zx-learn 的规范是「下划线主题 + 大写 Tag」，
 * 而 CodeJudge 的设计稿写的是点号主题（{@code judge.submission.created}）。
 * 点号命名与底座既有封装（{@link RocketMQTemplate}）及 RocketMQ Dashboard 的检索习惯
 * 都不一致，故统一改为 {@code judge_submission} + Tag {@code CREATED/RETRY/RESULT}，
 * 语义等价且与底座可复用的消费容器无缝衔接。
 */
public interface MqTopics {

    /**
     * 判题任务主题（judge-submission 发布 → judge-worker 消费，worker 内部重试自产自消）。
     * <p>
     * Tag 语义：
     * <ul>
     *   <li>{@code CREATED}：提交落库成功后投递，worker 首次拉取判题；</li>
     *   <li>{@code RETRY}：判题超时 / Worker 宕机 / 沙箱异常后重新投递，消费方按 attempt 判重；</li>
     *   <li>{@code PROGRESS}：判题中途状态（认领 / 逐用例完成），供 WebSocket 秒级推送进度
     *       —— 生产端已做限流合并，见 worker 的 ProgressPublisher；</li>
     *   <li>{@code RESULT}：判题终态回写事件，供竞赛榜与 WebSocket 推送消费。</li>
     * </ul>
     */
    String TOPIC_JUDGE_SUBMISSION = "judge_submission";

    /**
     * 判题死信主题：超过最大重试次数或消费持续失败的任务落入此处，供人工排查与重放。
     * <p>
     * 注意与 RocketMQ 原生 {@code %DLQ%{consumerGroup}} 的关系 —— 两者并存（双轨）：
     * 原生 DLQ 由 broker 兜底保证消息不丢，本主题承载「可读、可重放、可告警」的业务补偿记录。
     */
    String TOPIC_JUDGE_DLQ = "judge_submission_dlq";

    /** AI 代码点评请求（judge-submission / 用户触发 → judge-ai 消费） */
    String TOPIC_AI_REVIEW = "ai_review";

    /**
     * 竞赛排名变更事件 —— **P4 未启用，保留位**。
     *
     * <p>P4 实现选择：judge-contest 直接以独立消费组消费 {@code judge_submission} 的
     * {@code RESULT} 事件（同一份事件、不同消费组，互不影响）。多一跳
     * 「judge-submission 转发到 contest_rank」不会带来任何语义增益，
     * 反而多一个丢失点与一份重复报文。
     *
     * <p>保留本常量用于**跨系统**的排名变更外发（例如 P6 的数据看板、第三方订阅），
     * 届时由 judge-contest 在榜单落地后发布，Tag 用 {@code CHANGED}。
     */
    String TOPIC_CONTEST_RANK = "contest_rank";

    interface Tags {
        /** 提交创建、投递判题任务 */
        String SUBMISSION_CREATED = "CREATED";
        /** 判题重试 */
        String SUBMISSION_RETRY = "RETRY";
        /** 判题中途进度（P4 新增） */
        String SUBMISSION_PROGRESS = "PROGRESS";
        /** 判题结果回写 */
        String SUBMISSION_RESULT = "RESULT";
        /** 死信落库 */
        String SUBMISSION_DEAD = "DEAD";
        /** AI 点评请求 */
        String AI_REVIEW_REQUESTED = "REQUESTED";
        /** 排名变更 */
        String RANK_CHANGED = "CHANGED";
    }
}
