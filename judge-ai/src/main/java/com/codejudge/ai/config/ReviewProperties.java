package com.codejudge.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 点评业务参数（与「连接/RAG」类配置分开，便于按产品策略调整）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "cj.review")
public class ReviewProperties {

    /**
     * 是否允许 AI 输出完整可提交题解。
     *
     * <p>默认 {@code false}。点评平台的核心价值是「讲清错在哪、该怎么想」；
     * 若默认给出可直接粘贴提交的完整代码，练习场会退化成答案库
     * （学员只需故意提交一次 WA 再让 AI 补全）。开启后 Prompt 才允许给完整解法。
     */
    private boolean allowFullSolution = false;

    /** 代码注入 Prompt 的长度上限（与 submission.code ≤32KB 对齐，防止 Prompt 被撑爆） */
    private int maxCodeLength = 32768;

    /** 逐用例结果注入 Prompt 的条数上限（超出部分只给汇总，避免长表格淹没有效信息） */
    private int maxCaseSamples = 8;

    /** 点评正文落库的长度上限（PG 为 TEXT 无硬上限，此处仅防异常大响应） */
    private int maxContentLength = 65535;

    /**
     * 是否启用 MQ 异步预生成（订阅 {@code ai_review#REQUESTED}）。
     *
     * <p>默认 {@code false}：点评的主路径是 SSE 实时生成，MQ 预生成是**增强项**。
     * 默认开启会让「服务能否启动」依赖 RocketMQ broker 的可达性 ——
     * 一个可选的增强项不该成为启动前提。需要时显式置为 true。
     */
    private boolean mqEnabled = false;
}
