package com.codejudge.contest.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.codejudge.api.client.submission.SubmissionClient;
import com.codejudge.api.client.user.UserClient;
import com.codejudge.api.dto.contest.ContestSubmissionDTO;
import com.codejudge.api.dto.submission.SubmissionResultMessage;
import com.codejudge.api.dto.user.UserDTO;
import com.codejudge.common.constants.JudgeRedisKeys;
import com.codejudge.common.exceptions.BizIllegalException;
import com.codejudge.common.ws.WsSequencer;
import com.codejudge.contest.config.ContestProperties;
import com.codejudge.contest.domain.po.Contest;
import com.codejudge.contest.domain.po.ContestProblem;
import com.codejudge.contest.domain.po.ContestRankSnapshot;
import com.codejudge.contest.domain.vo.ContestRankVO;
import com.codejudge.contest.domain.vo.RankEntryVO;
import com.codejudge.contest.domain.vo.RebuildReportVO;
import com.codejudge.contest.mapper.ContestMapper;
import com.codejudge.contest.mapper.ContestProblemMapper;
import com.codejudge.contest.mapper.ContestRankSnapshotMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 排行榜服务：Redis ZSet 实时榜的**唯一写者**。
 *
 * <p><b>三段排序（单 ZSet 完成，不依赖二次排序）</b>：
 * <pre>
 *   score = 权重 × 10^12 + (999999 - 罚时秒) × 10^6 + (999999 - 末次通过偏移秒)
 *
 *   关键字① 权重         ACM=通过题数 / IOI=总分        → 多者在前
 *   关键字② 罚时秒       ACM=错误提交罚时 + 各题 AC 耗时
 *                        IOI=末次得分时刻偏移            → 少者在前
 *   关键字③ 末次通过偏移  同权重同罚时的最终裁决         → 早者在前
 * </pre>
 * ZSet 升序，读榜用 {@code reverseRange} 即得「权重高者在前 → 罚时少者在前 → 通过更早者在前」。
 * 编码用常量的理由：跨服务/跨语言（前端、运维脚本）都能用同一把尺子解释榜面。
 *
 * <p><b>为什么名次必须由编码决定、而不是取回列表再在应用侧排序</b>：名次是分布式共识。
 * 若名次由各消费端自行二次排序，不同语言/版本的前端会因排序稳定性差异给出不同名次，
 * 而榜单的权威性正建立在「所有人看到同一份名次」上。
 *
 * <p>⚠️ <b>精度边界</b>：分值经 IEEE-754 double 传递，仅 ≤ 2^53 的整数可精确表示。
 * 本编码上限 ≈ (权重+1)×10^12，故 <b>权重须 ≤ 8999</b>（通过题数 / 总分，实际远低于此）。
 * 调整 {@link #WEIGHT_FACTOR} 等常量时必须同步修改 Lua 脚本内的同名常量。
 *
 * <p><b>封榜的数据一致性策略（本服务最关键的设计）</b>：
 * <ol>
 *   <li>实时榜 {@code judge:contest:rank:{cid}} <b>永远更新</b>，封榜期间照写不误 ——
 *       榜单没有「暂停」这个状态，只有「给谁看哪个榜」；</li>
 *   <li>封榜瞬间用 {@code ZUNIONSTORE frozen 1 live} **单命令原子**生成冻结榜，
 *       不存在拍到一半的中间态；</li>
 *   <li>解封（竞赛结束）不需要任何合并动作：读回实时榜即得到完整结果，
 *       「封榜期间的成绩」从未丢失过；</li>
 *   <li>冻结榜与终榜各自落库一条快照（{@code contest_rank_snapshot}），
 *       使这份「当时的事实」不依赖 Redis 的存活。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContestRankService {

    /** 编码基数：权重段移位（10^12）。⚠️ 与 lua/contest_rank_update.lua 的 RANK_SHIFT 必须一致 */
    public static final long WEIGHT_FACTOR = 1_000_000_000_000L;
    /** 编码基数：罚时段移位（10^6）。⚠️ 与 Lua 的 MID_SHIFT 必须一致 */
    public static final long PENALTY_FACTOR = 1_000_000L;
    /** 段基数：段内「越优越大」，取反后保证小值胜出。⚠️ 与 Lua 的 SEG_BASE 必须一致 */
    public static final long SEGMENT_BASE = 999_999L;
    /** 权重段与罚时段之间的跨度（10^6）：解码中间段时的取模基数 */
    private static final long MID_SEGMENT_SPAN = WEIGHT_FACTOR / PENALTY_FACTOR;

    /** 落库快照的最大条数：LONGTEXT 兜得住，但没必要把十万行塞进一条记录 */
    private static final int SNAPSHOT_MAX_ENTRIES = 5000;
    /** 用户名本地缓存 TTL（秒）：避免每次推送都远程查一次用户服务 */
    private static final long NAME_CACHE_TTL_SECONDS = 600;
    /** 榜单视图主题后缀 */
    private static final String VIEW_PUBLIC = ":rank:public";
    private static final String VIEW_FULL = ":rank:full";
    /** 用户题目状态编码前缀（与 Lua 的 HSET 值格式一一对应） */
    private static final String STATE_AC = "ac:";
    private static final String STATE_SCORE = "s:";

    private final ContestMapper contestMapper;
    private final ContestProblemMapper contestProblemMapper;
    private final ContestRankSnapshotMapper snapshotMapper;
    private final StringRedisTemplate redis;
    private final RedisScript<List> contestRankScript;
    private final ObjectMapper objectMapper;
    private final ContestProperties properties;
    private final UserClient userClient;
    private final SubmissionClient submissionClient;
    private final WsSequencer sequencer;

    /** 用户名 TTL 缓存：userId -> [name, expireAtMillis] */
    private final Map<Long, Object[]> nameCache = new java.util.concurrent.ConcurrentHashMap<>();

    // ==================================================================
    // 一、写入：判题结果 → 榜单
    // ==================================================================

    /**
     * 应用一条判题结果（MQ 驱动，幂等）。
     *
     * @return true=榜单发生了变化（调用方据此触发合并推送）
     */
    public boolean applyResult(SubmissionResultMessage msg) {
        if (msg == null || msg.getContestId() == null || msg.getContestId() == 0L) {
            return false;
        }
        if (msg.getUserId() == null || msg.getProblemId() == null) {
            log.warn("判题结果缺少用户或题目，忽略：submissionId={}", msg.getSubmissionId());
            return false;
        }
        Contest contest = contestMapper.selectById(msg.getContestId());
        if (contest == null) {
            log.warn("判题结果指向不存在的竞赛，忽略：contestId={} submissionId={}",
                    msg.getContestId(), msg.getSubmissionId());
            return false;
        }
        ContestProblem problem = problemOf(contest.getId(), msg.getProblemId());
        if (problem == null) {
            log.warn("题目不属于该竞赛，忽略：contestId={} problemId={}", contest.getId(), msg.getProblemId());
            return false;
        }

        long submitSec = resolveSubmitSeconds(msg, contest);
        long endSec = epochSeconds(contest.getEndTime());
        if (submitSec > endSec) {
            // 竞赛结束后才提交的代码不计入（判题可能因排队/重试而在赛后完成，但提交时刻是权威依据）
            log.info("提交发生在竞赛结束后，不计入榜单：contestId={} submissionId={} submitSec={} endSec={}",
                    contest.getId(), msg.getSubmissionId(), submitSec, endSec);
            return false;
        }

        boolean accepted = "AC".equalsIgnoreCase(msg.getVerdict());
        boolean changed = executeUpdate(contest, problem, msg.getUserId(), accepted,
                msg.getScore() == null ? 0 : msg.getScore(), submitSec);
        if (changed) {
            log.info("榜单变更：contestId={} userId={} problemId={} verdict={} submissionId={}",
                    contest.getId(), msg.getUserId(), msg.getProblemId(), msg.getVerdict(), msg.getSubmissionId());
        }
        return changed;
    }

    /**
     * 执行原子更新（Lua）。
     *
     * @return true=榜单成员分值发生变化
     */
    private boolean executeUpdate(Contest contest, ContestProblem problem, Long userId, boolean accepted,
                                  int score, long submitSec) {
        List<String> keys = List.of(liveKey(contest.getId()), userStatusKey(contest.getId(), userId));
        long penaltyPerWrong = contest.penaltyMinutesOrDefault() * 60L;
        List<String> args = List.of(
                String.valueOf(userId),
                String.valueOf(problem.getProblemId()),
                contest.isAcm() ? Contest.RULE_ACM : Contest.RULE_IOI,
                accepted ? "1" : "0",
                String.valueOf(score),
                String.valueOf(problem.getFullScore() == null ? 0 : problem.getFullScore()),
                String.valueOf(penaltyPerWrong),
                String.valueOf(epochSeconds(contest.getStartTime())),
                String.valueOf(submitSec),
                String.valueOf(ttlSeconds(contest)));
        List<?> result = redis.execute(contestRankScript, keys, args.toArray());
        if (result == null || result.isEmpty()) {
            log.error("榜单脚本返回空，疑似脚本未加载：contestId={}", contest.getId());
            return false;
        }
        return toLong(result.get(0)) == 1L;
    }

    // ==================================================================
    // 二、封榜与快照
    // ==================================================================

    /**
     * 封榜：生成冻结榜 + 落库快照。**幂等**（多实例并发扫描只生效一次）。
     *
     * @return true=本次调用真正执行了封榜
     */
    public boolean freeze(Long contestId, String reason) {
        Contest contest = contestMapper.selectById(contestId);
        if (contest == null) {
            throw new BizIllegalException(404, "竞赛不存在：" + contestId);
        }
        if (hasSnapshot(contestId, ContestRankSnapshot.TYPE_FROZEN)) {
            log.info("竞赛已封榜过（存在 FROZEN 快照），跳过：contestId={}", contestId);
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        // 手动封榜（创建时未配 freeze_minutes）需要回填封榜时刻，否则 frozenAt() 永远为 false
        if (contest.getFreezeAt() == null || contest.getFreezeAt().isAfter(now)) {
            contest.setFreezeAt(now);
            contestMapper.updateById(contest);
        }
        String lockKey = JudgeRedisKeys.CONTEST_FREEZE_LOCK_PREFIX + contestId;
        Boolean first = redis.opsForValue().setIfAbsent(lockKey, "1", Duration.ofHours(48));
        if (!Boolean.TRUE.equals(first)) {
            log.info("封榜已被其他实例处理（SETNX 未命中）：contestId={}", contestId);
            return false;
        }

        long ttl = ttlSeconds(contest);

        // ① 先冻结逐题状态副本，**必须在 ZUNIONSTORE 覆盖冻结榜之前**：
        //    副本的「旧成员集」要靠当前冻结榜反查，一旦被覆盖就查不到了（旧副本会残留到 TTL 到期）。
        freezeUserStates(contestId, ttl);

        // ② 原子快照：ZUNIONSTORE dest 1 live —— 单命令，不存在部分写入
        Long copied = redis.opsForZSet().unionAndStore(liveKey(contestId), Collections.emptyList(),
                frozenKey(contestId));
        String fk = frozenKey(contestId);
        if (copied != null && copied > 0) {
            redis.expire(fk, Duration.ofSeconds(ttl));
        } else {
            // 封榜时还没有任何成绩：冻结榜为空是**正确语义**（ICPC 封榜就该是一片空白），
            // 但要把 key 清掉，避免上一届残留数据被当成本届冻结榜
            redis.delete(fk);
        }

        // ③ 留档。此处渲染走冻结榜 + 冻结状态副本，因此快照里的 problemStatus 也是封榜时刻的
        writeSnapshot(contest, ContestRankSnapshot.TYPE_FROZEN, fk);
        log.info("封榜完成：contestId={} reason={} frozenEntries={} at={}",
                contestId, reason, copied, now);
        return true;
    }

    /**
     * 冻结「用户 × 题目」状态副本（封榜的第二步，与冻结榜同批生效）。
     *
     * <p>榜单行的 {@code problemStatus} 读的是实时状态 Hash，不是 ZSet —— 只冻结 ZSet 会留下
     * 「名次冻结、明细泄漏」的口子：封榜期间有人通过题目，公开榜的单元格会从 {@code -1} 变成
     * {@code +}，等于公开宣布「封榜后谁过了题」（实测于 P4 验收首轮，D4 断言抓到）。
     *
     * <p>清理策略：先按「旧冻结榜成员 ∪ 当前实时榜成员」清空旧副本，再从实时状态复制。
     * 两集合取并集是为了同时覆盖「重复封榜」与「重建后重新封榜」两条路径。
     * 副本带 TTL，即使清理有漏也不会永久残留。
     */
    private void freezeUserStates(Long contestId, long ttlSeconds) {
        Set<String> liveMembers = redis.opsForZSet().range(liveKey(contestId), 0, -1);
        Set<String> staleMembers = redis.opsForZSet().range(frozenKey(contestId), 0, -1);
        Set<String> toClear = new HashSet<>();
        if (staleMembers != null) {
            toClear.addAll(staleMembers);
        }
        if (liveMembers != null) {
            toClear.addAll(liveMembers);
        }
        for (String member : toClear) {
            redis.delete(frozenUserStatusKey(contestId, parseLong(member)));
        }
        if (liveMembers == null || liveMembers.isEmpty()) {
            return;
        }
        for (String member : liveMembers) {
            Long userId = parseLong(member);
            Map<Object, Object> state = redis.opsForHash().entries(userStatusKey(contestId, userId));
            if (state.isEmpty()) {
                continue;
            }
            String frozenStatusKey = frozenUserStatusKey(contestId, userId);
            redis.opsForHash().putAll(frozenStatusKey, state);
            redis.expire(frozenStatusKey, Duration.ofSeconds(ttlSeconds));
        }
    }

    /**
     * 落库榜单快照。
     *
     * @param sourceKey 榜单数据来源（封榜快照取冻结榜，终榜取实时榜）
     */
    public void writeSnapshot(Contest contest, String type, String sourceKey) {
        List<ContestProblem> problems = problems(contest.getId());
        List<RankEntryVO> entries = renderEntries(contest, problems, sourceKey, SNAPSHOT_MAX_ENTRIES);
        ContestRankSnapshot snapshot = new ContestRankSnapshot();
        snapshot.setContestId(contest.getId());
        snapshot.setSnapshotType(type);
        snapshot.setSnapshotAt(LocalDateTime.now());
        try {
            snapshot.setRankJson(objectMapper.writeValueAsString(entries));
        } catch (Exception e) {
            log.error("榜单快照序列化失败：contestId={} type={}", contest.getId(), type, e);
            snapshot.setRankJson("[]");
        }
        snapshotMapper.insert(snapshot);
        log.info("榜单快照落库：contestId={} type={} entries={}", contest.getId(), type, entries.size());
    }

    public boolean hasSnapshot(Long contestId, String type) {
        Long count = snapshotMapper.selectCount(new LambdaQueryWrapper<ContestRankSnapshot>()
                .eq(ContestRankSnapshot::getContestId, contestId)
                .eq(ContestRankSnapshot::getSnapshotType, type));
        return count != null && count > 0;
    }

    public List<ContestRankSnapshot> listSnapshots(Long contestId) {
        return snapshotMapper.selectList(new LambdaQueryWrapper<ContestRankSnapshot>()
                .eq(ContestRankSnapshot::getContestId, contestId)
                .orderByAsc(ContestRankSnapshot::getSnapshotAt));
    }

    // ==================================================================
    // 三、读取
    // ==================================================================

    /**
     * 读取榜单。
     *
     * @param topN     条数上限，null=取配置默认值
     * @param fullView true=内部全量视图（封榜期间看实时榜，仅教师/管理员可得）
     * @param viewerId 查看者 id：用于回填「我的名次」；可空
     */
    public ContestRankVO rank(Long contestId, Integer topN, boolean fullView, Long viewerId) {
        Contest contest = contestMapper.selectById(contestId);
        if (contest == null) {
            throw new BizIllegalException(404, "竞赛不存在：" + contestId);
        }
        LocalDateTime now = LocalDateTime.now();
        boolean inFreeze = contest.frozenAt(now);
        boolean frozenView = inFreeze && !fullView;
        String key = frozenView ? frozenKey(contestId) : liveKey(contestId);
        int limit = (topN == null || topN <= 0) ? properties.getRankTopSize() : Math.min(topN, 1000);

        List<ContestProblem> problems = problems(contestId);
        ContestRankVO vo = new ContestRankVO();
        vo.setContestId(contestId);
        vo.setTitle(contest.getTitle());
        vo.setRule(contest.isAcm() ? Contest.RULE_ACM : Contest.RULE_IOI);
        vo.setStatus(contest.effectiveStatus(now));
        vo.setFreezeAt(contest.getFreezeAt());
        vo.setInFreezeWindow(inFreeze);
        vo.setFrozen(frozenView);
        vo.setFullView(fullView);
        vo.setLabels(problems.stream().map(this::labelOf).toList());
        vo.setUpdatedAt(now);
        vo.setVersion(sequencer.current(topic(contestId, fullView)));
        Long card = redis.opsForZSet().zCard(key);
        vo.setTotalParticipants(card == null ? 0L : card);
        vo.setEntries(renderEntries(contest, problems, key, limit));

        if (viewerId != null) {
            Long reverse = redis.opsForZSet().reverseRank(key, String.valueOf(viewerId));
            if (reverse != null) {
                vo.setMyRank((int) (reverse + 1));
                // 榜内直接复用；榜外（top-N 之外）只取那一行，避免为看自己而渲染整段榜单
                RankEntryVO mine = vo.getEntries().stream()
                        .filter(e -> Objects.equals(e.getUserId(), viewerId))
                        .findFirst()
                        .orElseGet(() -> renderEntryAt(contest, problems, key, reverse.intValue()));
                vo.setMyEntry(mine);
            }
        }
        return vo;
    }

    /** 推送用视图：不带「我的名次」，减少每个订阅者一份的差异 */
    public ContestRankVO rankForPush(Long contestId, boolean fullView) {
        return rank(contestId, properties.getRankTopSize(), fullView, null);
    }

    /**
     * 渲染榜单条目（ZSet → VO）。
     *
     * <p>批量取数说明：用户状态 Hash 用 pipeline 一次取回（N 次往返 → 1 次），
     * 用户名用内部 Feign 批量查 + 本地 TTL 缓存。榜单渲染是高频路径（每次推送都要跑），
     * 这两处是它唯一的 I/O，必须批量化。
     */
    private List<RankEntryVO> renderEntries(Contest contest, List<ContestProblem> problems,
                                            String rankKey, int limit) {
        Set<ZSetOperations.TypedTuple<String>> tuples =
                redis.opsForZSet().reverseRangeWithScores(rankKey, 0, limit - 1L);
        if (tuples == null || tuples.isEmpty()) {
            return List.of();
        }
        return buildEntries(contest, problems, new ArrayList<>(tuples), 0,
                isFrozenView(contest.getId(), rankKey));
    }

    /** 只渲染榜上第 index 行（0 基，按分值倒序）—— 让榜外用户也能看到自己的名次 */
    private RankEntryVO renderEntryAt(Contest contest, List<ContestProblem> problems,
                                     String rankKey, int index) {
        Set<ZSetOperations.TypedTuple<String>> tuples =
                redis.opsForZSet().reverseRangeWithScores(rankKey, index, index);
        if (tuples == null || tuples.isEmpty()) {
            return null;
        }
        List<RankEntryVO> one = buildEntries(contest, problems, new ArrayList<>(tuples), index,
                isFrozenView(contest.getId(), rankKey));
        return one.isEmpty() ? null : one.get(0);
    }

    private List<RankEntryVO> buildEntries(Contest contest, List<ContestProblem> problems,
                                           List<ZSetOperations.TypedTuple<String>> rows, int rankOffset,
                                           boolean frozen) {
        List<Long> userIds = new ArrayList<>(rows.size());
        for (ZSetOperations.TypedTuple<String> t : rows) {
            userIds.add(parseLong(t.getValue()));
        }
        Map<Long, String> names = resolveUserNames(userIds);
        // 冻结视图必须读封榜时刻的状态副本：实时 Hash 会带出封榜后的进展（名次不动、明细泄漏）
        Map<Long, Map<String, String>> states = loadStates(contest.getId(), userIds, frozen);
        Map<String, ContestProblem> byProblemId = problems.stream()
                .collect(Collectors.toMap(p -> String.valueOf(p.getProblemId()), p -> p, (a, b) -> a));

        List<RankEntryVO> entries = new ArrayList<>(rows.size());
        int rank = rankOffset + 1;
        for (ZSetOperations.TypedTuple<String> t : rows) {
            Long userId = parseLong(t.getValue());
            double raw = t.getScore() == null ? 0d : t.getScore();
            // 三段解码：权重 / 罚时 / 末次通过偏移
            long encoded = (long) raw;
            long weight = encoded / WEIGHT_FACTOR;
            long penalty = SEGMENT_BASE - (encoded / PENALTY_FACTOR) % MID_SEGMENT_SPAN;
            long tieOffset = SEGMENT_BASE - (encoded % PENALTY_FACTOR);

            RankEntryVO e = new RankEntryVO();
            e.setRank(rank++);
            e.setUserId(userId);
            e.setUserName(names.getOrDefault(userId, String.valueOf(userId)));
            e.setWeight((int) weight);
            e.setPenaltySeconds((int) penalty);
            if (contest.isAcm()) {
                e.setSolvedCount((int) weight);
                e.setTotalScore(0);
            } else {
                e.setSolvedCount(0);
                e.setTotalScore((int) weight);
            }
            Map<String, String> state = states.getOrDefault(userId, Map.of());
            e.setProblemStatus(renderProblemStatus(contest, byProblemId, state));
            e.setLastAcceptedOffsetSeconds(lastAcceptedOffset(contest, state));
            entries.add(e);
        }
        return entries;
    }

    /**
     * 渲染 ICPC 记法的逐题状态。
     *
     * <p>ACM：{@code +} / {@code +2}（2 次错误后通过）/ {@code -3}（3 次错误未通过）；
     * IOI：直接给出该题得分（0 分且从未得分显示为 {@code -}）。
     */
    private Map<String, String> renderProblemStatus(Contest contest, Map<String, ContestProblem> byProblemId,
                                                    Map<String, String> state) {
        Map<String, String> out = new LinkedHashMap<>();
        if (contest.isAcm()) {
            for (Map.Entry<String, String> en : state.entrySet()) {
                ContestProblem p = byProblemId.get(en.getKey());
                if (p == null) {
                    continue;
                }
                String v = en.getValue();
                if (v == null) {
                    continue;
                }
                if (v.startsWith("ac:")) {
                    String[] parts = v.split(":");
                    int wrong = parts.length > 2 ? safeInt(parts[2]) : 0;
                    out.put(labelOf(p), wrong == 0 ? "+" : "+" + wrong);
                } else if (v.startsWith("w:")) {
                    out.put(labelOf(p), "-" + safeInt(v.substring(2)));
                }
            }
        } else {
            for (Map.Entry<String, String> en : state.entrySet()) {
                ContestProblem p = byProblemId.get(en.getKey());
                if (p == null || en.getValue() == null) {
                    continue;
                }
                String[] parts = en.getValue().split(":");
                int gained = parts.length > 1 ? safeInt(parts[1]) : 0;
                out.put(labelOf(p), gained <= 0 ? "-" : String.valueOf(gained));
            }
        }
        return out;
    }

    /**
     * 最后一次「通过 / 得分」距开赛的秒数；无通过记录为 null。
     *
     * <p>⚠️ 两种状态编码的时间戳位置不同，必须分别取：
     * ACM {@code ac:<通过时刻>:<错误数>} 取 [1]；IOI {@code s:<最高分>:<取得时刻>} 取 [2]。
     * 曾因统一取 [1] 而把 IOI 的**分数**当成时间戳，使该字段在 IOI 下恒为乱值
     * —— 榜单排序的第三关键字正是它，取错会直接写坏名次。
     */
    private Integer lastAcceptedOffset(Contest contest, Map<String, String> state) {
        long start = epochSeconds(contest.getStartTime());
        long last = -1;
        for (String v : state.values()) {
            if (v == null) {
                continue;
            }
            String[] parts = v.split(":");
            long ts;
            if (v.startsWith(STATE_AC)) {
                ts = parts.length >= 2 ? safeLong(parts[1]) : -1;
            } else if (v.startsWith(STATE_SCORE)) {
                ts = parts.length >= 3 ? safeLong(parts[2]) : -1;
            } else {
                // 'w:<错误数>'：只有错误提交，没有通过/得分时刻
                continue;
            }
            if (ts > last) {
                last = ts;
            }
        }
        return last < 0 ? null : (int) Math.max(0, last - start);
    }

    // ==================================================================
    // 四、终榜重建
    // ==================================================================

    /**
     * 从权威数据源（judge_submission 的提交表）重算榜单。
     *
     * <p>使用场景：Redis 数据丢失；罚时/赛制规则调整后需要回算历史竞赛；
     * 重判导致既有结论回退（增量更新只保证「单调向好」，不会把已通过的题撤销）。
     *
     * <p>实现要点：**按提交时间升序回放** —— Lua 的「首次 AC 才计分」语义依赖顺序，
     * 乱序回放会把「先错后对」算成「先对后错」。
     *
     * @param refreeze true=重建后重新生成封榜快照（默认 false，保留历史封榜这一事实）
     */
    public RebuildReportVO rebuild(Long contestId, boolean refreeze) {
        long started = System.currentTimeMillis();
        Contest contest = contestMapper.selectById(contestId);
        if (contest == null) {
            throw new BizIllegalException(404, "竞赛不存在：" + contestId);
        }
        List<ContestSubmissionDTO> submissions;
        try {
            submissions = submissionClient.listContestResults(contestId, properties.getRebuildMaxSubmissions());
        } catch (Exception e) {
            log.error("拉取竞赛提交失败：contestId={}", contestId, e);
            throw new BizIllegalException("提交服务不可用，无法重建榜单");
        }
        if (submissions == null) {
            submissions = List.of();
        }
        submissions = submissions.stream()
                .filter(s -> s.getUserId() != null && s.getProblemId() != null)
                .sorted((a, b) -> Long.compare(
                        a.getSubmitTimeEpochMs() == null ? 0L : a.getSubmitTimeEpochMs(),
                        b.getSubmitTimeEpochMs() == null ? 0L : b.getSubmitTimeEpochMs()))
                .toList();

        Map<Long, ContestProblem> problems = problems(contestId).stream()
                .collect(Collectors.toMap(ContestProblem::getProblemId, p -> p, (a, b) -> a));
        long endSec = epochSeconds(contest.getEndTime());

        // 清空派生数据（榜单 + 各用户题目状态），再从零回放
        redis.delete(liveKey(contestId));
        Set<Long> participants = new HashSet<>();
        for (ContestSubmissionDTO s : submissions) {
            participants.add(s.getUserId());
        }
        if (!participants.isEmpty()) {
            redis.delete(participants.stream()
                    .map(uid -> userStatusKey(contestId, uid))
                    .collect(Collectors.toList()));
        }

        int replayed = 0;
        int changed = 0;
        for (ContestSubmissionDTO s : submissions) {
            ContestProblem problem = problems.get(s.getProblemId());
            if (problem == null) {
                continue;
            }
            long submitSec = s.getSubmitTimeEpochMs() == null
                    ? epochSeconds(LocalDateTime.now()) : s.getSubmitTimeEpochMs() / 1000L;
            if (submitSec > endSec) {
                continue;
            }
            boolean accepted = "AC".equalsIgnoreCase(s.getVerdict());
            if (executeUpdate(contest, problem, s.getUserId(), accepted,
                    s.getScore() == null ? 0 : s.getScore(), submitSec)) {
                changed++;
            }
            replayed++;
        }

        boolean refrozen = false;
        if (refreeze && contest.frozenAt(LocalDateTime.now())) {
            // 重新生成封榜快照：清掉冻结榜留档与封榜锁，再走一次标准封榜流程。
            // ⚠️ 这里**不要**手动 delete 冻结榜：freeze() 需要靠它反查上一次的逐题状态副本成员集
            //    才能清干净，而且 ZUNIONSTORE 本来就会覆盖它。手动删反而制造残留。
            redis.delete(JudgeRedisKeys.CONTEST_FREEZE_LOCK_PREFIX + contestId);
            snapshotMapper.delete(new LambdaQueryWrapper<ContestRankSnapshot>()
                    .eq(ContestRankSnapshot::getContestId, contestId)
                    .eq(ContestRankSnapshot::getSnapshotType, ContestRankSnapshot.TYPE_FROZEN));
            refrozen = freeze(contestId, "rebuild-refreeze");
        }

        RebuildReportVO report = new RebuildReportVO();
        report.setContestId(contestId);
        report.setFetched(submissions.size());
        report.setReplayed(replayed);
        report.setChanged(changed);
        Long card = redis.opsForZSet().zCard(liveKey(contestId));
        report.setParticipants(card == null ? 0 : card.intValue());
        report.setRefrozen(refrozen);
        report.setTookMs(System.currentTimeMillis() - started);
        report.setTruncated(submissions.size() >= properties.getRebuildMaxSubmissions());
        log.info("榜单重建完成：contestId={} fetched={} replayed={} changed={} participants={} refrozen={} took={}ms",
                contestId, report.getFetched(), replayed, changed, report.getParticipants(), refrozen,
                report.getTookMs());
        return report;
    }

    // ==================================================================
    // 五、主题与版本（REST 与 WS 共用同一套版本号，客户端才能对齐）
    // ==================================================================

    public String topic(Long contestId, boolean fullView) {
        return "contest:" + contestId + (fullView ? VIEW_FULL : VIEW_PUBLIC);
    }

    public long currentVersion(String topic) {
        return sequencer.current(topic);
    }

    public long nextVersion(String topic) {
        return sequencer.next(topic);
    }

    // ==================================================================
    // 六、工具
    // ==================================================================

    public String liveKey(Long contestId) {
        return JudgeRedisKeys.CONTEST_RANK_PREFIX + contestId;
    }

    public String frozenKey(Long contestId) {
        return JudgeRedisKeys.CONTEST_RANK_FROZEN_PREFIX + contestId;
    }

    public String userStatusKey(Long contestId, Long userId) {
        return JudgeRedisKeys.CONTEST_USER_STATUS_PREFIX + contestId + ":" + userId;
    }

    /** 封榜时刻的逐题状态副本；封榜期间对外渲染只能读它（否则明细会泄漏实时进展） */
    public String frozenUserStatusKey(Long contestId, Long userId) {
        return JudgeRedisKeys.CONTEST_USER_STATUS_FROZEN_PREFIX + contestId + ":" + userId;
    }

    /** 该榜单键是否为冻结榜 —— 用它决定「读实时状态还是读冻结副本」，避免额外传参漏改 */
    private boolean isFrozenView(Long contestId, String rankKey) {
        return frozenKey(contestId).equals(rankKey);
    }

    public List<ContestProblem> problems(Long contestId) {
        return contestProblemMapper.selectList(new LambdaQueryWrapper<ContestProblem>()
                .eq(ContestProblem::getContestId, contestId)
                .orderByAsc(ContestProblem::getDisplayOrder)
                .orderByAsc(ContestProblem::getId));
    }

    private ContestProblem problemOf(Long contestId, Long problemId) {
        return contestProblemMapper.selectOne(new LambdaQueryWrapper<ContestProblem>()
                .eq(ContestProblem::getContestId, contestId)
                .eq(ContestProblem::getProblemId, problemId)
                .last("LIMIT 1"));
    }

    private String labelOf(ContestProblem p) {
        return p.getLabel() == null ? String.valueOf(p.getProblemId()) : p.getLabel();
    }

    /** 榜单数据保留期：竞赛剩余时长 + 结束后 N 小时（封榜/终榜已落库，Redis 只是热数据） */
    private long ttlSeconds(Contest contest) {
        long endSec = epochSeconds(contest.getEndTime());
        long nowSec = epochSeconds(LocalDateTime.now());
        long remain = Math.max(0, endSec - nowSec);
        return remain + properties.getRankTtlAfterEndHours() * 3600L;
    }

    private long resolveSubmitSeconds(SubmissionResultMessage msg, Contest contest) {
        if (msg.getSubmitTimeEpochMs() != null) {
            return msg.getSubmitTimeEpochMs() / 1000L;
        }
        log.warn("判题结果缺少提交时间（老版本消息），按当前时间计入罚时：submissionId={} contestId={}",
                msg.getSubmissionId(), contest.getId());
        return epochSeconds(LocalDateTime.now());
    }

    private long epochSeconds(LocalDateTime time) {
        return time == null ? 0L : time.atZone(ZoneId.systemDefault()).toEpochSecond();
    }

    /**
     * 批量取用户状态（pipeline，N 次往返压到 1 次）。
     *
     * @param frozen true=读封榜时刻的副本（对外冻结视图），false=读实时状态（内部视图与解封后）
     */
    @SuppressWarnings("unchecked")
    private Map<Long, Map<String, String>> loadStates(Long contestId, List<Long> userIds, boolean frozen) {
        Map<Long, Map<String, String>> out = new HashMap<>(userIds.size());
        if (userIds.isEmpty()) {
            return out;
        }
        List<Object> raw = redis.executePipelined((RedisCallback<Object>) connection -> {
            for (Long uid : userIds) {
                String key = frozen ? frozenUserStatusKey(contestId, uid) : userStatusKey(contestId, uid);
                connection.hashCommands().hGetAll(key.getBytes(StandardCharsets.UTF_8));
            }
            return null;
        });
        for (int i = 0; i < userIds.size() && i < raw.size(); i++) {
            Object item = raw.get(i);
            if (!(item instanceof Map<?, ?> map) || map.isEmpty()) {
                continue;
            }
            Map<String, String> decoded = new HashMap<>(map.size());
            for (Map.Entry<?, ?> e : ((Map<Object, Object>) map).entrySet()) {
                decoded.put(utf8(e.getKey()), utf8(e.getValue()));
            }
            out.put(userIds.get(i), decoded);
        }
        return out;
    }

    /** 批量解析用户名（内部 Feign + 本地 TTL 缓存）；失败时回落为 id，绝不因用户服务故障让榜单不可用 */
    private Map<Long, String> resolveUserNames(List<Long> userIds) {
        Map<Long, String> out = new HashMap<>(userIds.size());
        long now = System.currentTimeMillis();
        List<Long> missing = new ArrayList<>();
        for (Long uid : userIds) {
            Object[] cached = nameCache.get(uid);
            if (cached != null && (long) cached[1] > now) {
                out.put(uid, (String) cached[0]);
            } else {
                missing.add(uid);
            }
        }
        if (missing.isEmpty()) {
            return out;
        }
        try {
            List<UserDTO> users = userClient.queryUserByIds(missing);
            if (users != null) {
                for (UserDTO u : users) {
                    if (u.getId() == null) {
                        continue;
                    }
                    String name = u.getName() == null || u.getName().isBlank()
                            ? String.valueOf(u.getId()) : u.getName();
                    out.put(u.getId(), name);
                    nameCache.put(u.getId(), new Object[]{name, now + NAME_CACHE_TTL_SECONDS * 1000});
                }
            }
        } catch (Exception e) {
            log.warn("批量查询用户名失败，榜单回落展示 userId：{}", e.getMessage());
        }
        for (Long uid : missing) {
            out.putIfAbsent(uid, String.valueOf(uid));
        }
        return out;
    }

    private String utf8(Object o) {
        if (o instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return o == null ? null : String.valueOf(o);
    }

    private Long parseLong(String s) {
        try {
            return Long.valueOf(s);
        } catch (Exception e) {
            return 0L;
        }
    }

    private int safeInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private long safeLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return -1L;
        }
    }

    private long toLong(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (Exception e) {
            return 0L;
        }
    }
}
