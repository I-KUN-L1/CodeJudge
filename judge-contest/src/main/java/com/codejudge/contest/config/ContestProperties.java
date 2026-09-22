package com.codejudge.contest.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 竞赛域参数（前缀 {@code cj.contest}）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "cj.contest")
public class ContestProperties {

    /** 榜单下发条数上限（top-N）。全量榜在竞赛结束后可放开，但默认限制避免长尾拖慢首屏 */
    private int rankTopSize = 100;

    /**
     * 榜单推送的合并窗口（毫秒）。
     *
     * <p>这是「推送频率控制」的核心参数：一场热门竞赛在最后十分钟可能每秒产生几十次 AC，
     * 若逐条推送，单个客户端会收到几十条几乎相同的榜单，把浏览器打满。
     * 合并策略：窗口内的多次变更只推一次，且只推**当前终态**（中间态直接丢弃）。
     * 值越小越实时，越大越省带宽；1s 是竞技场景下「看起来实时」与「流量可控」的平衡点。
     */
    private long pushFlushIntervalMs = 1000;

    /** 竞赛状态扫描间隔（毫秒）：开赛 / 封榜 / 结束三个时点的状态推进与快照 */
    private long lifecycleScanIntervalMs = 10_000;

    /**
     * 提交竞赛题是否需要已报名。
     * 默认 true：未报名的用户提交竞赛题会被拒（否则任何人都能往榜里刷）。
     */
    private boolean requireRegistration = true;

    /** 竞赛结束后榜单的保留时长（小时），到期由 Redis TTL 自动回收；封榜/终榜快照已落库 */
    private int rankTtlAfterEndHours = 24;

    /** 单次重建（rebuild）允许扫描的提交数上限，防止一次请求打爆内存与 Feign 超时 */
    private int rebuildMaxSubmissions = 20_000;
}
