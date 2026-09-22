package com.codejudge.submission.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 判题调度参数（judge-submission 侧）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "cj.judge")
public class JudgeProperties {

    /** 任务级超时（含编译 + 全部用例），同时作为 worker 租约时长 */
    private long taskTimeoutMs = 120_000;

    /** 最大重试次数，超过即转死信（DEAD）并投递业务 DLQ */
    private int maxAttempt = 3;

    /** 单用户提交频控（次/分钟），Redis INCR + 60s 过期实现 */
    private int submitRateLimitPerMin = 30;

    /** 提交幂等 key 的 Redis TTL（秒）—— DB 唯一索引是最终防线，本 key 只是快路径 */
    private long idempotentTtlSeconds = 60;

    /** PENDING 任务滞留多久后由补偿任务重发（毫秒） */
    private long pendingRescueDelayMs = 15_000;
}
