package com.codejudge.contest.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.codejudge.api.client.problem.ProblemClient;
import com.codejudge.api.dto.contest.ContestContextDTO;
import com.codejudge.api.dto.problem.ProblemSummaryDTO;
import com.codejudge.common.domain.PageDTO;
import com.codejudge.common.exceptions.BizIllegalException;
import com.codejudge.common.exceptions.ForbiddenException;
import com.codejudge.common.utils.UserContext;
import com.codejudge.contest.config.ContestProperties;
import com.codejudge.contest.domain.dto.ContestFormDTO;
import com.codejudge.contest.domain.dto.ContestQuery;
import com.codejudge.contest.domain.po.Contest;
import com.codejudge.contest.domain.po.ContestProblem;
import com.codejudge.contest.domain.po.ContestRegistration;
import com.codejudge.contest.domain.vo.ContestDetailVO;
import com.codejudge.contest.domain.vo.ContestVO;
import com.codejudge.contest.mapper.ContestMapper;
import com.codejudge.contest.mapper.ContestProblemMapper;
import com.codejudge.contest.mapper.ContestRegistrationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 竞赛基础服务：创建、列表、详情、报名、内部上下文。
 *
 * <p>排行榜相关逻辑在 {@link ContestRankService}（Redis 侧），状态推进在
 * {@link ContestLifecycleService}（定时侧）—— 三者边界明确：
 * 本类只碰「竞赛定义与报名」这些关系型数据。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContestService {

    /** 题号缺省序列：26 题以内用 A~Z，超出后用 A1/A2… 保证可读且不重复 */
    private static final String LABEL_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private final ContestMapper contestMapper;
    private final ContestProblemMapper contestProblemMapper;
    private final ContestRegistrationMapper registrationMapper;
    private final ContestProperties properties;
    private final ProblemClient problemClient;

    // ==================================================================
    // 创建
    // ==================================================================

    /**
     * 建赛：写 contest + contest_problem（同事务），并校验题目合法性。
     *
     * <p>为什么要经内部 Feign 校验题目：竞赛题一旦写入，后续提交、榜单、快照都会以它为准。
     * 若允许编排一个不存在的题目 id，管理员会在赛前拿到一个「看起来正常、提交永远失败」
     * 的竞赛，而问题要到比赛当天才暴露。
     */
    @Transactional(rollbackFor = Exception.class)
    public ContestDetailVO create(ContestFormDTO form) {
        validateWindow(form);
        Long ownerId = UserContext.getUserId();

        List<Long> problemIds = form.getProblems().stream()
                .map(ContestFormDTO.ContestProblemForm::getProblemId)
                .toList();
        if (new HashSet<>(problemIds).size() != problemIds.size()) {
            throw new BizIllegalException("同一题目不能在竞赛中重复编排");
        }
        Map<Long, ProblemSummaryDTO> summaries = fetchSummaries(problemIds);
        for (Long problemId : problemIds) {
            ProblemSummaryDTO summary = summaries.get(problemId);
            if (summary == null) {
                throw new BizIllegalException("题目不存在：" + problemId);
            }
            if (summary.getStatus() == null || summary.getStatus() != 1) {
                throw new BizIllegalException("题目未发布，不能编排进竞赛：" + problemId
                        + "（当前状态 " + summary.getStatus() + "）");
            }
        }

        Contest contest = new Contest();
        contest.setTitle(form.getTitle());
        contest.setDescription(form.getDescription());
        contest.setRule(normalizeRule(form.getRule()));
        contest.setStartTime(form.getStartTime());
        contest.setEndTime(form.getEndTime());
        int freezeMinutes = form.getFreezeMinutes() == null ? 0 : form.getFreezeMinutes();
        contest.setFreezeMinutes(freezeMinutes);
        // 封榜时刻由结束时间推导：改赛程时不必同步改两个字段，避免运营漏改
        contest.setFreezeAt(freezeMinutes > 0 ? form.getEndTime().minusMinutes(freezeMinutes) : null);
        contest.setPenaltyMinutes(form.getPenaltyMinutes() == null ? 20 : form.getPenaltyMinutes());
        contest.setStatus(contest.effectiveStatus(LocalDateTime.now()));
        contest.setOwnerId(ownerId);
        contestMapper.insert(contest);

        int order = 0;
        for (ContestFormDTO.ContestProblemForm item : form.getProblems()) {
            ContestProblem cp = new ContestProblem();
            cp.setContestId(contest.getId());
            cp.setProblemId(item.getProblemId());
            cp.setLabel(item.getLabel() != null && !item.getLabel().isBlank()
                    ? item.getLabel() : defaultLabel(order));
            cp.setDisplayOrder(item.getDisplayOrder() == null ? order : item.getDisplayOrder());
            cp.setFullScore(item.getFullScore() == null ? 100 : item.getFullScore());
            cp.setSubmitCount(0);
            cp.setAcceptedCount(0);
            contestProblemMapper.insert(cp);
            order++;
        }
        log.info("竞赛创建成功：contestId={} title={} rule={} problems={} owner={} window=[{}, {}) freezeAt={}",
                contest.getId(), contest.getTitle(), contest.getRule(), problemIds.size(), ownerId,
                contest.getStartTime(), contest.getEndTime(), contest.getFreezeAt());
        return detail(contest.getId());
    }

    private void validateWindow(ContestFormDTO form) {
        if (!form.getEndTime().isAfter(form.getStartTime())) {
            throw new BizIllegalException("结束时间必须晚于开始时间");
        }
        int freezeMinutes = form.getFreezeMinutes() == null ? 0 : form.getFreezeMinutes();
        if (freezeMinutes < 0) {
            throw new BizIllegalException("封榜时长不能为负");
        }
        long durationMinutes = Duration.between(form.getStartTime(), form.getEndTime()).toMinutes();
        if (freezeMinutes >= durationMinutes) {
            // 封榜时刻会落到开赛之前 —— 等于「整场都封榜」，通常是运营填错（把时长当成时刻填）
            throw new BizIllegalException("封榜时长必须小于竞赛总时长（当前 " + freezeMinutes
                    + " 分钟 >= " + durationMinutes + " 分钟）");
        }
        if (form.getPenaltyMinutes() != null && form.getPenaltyMinutes() < 0) {
            throw new BizIllegalException("罚时不能为负");
        }
    }

    private String normalizeRule(String rule) {
        if (rule == null || rule.isBlank()) {
            return Contest.RULE_ACM;
        }
        String upper = rule.trim().toUpperCase();
        if (!Contest.RULE_ACM.equals(upper) && !Contest.RULE_IOI.equals(upper)) {
            throw new BizIllegalException("不支持的赛制：" + rule + "（可选 ACM / IOI）");
        }
        return upper;
    }

    private String defaultLabel(int index) {
        if (index < LABEL_CHARS.length()) {
            return String.valueOf(LABEL_CHARS.charAt(index));
        }
        return "A" + (index - LABEL_CHARS.length() + 1);
    }

    // ==================================================================
    // 查询
    // ==================================================================

    /**
     * 分页查询。
     *
     * <p>{@code status} 过滤按**时间区间**翻译，而不是过滤库里的 status 列：
     * 那列只是扫描任务的产出，最多滞后一个扫描周期。若直接按列过滤，
     * 会出现「列表里显示已结束、按 status=1 却查不到」的自相矛盾。
     */
    public PageDTO<ContestVO> page(ContestQuery query) {
        LocalDateTime now = LocalDateTime.now();
        LambdaQueryWrapper<Contest> wrapper = new LambdaQueryWrapper<Contest>()
                .like(query.getKeyword() != null && !query.getKeyword().isBlank(),
                        Contest::getTitle, query.getKeyword())
                .orderByDesc(Contest::getStartTime);
        if (query.getStatus() != null) {
            switch (query.getStatus()) {
                case Contest.ST_NOT_STARTED -> wrapper.gt(Contest::getStartTime, now);
                case Contest.ST_RUNNING -> wrapper.le(Contest::getStartTime, now)
                        .gt(Contest::getEndTime, now);
                case Contest.ST_FINISHED -> wrapper.le(Contest::getEndTime, now);
                default -> throw new BizIllegalException("非法的竞赛状态：" + query.getStatus());
            }
        }
        Long viewer = UserContext.getUser();
        if (Boolean.TRUE.equals(query.getRegisteredOnly()) && viewer != null) {
            Set<Long> registered = registeredContestIds(viewer);
            if (registered.isEmpty()) {
                return PageDTO.empty(0L, 0L);
            }
            wrapper.in(Contest::getId, registered);
        }

        Page<Contest> page = contestMapper.selectPage(query.toMpPage(), wrapper);
        List<Contest> records = page.getRecords();
        Map<Long, Integer> problemCounts = records.isEmpty() ? Map.of() : problemCounts(
                records.stream().map(Contest::getId).toList());
        Map<Long, Long> registerCounts = records.isEmpty() ? Map.of() : registerCounts(
                records.stream().map(Contest::getId).toList());
        Set<Long> mine = viewer == null ? Set.of() : registeredContestIds(viewer);

        return PageDTO.of(page, c -> {
            ContestVO vo = toVO(c, now);
            vo.setProblemCount(problemCounts.getOrDefault(c.getId(), 0));
            vo.setRegisterCount(registerCounts.getOrDefault(c.getId(), 0L));
            vo.setMyRegistered(viewer == null ? null : mine.contains(c.getId()));
            return vo;
        });
    }

    public ContestDetailVO detail(Long contestId) {
        LocalDateTime now = LocalDateTime.now();
        Contest contest = requireContest(contestId);
        ContestDetailVO vo = new ContestDetailVO();
        copyBase(contest, now, vo);

        List<ContestProblem> problems = problemMapperList(contestId);
        Map<Long, String> titles = titlesOf(problems.stream().map(ContestProblem::getProblemId).toList());
        vo.setProblems(problems.stream().map(p -> {
            ContestDetailVO.ContestProblemVO item = new ContestDetailVO.ContestProblemVO();
            item.setProblemId(p.getProblemId());
            item.setLabel(p.getLabel());
            item.setDisplayOrder(p.getDisplayOrder());
            item.setFullScore(p.getFullScore());
            item.setTitle(titles.get(p.getProblemId()));
            item.setSubmitCount(p.getSubmitCount());
            item.setAcceptedCount(p.getAcceptedCount());
            return item;
        }).toList());
        vo.setProblemCount(problems.size());
        vo.setRegisterCount(countRegistrations(contestId));
        Long viewer = UserContext.getUser();
        vo.setMyRegistered(viewer == null ? null : isRegistered(contestId, viewer));
        return vo;
    }

    private ContestVO toVO(Contest contest, LocalDateTime now) {
        ContestVO vo = new ContestVO();
        copyBase(contest, now, vo);
        return vo;
    }

    private void copyBase(Contest contest, LocalDateTime now, ContestVO vo) {
        vo.setId(contest.getId());
        vo.setTitle(contest.getTitle());
        vo.setDescription(contest.getDescription());
        vo.setRule(contest.isAcm() ? Contest.RULE_ACM : Contest.RULE_IOI);
        vo.setStartTime(contest.getStartTime());
        vo.setEndTime(contest.getEndTime());
        vo.setFreezeAt(contest.getFreezeAt());
        vo.setFreezeMinutes(contest.getFreezeMinutes());
        vo.setPenaltyMinutes(contest.getPenaltyMinutes());
        vo.setStatus(contest.effectiveStatus(now));
        vo.setFrozen(contest.frozenAt(now));
        vo.setOwnerId(contest.getOwnerId());
    }

    // ==================================================================
    // 报名
    // ==================================================================

    /**
     * 报名（幂等）：未开始的竞赛可提前报名，进行中可随时报名，已结束不可报名。
     *
     * <p>已取消的报名记录会被「复活」而不是插入新行 —— 唯一键
     * {@code uk_contest_registration(contest_id, user_id)} 不允许同一用户两条记录。
     */
    @Transactional(rollbackFor = Exception.class)
    public void register(Long contestId) {
        Long userId = UserContext.getUserId();
        if (userId == null || userId == 0L) {
            throw new ForbiddenException("请先登录后再报名");
        }
        Contest contest = requireContest(contestId);
        if (contest.effectiveStatus(LocalDateTime.now()) == Contest.ST_FINISHED) {
            throw new BizIllegalException("竞赛已结束，无法报名");
        }
        ContestRegistration existing = registrationMapper.selectOne(
                new LambdaQueryWrapper<ContestRegistration>()
                        .eq(ContestRegistration::getContestId, contestId)
                        .eq(ContestRegistration::getUserId, userId)
                        .last("LIMIT 1"));
        LocalDateTime now = LocalDateTime.now();
        if (existing == null) {
            ContestRegistration r = new ContestRegistration();
            r.setContestId(contestId);
            r.setUserId(userId);
            r.setRegisterTime(now);
            r.setStatus(ContestRegistration.ST_REGISTERED);
            registrationMapper.insert(r);
            log.info("竞赛报名成功：contestId={} userId={}", contestId, userId);
            return;
        }
        if (existing.getStatus() != null && existing.getStatus() == ContestRegistration.ST_REGISTERED) {
            log.info("重复报名（幂等返回）：contestId={} userId={}", contestId, userId);
            return;
        }
        existing.setStatus(ContestRegistration.ST_REGISTERED);
        existing.setRegisterTime(now);
        registrationMapper.updateById(existing);
        log.info("重新报名成功：contestId={} userId={}", contestId, userId);
    }

    public boolean isRegistered(Long contestId, Long userId) {
        Long count = registrationMapper.selectCount(new LambdaQueryWrapper<ContestRegistration>()
                .eq(ContestRegistration::getContestId, contestId)
                .eq(ContestRegistration::getUserId, userId)
                .eq(ContestRegistration::getStatus, ContestRegistration.ST_REGISTERED));
        return count != null && count > 0;
    }

    private Set<Long> registeredContestIds(Long userId) {
        List<ContestRegistration> list = registrationMapper.selectList(
                new LambdaQueryWrapper<ContestRegistration>()
                        .eq(ContestRegistration::getUserId, userId)
                        .eq(ContestRegistration::getStatus, ContestRegistration.ST_REGISTERED));
        return list.stream().map(ContestRegistration::getContestId).collect(Collectors.toSet());
    }

    // ==================================================================
    // 内部上下文（judge-submission 提交校验用）
    // ==================================================================

    /**
     * 竞赛上下文：供 judge-submission 在受理竞赛提交前校验。
     *
     * <p>状态按当前时间**实时推导**：即便扫描任务还没把状态写回库，
     * 也不会把刚开始的竞赛当成未开始而误拒提交。
     */
    public ContestContextDTO context(Long contestId, Long userId) {
        Contest contest = requireContest(contestId);
        LocalDateTime now = LocalDateTime.now();
        List<ContestProblem> problems = problemMapperList(contestId);

        ContestContextDTO dto = new ContestContextDTO();
        dto.setContestId(contest.getId());
        dto.setTitle(contest.getTitle());
        dto.setRule(contest.isAcm() ? Contest.RULE_ACM : Contest.RULE_IOI);
        dto.setStatus(contest.effectiveStatus(now));
        dto.setStartTime(contest.getStartTime());
        dto.setEndTime(contest.getEndTime());
        dto.setFreezeAt(contest.getFreezeAt());
        dto.setFreezeMinutes(contest.getFreezeMinutes());
        dto.setPenaltyMinutes(contest.getPenaltyMinutes());
        dto.setNeedRegister(properties.isRequireRegistration());
        dto.setProblemIds(problems.stream().map(ContestProblem::getProblemId).toList());
        dto.setFullScores(problems.stream().collect(Collectors.toMap(
                p -> String.valueOf(p.getProblemId()),
                p -> p.getFullScore() == null ? 100 : p.getFullScore(),
                (a, b) -> a, LinkedHashMap::new)));
        if (userId != null && userId != 0L) {
            dto.setRegistered(isRegistered(contestId, userId));
        }
        return dto;
    }

    // ==================================================================
    // 工具
    // ==================================================================

    public Contest requireContest(Long contestId) {
        if (contestId == null) {
            throw new BizIllegalException("竞赛 id 不能为空");
        }
        Contest contest = contestMapper.selectById(contestId);
        if (contest == null) {
            throw new BizIllegalException(404, "竞赛不存在：" + contestId);
        }
        return contest;
    }

    /** 校验当前用户可管理该竞赛（归属人本人或员工） */
    public void checkManageable(Contest contest) {
        Long current = UserContext.getUser();
        if (current == null) {
            // 内部 Feign 调用（无 user-info），放行；对外请求经网关必带身份
            return;
        }
        if (current.equals(contest.getOwnerId()) || UserContext.hasRole(1)) {
            return;
        }
        throw new ForbiddenException("仅竞赛创建者或管理员可执行该操作");
    }

    /** CAS 更新状态，避免多实例并发扫描时后写覆盖前写 */
    public boolean updateStatus(Contest contest, int expected, int target) {
        int rows = contestMapper.update(null, new LambdaUpdateWrapper<Contest>()
                .eq(Contest::getId, contest.getId())
                .eq(Contest::getStatus, expected)
                .set(Contest::getStatus, target));
        return rows == 1;
    }

    private List<ContestProblem> problemMapperList(Long contestId) {
        return contestProblemMapper.selectList(new LambdaQueryWrapper<ContestProblem>()
                .eq(ContestProblem::getContestId, contestId)
                .orderByAsc(ContestProblem::getDisplayOrder)
                .orderByAsc(ContestProblem::getId));
    }

    private Map<Long, Integer> problemCounts(List<Long> contestIds) {
        List<ContestProblem> all = contestProblemMapper.selectList(new LambdaQueryWrapper<ContestProblem>()
                .in(ContestProblem::getContestId, contestIds));
        return all.stream().collect(Collectors.groupingBy(ContestProblem::getContestId,
                Collectors.collectingAndThen(Collectors.counting(), Long::intValue)));
    }

    private Map<Long, Long> registerCounts(List<Long> contestIds) {
        List<ContestRegistration> all = registrationMapper.selectList(
                new LambdaQueryWrapper<ContestRegistration>()
                        .in(ContestRegistration::getContestId, contestIds)
                        .eq(ContestRegistration::getStatus, ContestRegistration.ST_REGISTERED));
        return all.stream().collect(Collectors.groupingBy(ContestRegistration::getContestId,
                Collectors.counting()));
    }

    private Long countRegistrations(Long contestId) {
        Long count = registrationMapper.selectCount(new LambdaQueryWrapper<ContestRegistration>()
                .eq(ContestRegistration::getContestId, contestId)
                .eq(ContestRegistration::getStatus, ContestRegistration.ST_REGISTERED));
        return count == null ? 0L : count;
    }

    /** 题目标题（内部 Feign）；题目服务不可用时返回空表，页面降级为只显示题号 */
    public Map<Long, String> titlesOf(List<Long> problemIds) {
        if (problemIds == null || problemIds.isEmpty()) {
            return Map.of();
        }
        try {
            List<ProblemSummaryDTO> list = problemClient.listSummaries(
                    new ArrayList<>(new LinkedHashSet<>(problemIds)));
            if (list == null) {
                return Map.of();
            }
            return list.stream()
                    .filter(s -> s.getProblemId() != null)
                    .collect(Collectors.toMap(ProblemSummaryDTO::getProblemId,
                            s -> s.getTitle() == null ? "" : s.getTitle(), (a, b) -> a));
        } catch (Exception e) {
            log.warn("拉取题目摘要失败，本次不展示标题：{}", e.getMessage());
            return Map.of();
        }
    }

    /** 建赛校验用：题目摘要（失败即抛出，建赛必须知道题目是否合法） */
    private Map<Long, ProblemSummaryDTO> fetchSummaries(List<Long> problemIds) {
        List<ProblemSummaryDTO> list;
        try {
            list = problemClient.listSummaries(problemIds);
        } catch (Exception e) {
            log.error("拉取题目摘要失败：{}", e.getMessage(), e);
            throw new BizIllegalException("题目服务不可用，暂时无法建赛");
        }
        if (list == null) {
            return Map.of();
        }
        return list.stream()
                .filter(s -> s.getProblemId() != null)
                .collect(Collectors.toMap(ProblemSummaryDTO::getProblemId, Function.identity(), (a, b) -> a));
    }

    /** 竞赛是否需要报名才能提交 */
    public boolean registrationRequired() {
        return properties.isRequireRegistration();
    }

    /** 供测试与运维判断「竞赛是否已结束」 */
    public boolean isFinished(Contest contest) {
        return contest.effectiveStatus(LocalDateTime.now()) == Contest.ST_FINISHED;
    }
}
