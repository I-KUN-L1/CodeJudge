--[[
  竞赛排行原子更新（Redis Lua）。
  ============================================================================
  为什么必须用 Lua 而不是「Java 侧读-算-写」：
    同一用户可以并发提交多道题（多 worker 同时出结果），若罚时/通过数是
    「先 GET 状态 → 在 Java 里累加 → 再 ZADD」，两个并发事件会各自基于同一份旧状态
    计算出相同结果，出现**丢更新**：两道题都 AC 了，榜上却只加了一道题的通过数。
    Lua 在 Redis 单线程内执行，把「读状态 → 改状态 → 重算 → 写榜单」变成一次原子操作，
    无需引入分布式锁（这也是 judge-common 可以不要 Redisson 的又一依据）。

  ============================================================================
  排序编码：把需求的三个关键字挤进一个 ZSet 分值，一次 ZREVRANGE 即得最终名次
  ============================================================================
    score = 权重 × 10^12 + (999999 − 罚时秒) × 10^6 + (999999 − 末次通过偏移秒)

    关键字① 权重         —— ACM=通过题数 / IOI=总分，**多者在前**
    关键字② 罚时秒       —— **少者在前**
    关键字③ 末次通过偏移 —— **早者在前**（同权重同罚时的最终裁决）
                            IOI 下它与关键字②同源：都等于「最后一次得分的时刻偏移」，
                            因此等价于「同分时先到达该分者优先」。

  为什么把三个关键字编码进**一个**分值，而不是「ZSet 存权重、应用侧再排序」：
    · 名次是分布式共识，不是本地视图。若名次由各消费端自行二次排序，不同语言/版本的前端
      会因排序稳定性差异给出**不同名次**，而榜单的权威性正建立在「所有人看到同一份名次」上；
    · 编码常量（见 ContestRankService.WEIGHT_FACTOR 等）是跨语言可解释的，
      运维脚本、前端、第三方消费方都能用同一把尺子读榜。

  ⚠️ 精度边界（改移位量前必读）：
    ZSet 分值虽是 64 位，但 Redis 与 Lua 都以 IEEE-754 double 传递，只有 ≤ 2^53
    （约 9.007×10^15）的整数能被精确表示。本编码上限 ≈ (权重+1) × 10^12，
    因此 **权重必须 ≤ 8999**（ACM=通过题数、IOI=总分，两者都远低于此）。
    若未来放开「单题满分 × 题目数」的上限，必须同步调整这里的移位量。

  KEYS[1] = judge:contest:rank:{contestId}          实时榜 ZSet
  KEYS[2] = judge:contest:user:{contestId}:{userId} 该用户的题目状态 Hash
            field=problemId，value 两种形态：
              ACM：'ac:<通过时刻sec>:<该题此前错误次数>' | 'w:<错误次数>'
              IOI：'s:<该题最高分>:<取得该分的时刻sec>'
  ARGV[1] = userId
  ARGV[2] = problemId
  ARGV[3] = rule              ACM / IOI
  ARGV[4] = accepted          1=该提交 AC
  ARGV[5] = score             提交得分（AC 用例分值之和，IOI 用）
  ARGV[6] = fullScore         该题满分（IOI 封顶用）
  ARGV[7] = penaltyPerWrong   每次错误提交的罚时秒数（penaltyMinutes × 60）
  ARGV[8] = startSec          竞赛开始时间（epoch 秒）
  ARGV[9] = submitSec         本次提交的时间（epoch 秒）
  ARGV[10] = statusTtlSec     状态 Hash + 榜单 ZSet 的 TTL（秒）

  返回 { changed, solved, penalty, weight }
    changed 0/1 —— 榜单分值是否发生变化（0 表示本次提交不影响名次，调用方不必推送）
    solved      ACM=通过题数；IOI 无意义（为 0）
    penalty     罚时秒数（ACM）/ 末次得分偏移（IOI）
    weight      ACM=通过题数；IOI=总分
--]]

-- 编码常量：必须与 ContestRankService.WEIGHT_FACTOR / PENALTY_FACTOR / SEGMENT_BASE 一致
local RANK_SHIFT = 1000000000000  -- 10^12：权重段移位
local MID_SHIFT  = 1000000        -- 10^6 ：罚时段移位
local SEG_BASE   = 999999         -- 每段基数：段内「越优越大」，取反后保证小值胜出

local rankKey   = KEYS[1]
local statusKey = KEYS[2]
local userId    = ARGV[1]
local problemId = ARGV[2]
local rule      = ARGV[3]
local accepted  = (ARGV[4] == '1')
local score     = tonumber(ARGV[5]) or 0
local fullScore = tonumber(ARGV[6]) or 0
local penaltyPerWrong = tonumber(ARGV[7]) or 0
local startSec  = tonumber(ARGV[8]) or 0
local submitSec = tonumber(ARGV[9]) or 0
local ttlSec    = tonumber(ARGV[10]) or 86400

