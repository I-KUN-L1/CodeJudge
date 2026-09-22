package com.codejudge.contest.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.codejudge.contest.domain.po.Contest;
import com.codejudge.contest.domain.po.ContestRankSnapshot;
import com.codejudge.contest.mapper.ContestMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 竞赛生命周期推进：未开始 → 进行中 → 已结束，以及其中的**封榜**时点。
 *
 * <p><b>为什么用「时间推导 + 定时落库」而不是「定时器/Cron 排程」</b>：
 * 排程方案在服务重启、时钟回拨、竞赛临时改期时都会丢触发（要么漏开赛、要么漏封榜）。
 * 本方案把「真实状态」定义为<b>当前时间的函数</b>（{@link Contest#effectiveStatus}），
 * 扫描只是把推导结果落库并触发副作用（快照、推送）。即使服务停摆半小时，
 * 重启后第一次扫描就能自动补上漏掉的状态与快照 —— 幂等、可自愈。
 *
 * <p><b>多实例安全</b>：状态迁移用 CAS（{@code WHERE status = 旧值}），封榜用 Redis SETNX +
 * 「快照是否已存在」双重幂等，因此多个实例同时扫描不会重复封榜或重复留档。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContestLifecycleService {

    private final ContestMapper contestMapper;
    private final ContestService contestService;
    private final ContestRankService rankService;
    private final ContestRankPusher pusher;

    /**
     * 扫描未结束的竞赛并推进状态。
     *
     * <p>只扫 {@code status != 2}（已结束）的竞赛：已结束的无需再管，
     * 避免竞赛越积越多之后每轮扫描都全表遍历。
     */
    @Scheduled(initialDelayString = "${cj.contest.lifecycle-scan-interval-ms:10000}",
            fixedDelayString = "${cj.contest.lifecycle-scan-interval-ms:10000}")
    public void scan() {
        LocalDateTime now = LocalDateTime.now();
        List<Contest> contests;
        try {
            contests = contestMapper.selectList(new LambdaQueryWrapper<Contest>()
                    .ne(Contest::getStatus, Contest.ST_FINISHED));
        } catch (Exception e) {
            log.warn("竞赛状态扫描查询失败：{}", e.getMessage());
            return;
        }
        for (Contest contest : contests) {
            try {
                advance(contest, now);
            } catch (Exception e) {
                // 单个竞赛出错不能拖垮整轮扫描
                log.error("竞赛状态推进失败：contestId={}", contest.getId(), e);
            }
        }
    }

    private void advance(Contest contest, LocalDateTime now) {
        int effective = contest.effectiveStatus(now);
        Integer stored = contest.getStatus();

        // ① 状态迁移（CAS）：只有真正迁移成功才推送，避免多实例重复推送
        if (stored == null || stored != effective) {
            if (contestService.updateStatus(contest, stored == null ? effective : stored, effective)) {
                log.info("竞赛状态推进：contestId={} {} -> {} title={}",
                        contest.getId(), describe(stored), describe(effective), contest.getTitle());
                if (effective == Contest.ST_RUNNING) {
                    pusher.pushStatus(contest, "STARTED", "竞赛已开始");
                } else if (effective == Contest.ST_FINISHED) {
                    pusher.pushStatus(contest, "FINISHED", "竞赛已结束，榜单解封");
                }
            }
        }

        // ② 封榜时点：只要「已到封榜时刻且在赛程内」且尚无封榜快照，就执行（幂等）
        if (contest.frozenAt(now) && !rankService.hasSnapshot(contest.getId(), ContestRankSnapshot.TYPE_FROZEN)) {
            if (rankService.freeze(contest.getId(), "lifecycle")) {
                pusher.pushStatus(contest, "FROZEN",
                        "已到封榜时刻，公开榜单冻结（内部继续记录）");
            }
        }

        // ③ 终榜留档：结束后写一条 FINAL 快照（公开榜此时已自动切回实时榜 = 完整榜）
        if (effective == Contest.ST_FINISHED
                && !rankService.hasSnapshot(contest.getId(), ContestRankSnapshot.TYPE_FINAL)) {
            rankService.writeSnapshot(contest, ContestRankSnapshot.TYPE_FINAL, rankService.liveKey(contest.getId()));
        }
    }

    private String describe(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case Contest.ST_NOT_STARTED -> "未开始";
            case Contest.ST_RUNNING -> "进行中";
            case Contest.ST_FINISHED -> "已结束";
            default -> String.valueOf(status);
        };
    }
}
