package com.codejudge.contest.service;

import com.codejudge.common.constants.JudgeRedisKeys;
import com.codejudge.common.ws.RedisPushChannel;
import com.codejudge.common.ws.WsEnvelope;
import com.codejudge.common.ws.WsMessageType;
import com.codejudge.contest.config.ContestProperties;
import com.codejudge.contest.domain.po.Contest;
import com.codejudge.contest.domain.vo.ContestRankVO;
import com.codejudge.contest.domain.vo.RankEntryVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 榜单推送器：把「榜单变了」转化为**受控频率**的 WebSocket 广播。
 *
 * <p><b>为什么必须做合并（推送频率控制）</b>：一场热门竞赛的最后十分钟可能每秒产生几十次
 * AC。逐条推送的代价不是「多几条消息」，而是：
 * <ul>
 *   <li>每个订阅者收到几十条几乎相同的榜单，前端反复重绘表格 → 页面卡死；</li>
 *   <li>广播通道与网关带宽被榜单报文吃满；</li>
 *   <li>真正的信息（最终名次）反而淹没在噪音里 —— 榜单是「状态」而不是「事件流」。</li>
 * </ul>
 *
 * <p><b>三道闸门</b>：
 * <ol>
 *   <li><b>窗口合并</b>：以 topic 为单位，窗口（{@code cj.contest.push-flush-interval-ms}）
 *       内无论变更多少次，只推一次**当前终态**；</li>
 *   <li><b>内容去重</b>：若本次渲染结果与上次推送的内容签名一致（例如封榜期间公众榜恒定），
 *       直接跳过 —— 不推进版本号、不发消息；</li>
 *   <li><b>视图隔离</b>：公众榜（{:code public}）与内部榜（{@code full}）是两个独立主题，
 *       封榜期间公众主题天然「无变化」，因此自动静默，无需额外开关。</li>
 * </ol>
 *
 * <p><b>版本号语义</b>：{@code seq} 只在**真正推送**时递增，客户端据此可以确信
 * 「seq 没变 = 榜单没变」，不会因合并而误判丢包。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContestRankPusher {

    private final ContestRankService rankService;
    private final RedisPushChannel pushChannel;
    private final ContestProperties properties;
    private final StringRedisTemplate redis;

    /** contestId -> 首次变脏的时间戳（毫秒）；Map 的 key 集合即「待推送竞赛」 */
    private final Map<Long, Long> dirty = new ConcurrentHashMap<>();
    /** topic -> 上次推送的内容签名（用于去重） */
    private final Map<String, String> lastSignature = new ConcurrentHashMap<>();

    /** 榜单发生变化时登记；由 {@link #flush()} 在窗口到期后统一推送 */
    public void markDirty(Long contestId) {
        if (contestId == null || contestId == 0L) {
            return;
        }
        dirty.putIfAbsent(contestId, System.currentTimeMillis());
        try {
            redis.opsForValue().set(JudgeRedisKeys.CONTEST_PUSH_DIRTY_PREFIX + contestId, "1",
                    Duration.ofSeconds(Math.max(60, properties.getPushFlushIntervalMs() / 1000 * 10)));
        } catch (Exception e) {
            // 脏标记只是运维观测用，失败不影响推送
            log.debug("写推送脏标记失败：contestId={} err={}", contestId, e.getMessage());
        }
    }

    /**
     * 窗口到期冲刷：把窗口内累积的变更合并成一次推送。
     *
     * <p>为何不用「每次变更后延迟 N 毫秒推送」的 debounce：高负载下变更会持续不断到达，
     * debounce 会被无限推迟，最终完全不推（饿死）。以「首次变更时刻 + 固定窗口」为准，
     * 保证最坏延迟就是窗口长度。
     */
    @Scheduled(fixedDelayString = "${cj.contest.push-flush-interval-ms:1000}")
    public void flush() {
        if (dirty.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        long window = properties.getPushFlushIntervalMs();
        List<Long> ready = new ArrayList<>();
        dirty.forEach((contestId, since) -> {
            if (now - since >= window) {
                ready.add(contestId);
            }
        });
        for (Long contestId : ready) {
            // 先摘除再推送：推送期间的新的变更会重新登记，不会被本次窗口吞掉
            dirty.remove(contestId);
            try {
                pushView(contestId, false);
                pushView(contestId, true);
            } catch (Exception e) {
                log.warn("榜单推送失败：contestId={} err={}", contestId, e.getMessage());
            }
        }
    }

    /** 推送某一视图（public / full）；内容未变则静默 */
    private void pushView(Long contestId, boolean fullView) {
        String topic = rankService.topic(contestId, fullView);
        ContestRankVO vo;
        try {
            vo = rankService.rankForPush(contestId, fullView);
        } catch (Exception e) {
            log.warn("渲染榜单失败，跳过推送：contestId={} full={} err={}", contestId, fullView, e.getMessage());
            return;
        }
        if (vo == null) {
            return;
        }
        String signature = signatureOf(vo);
        String previous = lastSignature.get(topic);
        if (signature.equals(previous)) {
            log.debug("榜单内容未变，跳过推送：topic={}", topic);
            return;
        }
        lastSignature.put(topic, signature);
        WsEnvelope envelope = WsEnvelope.of(WsMessageType.RANK_UPDATE, topic, vo);
        envelope.setFull(fullView);
        // 版本号与本服务 REST 返回的 version 同源（WsSequencer），两端可交叉校验
        envelope.setSeq(rankService.nextVersion(topic));
        pushChannel.publish(envelope);
        log.info("推送榜单：topic={} seq={} entries={} frozen={} participants={}",
                topic, envelope.getSeq(), vo.getEntries() == null ? 0 : vo.getEntries().size(),
                vo.getFrozen(), vo.getTotalParticipants());
    }

    /**
     * 竞赛状态变更推送（开赛 / 封榜 / 结束）。
     *
     * <p>与 RANK_UPDATE 分开是有意为之：封榜时公众榜**内容不变**（因此不会触发
     * RANK_UPDATE 去重逻辑），但客户端必须知道「现在进入封榜了」才能改显示水印、
     * 停止期待名次变化。状态事件因此绕过内容去重，无条件推送。
     */
    public void pushStatus(Contest contest, String phase, String message) {
        if (contest == null || contest.getId() == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (boolean fullView : new boolean[]{false, true}) {
            Map<String, Object> data = Map.of(
                    "contestId", contest.getId(),
                    "phase", phase,
                    "status", contest.effectiveStatus(now),
                    "inFreezeWindow", contest.frozenAt(now),
                    "frozen", contest.frozenAt(now) && !fullView,
                    "full", fullView,
                    "message", message);
            String topic = rankService.topic(contest.getId(), fullView);
            WsEnvelope envelope = WsEnvelope.of(WsMessageType.CONTEST_STATUS, topic, data);
            envelope.setFull(fullView);
            envelope.setSeq(rankService.nextVersion(topic));
            pushChannel.publish(envelope);
        }
        log.info("推送竞赛状态：contestId={} phase={} message={}", contest.getId(), phase, message);
    }

    /**
     * 内容签名：只取「客户端可见的排序结果」，不含 version / updatedAt / 我的名次。
     *
     * <p>含 frozen 标记是为了让「封榜生效 / 解封」也能触发一次推送（此时条目可能恰好不变）。
     */
    private String signatureOf(ContestRankVO vo) {
        StringBuilder sb = new StringBuilder(256);
        sb.append(vo.getFrozen()).append('|').append(vo.getStatus()).append('|')
                .append(vo.getTotalParticipants()).append('|');
        List<RankEntryVO> entries = vo.getEntries() == null ? List.of() : vo.getEntries();
        sb.append(entries.stream()
                .map(e -> e.getUserId() + ":" + e.getWeight() + ":" + e.getPenaltySeconds())
                .collect(Collectors.joining(",")));
        return Integer.toHexString(sb.toString().hashCode());
    }
}