-- 罚时/时间差保护：提交时间早于竞赛开始（时钟漂移或补判）时按 0 计
local elapsed = submitSec - startSec
if elapsed < 0 then elapsed = 0 end

local cur = redis.call('HGET', statusKey, problemId)
local solved = 0
local penalty = 0
local weight = 0
local tie = 0

if rule == 'IOI' then
    ---------------------------------------------------------------
    -- IOI：按分。取该题历史最高分（部分得分有效），满分封顶。
    ---------------------------------------------------------------
    local best, bestAt = 0, submitSec
    if cur then
        local b, t = string.match(cur, '^s:(%d+):(%d+)$')
        if b then best = tonumber(b); bestAt = tonumber(t) end
    end
    local gained = 0
    if accepted then
        gained = score
        -- 满分封顶：用例分值之和理论上等于满分，配置漂移时不至于把权重算爆
        if fullScore > 0 and gained > fullScore then gained = fullScore end
    end
    if gained > best then
        best = gained
        bestAt = submitSec
        redis.call('HSET', statusKey, problemId,
                's:'..string.format('%d', best)..':'..string.format('%d', bestAt))
    end

    -- 重算总分与该用户「最后一次得分时刻」
    local all = redis.call('HGETALL', statusKey)
    local lastAt = 0
    for i = 1, #all, 2 do
        local b, t = string.match(all[i + 1], '^s:(%d+):(%d+)$')
        if b then
            weight = weight + tonumber(b)
            if tonumber(t) > lastAt then lastAt = tonumber(t) end
        end
    end
    tie = lastAt - startSec
    penalty = tie
else
    ---------------------------------------------------------------
    -- ACM：只有「首次通过」才计入，且该题此前的错误提交全部转为罚时。
    -- 未通过的题不计罚时（ICPC 规则）——这也是为什么要重算而不是累加：
    -- 重算不会被「先错后对」的乱序到达写坏。
    ---------------------------------------------------------------
    local wrong, acAt = 0, 0
    if cur then
        local w = string.match(cur, '^w:(%d+)$')
        if w then
            wrong = tonumber(w)
        else
            local a, ww = string.match(cur, '^ac:(%d+):(%d+)$')
            if a then acAt = tonumber(a); wrong = tonumber(ww) end
        end
    end

    if acAt > 0 then
        -- 已通过：重复 AC 不改变任何状态（幂等，重投消息安全）—— 空分支即预期行为
    elseif accepted then
        acAt = submitSec
        redis.call('HSET', statusKey, problemId,
                'ac:'..string.format('%d', acAt)..':'..string.format('%d', wrong))
    else
        wrong = wrong + 1
        redis.call('HSET', statusKey, problemId, 'w:'..string.format('%d', wrong))
    end

    local all = redis.call('HGETALL', statusKey)
    local lastAc = 0
    for i = 1, #all, 2 do
        local a, ww = string.match(all[i + 1], '^ac:(%d+):(%d+)$')
        if a then
            solved = solved + 1
            local at = tonumber(a)
            if at > lastAc then lastAc = at end
            local off = at - startSec
            if off < 0 then off = 0 end
            penalty = penalty + tonumber(ww) * penaltyPerWrong + off
        end
    end
    weight = solved
    tie = lastAc - startSec
end

-- 三段全部夹到段内上限，避免串位破坏排序（罚时/赛时超过 999999 秒 ≈ 11.5 天时封顶）
if penalty > SEG_BASE then penalty = SEG_BASE end
if tie < 0 then tie = 0 end
if tie > SEG_BASE then tie = SEG_BASE end

-- 写回：拿「当前真实排名值」与 ZSet 现值比对，不同才写。
-- 用「比对现值」而不是「靠事件标志位判断」的原因：
--   ① 只交过错误解、或参赛后尚未通过的选手也必须进榜（0 分 + '-1' 记法是 ICPC 榜面的正常形态），
--      否则榜单上根本看不到「参与了但没解出」的人，参赛人数也会少算；
--   ② 重复 AC / 重投消息天然幂等 —— 值没变就不写、不推进版本号，推送侧随之静默。
local encoded = weight * RANK_SHIFT + (SEG_BASE - penalty) * MID_SHIFT + (SEG_BASE - tie)
local currentScore = redis.call('ZSCORE', rankKey, userId)
local changed = 0
if currentScore == false or tonumber(currentScore) ~= encoded then
    redis.call('ZADD', rankKey, string.format('%d', encoded), userId)
    changed = 1
end

-- TTL 对齐竞赛存活期（避免换库或长期不再被访问的竞赛把 Redis 撑满）
redis.call('EXPIRE', statusKey, ttlSec)
redis.call('EXPIRE', rankKey, ttlSec)

return { changed, solved, penalty, weight }
