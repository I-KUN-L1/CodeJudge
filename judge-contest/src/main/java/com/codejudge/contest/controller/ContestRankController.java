package com.codejudge.contest.controller;

import com.codejudge.common.annotation.RequireRole;
import com.codejudge.common.constants.UserRole;
import com.codejudge.common.domain.R;
import com.codejudge.common.utils.UserContext;
import com.codejudge.contest.domain.po.Contest;
import com.codejudge.contest.domain.po.ContestRankSnapshot;
import com.codejudge.contest.domain.vo.ContestRankVO;
import com.codejudge.contest.domain.vo.RebuildReportVO;
import com.codejudge.contest.service.ContestRankPusher;
import com.codejudge.contest.service.ContestRankService;
import com.codejudge.contest.service.ContestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 排行榜接口（网关前缀 /contests/**）。
 *
 * <p><b>{@code full} 参数是本接口的权限开关</b>：封榜期间 {@code full=true} 返回实时榜，
 * 仅教师/管理员可得；普通用户传了也只会得到冻结榜（400 而不是静默降级？——
 * 这里选择**显式拒绝**，见 {@link #rank}，因为「以为看到全量、实际看到冻结」
 * 会让裁判/教学人员做出错误判断）。
 */
@Slf4j
@RestController
@RequestMapping("/contests")
@RequiredArgsConstructor
@Tag(name = "竞赛排行榜", description = "实时榜（Redis ZSet）、封榜冻结榜、快照与终榜重建")
public class ContestRankController {

    private final ContestRankService rankService;
    private final ContestService contestService;
    private final ContestRankPusher pusher;

    @Operation(summary = "排行榜", description = "封榜期间返回冻结榜；教师/管理员可 full=true 看实时榜")
    @GetMapping("/{id}/rank")
    public R<ContestRankVO> rank(@PathVariable("id") Long id,
                                 @RequestParam(value = "top", required = false) Integer top,
                                 @RequestParam(value = "full", required = false, defaultValue = "false") boolean full) {
        boolean privileged = UserContext.hasRole(UserRole.STAFF.getCode(), UserRole.TEACHER.getCode());
        if (full && !privileged) {
            throw new com.codejudge.common.exceptions.ForbiddenException(
                    "无权查看全量榜单（仅教师/管理员可在封榜期间查看实时榜）");
        }
        Long viewer = UserContext.getUser();
        return R.ok(rankService.rank(id, top, full, viewer));
    }

    @Operation(summary = "手动封榜", description = "竞赛创建者/管理员；幂等，常用于未配置封榜时长时的赛时操作")
    @RequireRole({UserRole.STAFF, UserRole.TEACHER})
    @PostMapping("/{id}/freeze")
    public R<Boolean> freeze(@PathVariable("id") Long id) {
        Contest contest = contestService.requireContest(id);
        contestService.checkManageable(contest);
        boolean done = rankService.freeze(id, "manual:" + UserContext.getUserId());
        if (done) {
            // 封榜后立刻广播状态：公众榜内容未变（因此不会触发 RANK_UPDATE），
            // 但客户端必须知道「现在进入封榜」，否则会一直等一个不会到来的名次更新
            pusher.pushStatus(contestService.requireContest(id), "FROZEN",
                    "管理员手动封榜，公开榜单冻结");
        }
        return R.ok(done);
    }

    @Operation(summary = "榜单快照", description = "封榜(FROZEN)与终榜(FINAL)留档记录；仅教师/管理员")
    @RequireRole({UserRole.STAFF, UserRole.TEACHER})
    @GetMapping("/{id}/snapshots")
    public R<List<Map<String, Object>>> snapshots(@PathVariable("id") Long id) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ContestRankSnapshot s : rankService.listSnapshots(id)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", s.getSnapshotType());
            item.put("snapshotAt", s.getSnapshotAt());
            item.put("size", s.getRankJson() == null ? 0 : s.getRankJson().length());
            out.add(item);
        }
        return R.ok(out);
    }

    @Operation(summary = "终榜重建", description = "从 judge-submission 的提交表按提交时间回放重算榜单；竞赛创建者/管理员")
    @RequireRole({UserRole.STAFF, UserRole.TEACHER})
    @PostMapping("/{id}/rank/rebuild")
    public R<RebuildReportVO> rebuild(@PathVariable("id") Long id,
                                      @RequestParam(value = "refreeze", required = false, defaultValue = "false")
                                      boolean refreeze) {
        Contest contest = contestService.requireContest(id);
        contestService.checkManageable(contest);
        RebuildReportVO report = rankService.rebuild(id, refreeze);
        pusher.markDirty(id);
        log.info("管理员触发榜单重建：contestId={} operator={} at={}", id, UserContext.getUserId(),
                LocalDateTime.now());
        return R.ok(report);
    }
}
