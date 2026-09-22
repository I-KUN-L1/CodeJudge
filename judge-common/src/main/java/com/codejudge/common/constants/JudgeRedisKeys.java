package com.codejudge.common.constants;

/**
 * 判题域 Redis Key 全局约定（judge-submission / judge-worker 共用）。
 *
 * <p>与 PLAN §4.2 对齐；禁止在业务代码中散落硬编码 key。
 */
public interface JudgeRedisKeys {

    /**
     * 提交幂等快路径 key 前缀：{@code judge:submission:idempotent:{userId}:{problemId}:{contestId}:{codeHash}}
     * （String，TTL 60s；DB 唯一索引 uk_submission_idempotent 是最终防线）
     */
    String SUBMISSION_IDEMPOTENT_PREFIX = "judge:submission:idempotent:";

    /**
     * 待判队列 ZSet：member=submissionId，score=入队时间戳（队列积压指标 / 排队时长分析）
     */
    String JUDGE_QUEUE_ZSET = "judge:judge:queue:zset";

    /**
     * 判题任务级幂等锁前缀：{@code judge:task:lock:{taskId}:{attempt}}
     * （String SETNX，TTL=任务超时+30s；DB 租约 CAS 是权威，本锁拦截明显重复的投递）
     */
    String TASK_LOCK_PREFIX = "judge:task:lock:";

    /**
     * worker 心跳 key 前缀：{@code judge:worker:heartbeat:{workerId}}（String，TTL 30s，过期即离线）
     */
    String WORKER_HEARTBEAT_PREFIX = "judge:worker:heartbeat:";

    /** worker 负载 ZSet：member=workerId，score=在跑任务数 */
    String WORKER_LOAD_ZSET = "judge:worker:load";

    /**
     * 业务死信已投递标记前缀：{@code judge:task:dlq:{taskId}}
     * （String SETNX，防止多个失败路径对同一任务重复投递 DLQ）
     */
    String TASK_DLQ_SENT_PREFIX = "judge:task:dlq:";

    /**
     * 补偿扫描防抖标记前缀：{@code judge:task:rescue:{taskId}}
     * （String SETNX，TTL 30s，避免滞留任务在扫描周期内被反复重发）
     */
    String TASK_RESCUE_PREFIX = "judge:task:rescue:";

    /**
     * 提交频控 key 前缀：{@code judge:submission:rate:{userId}}（String INCR，TTL 60s）
     */
    String SUBMISSION_RATE_PREFIX = "judge:submission:rate:";

    // ==================== P4：竞赛 / 排行榜 / WebSocket ====================

    /**
     * 竞赛实时榜 ZSet（**永远更新**，封榜也不停写）：
     * {@code judge:contest:rank:{contestId}}，member=userId，score 三段编码见
     * {@code ContestRankService}。
     *
     * <p>关键设计：封榜只影响「读哪个榜」，不影响「写哪个榜」—— 实时榜持续写入，
     * 才能保证解封后立即得到正确的完整榜（无需回放日志或跨表合并）。
     */
    String CONTEST_RANK_PREFIX = "judge:contest:rank:";

    /**
     * 封榜冻结榜 ZSet：{@code judge:contest:rank:frozen:{contestId}}。
     *
     * <p>由封榜时刻的 {@code ZUNIONSTORE}(1, live) 一次性原子生成（不是逐条拷贝），
     * 因此不存在「快照拍到一半」的中间态。封榜期间对外只读它。
     */
    String CONTEST_RANK_FROZEN_PREFIX = "judge:contest:rank:frozen:";

    /**
     * 竞赛内「用户 × 题目」状态 Hash：{@code judge:contest:user:{contestId}:{userId}}，
     * field=problemId，value 约定：
     * <ul>
     *   <li>ACM：{@code w:{错误次数}}（未通过） / {@code ac:{AC时间戳秒}:{错误次数}}（已通过）；</li>
     *   <li>IOI：{@code s:{最高分}:{首次取得该分的秒级时间戳}}（可只有分没有通过）。</li>
     * </ul>
     * 罚时/总分由该 Hash **重算**（而非增量累加），因此重判导致的结论回退也能自愈。
     */
    String CONTEST_USER_STATUS_PREFIX = "judge:contest:user:";

    /**
     * 封榜时刻「用户 × 题目」状态的只读副本：
     * {@code judge:contest:user:frozen:{contestId}:{userId}}，字段与
     * {@link #CONTEST_USER_STATUS_PREFIX} 完全一致。
     *
     * <p><b>为什么必须单独冻结逐题状态</b>：榜单行里的 {@code problemStatus}（ICPC 的
     * {@code +1} / {@code -3} 记法）不是从 ZSet 来的，而是读实时状态 Hash 渲染的。
     * 若只冻结 ZSet，封榜期间有人通过题目时，公开榜的<b>名次不变、但明细单元格会从
     * {@code -1} 变成 {@code +}</b> —— 等于把「封榜后谁过了题」直接印在公开榜上。
     * 名次冻结了、明细却泄漏，是比完全不封榜更糟的形态（看起来像没封）。
     *
     * <p>副本在封榜时与冻结榜同一批生成，TTL 与竞赛存活期对齐，旧副本在重复封榜/重算时清理。
     */
    String CONTEST_USER_STATUS_FROZEN_PREFIX = "judge:contest:user:frozen:";

    /**
     * 封榜幂等锁（SETNX，无 TTL 或长 TTL）：{@code judge:contest:lock:freeze:{contestId}}。
     * 多实例同时扫描到 freeze_at 时，保证只有一次快照动作生效。
     */
    String CONTEST_FREEZE_LOCK_PREFIX = "judge:contest:lock:freeze:";

    /**
     * 竞赛榜单推送的「脏标记」：{@code judge:contest:push:dirty:{contestId}}
     * （String，TTL 略大于推送间隔）。仅用于观测与排障，合并逻辑在进程内完成。
     */
    String CONTEST_PUSH_DIRTY_PREFIX = "judge:contest:push:dirty:";

    /**
     * WebSocket 会话索引：{@code judge:ws:session:{instanceId}:{userId}} → Set&lt;sessionId&gt;。
     *
     * <p>⚠️ 相对 PLAN §4.2 的 {@code judge:ws:session:{userId}} 多了一层 instanceId：
     * 会话是进程内资源，不含实例标识时两个服务（甚至同一服务的两个实例）会写进同一个 Set，
     * 互相覆盖。本 key 只服务于运维观测，推送正确性依赖 Redis 广播通道。
     */
    String WS_SESSION_PREFIX = "judge:ws:session:";
}
