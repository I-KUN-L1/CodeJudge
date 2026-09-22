# P4 交付报告 —— WebSocket 推送 + 竞赛模块（封榜）+ Redis ZSet 排行榜

> 交付日期：2026-09-20 ｜ 阶段：P4 ｜ 状态：✅ **已完成并验收通过**
> 验收脚本：`python scripts/verify-p4.py` → **60 项断言 PASS / 0 FAIL / 0 SKIP**（耗时约 6 分钟）

---

## 一、交付内容概览

| 需求项 | 落地载体 | 状态 |
|---|---|---|
| ① WebSocket 推送（实时通信通道） | `judge-common` 的 `com.codejudge.common.ws` 推送基座（9 类）+ `judge-submission` 判题进度端点 + `judge-contest` 榜单端点 + 网关 `/ws/**` 拆分路由 | ✅ |
| ② 竞赛模块（生命周期 + 封榜） | `judge-contest`(9086)：建赛/列表/详情/报名、按时间推导状态 + 定时落库、封榜冻结、快照留档、终榜重建 | ✅ |
| ③ Redis ZSet 排行榜 + 同分规则 + 变更推送 | 单 ZSet 三段编码（权重 → 罚时 → 末次通过时刻）+ Lua 原子更新 + 合并窗口推送 | ✅ |

**验收命令**

```bash
# 1. 全量构建（10 模块）
/d/1/apache-maven-3.9.6/bin/mvn.cmd clean install -DskipTests      # BUILD SUCCESS, 1m05s

# 2. 启动 7 个服务（看护模式，需 run_in_background 挂起）
C:/Users/20670/.workbuddy/binaries/python/versions/3.13.12/python.exe scripts/dev-start-backend.py --wait

# 3. 验收（需约 6 分钟：F 段要等短赛程竞赛自然结束）
C:/Users/20670/.workbuddy/binaries/python/envs/default/Scripts/python.exe scripts/verify-p4.py
```

---

## 二、验收实证（60 项断言，逐组实测值）

### A. 竞赛创建与生命周期（6/6）
| 断言 | 实测 |
|---|---|
| 学员建赛被拒 | `403 无权限访问该资源` |
| 教师建赛 + 状态按时间推导 | `contestId=2101640007486836737 status=1`（进行中） |
| 详情含题号编排 | `[["A",4001,100], ["B",4002,100]]` |
| 编排不存在的题目被拒 | `题目不存在：999999` |
| 编排未发布（草稿 4006）被拒 | `题目未发布，不能编排进竞赛：4006（当前状态 0）` |
| 状态分页按**时间区间**翻译 | `status=1` 含新建赛与 5002；`status=2` 含 5001 |

### B. 报名与竞赛提交校验（6/6）
报名幂等 200；未报名提交 → `请先报名参加竞赛后再提交`；非本赛题目 → `题目 4003 不属于竞赛 …`；已结束竞赛 → `竞赛已结束，无法提交`。

### E. WebSocket（12/12）
| 断言 | 实测 |
|---|---|
| 无 token 订阅 | 握手即 401（网关拦截） |
| 本人订阅判题进度 | `CONNECTED → SNAPSHOT → SUB_PROGRESS×5 → SUB_RESULT` |
| 进度推送 | **5 帧 SUB_PROGRESS**（worker 生产端 800ms 限流后的净帧数） |
| 越权订阅他人提交 | 收到 `ERROR` 后断开 |
| 学员请求 `full=true` | 收到 `ERROR`（fail-closed，附原因），**不是**静默降级 |
| 榜单订阅 | `CONNECTED → SNAPSHOT` |
| 心跳 | `PING → PONG` |
| 榜单变更推送 | 收到 `RANK_UPDATE`，版本号 `4 → 5` 单调递增 |

### C. Redis ZSet 排行榜与同分规则（13/13）
| 断言 | 实测 |
|---|---|
| 首次 AC 前不计分 | 学员一 `weight=0`、A 题 `-1` |
| 同分罚时少者优先 | 学员一 `rank=2 罚时=3057s`；学员二 `rank=1 罚时=1889s` |
| 罚时构成 | `Δpenalty = 1168s = 1200（1 次错误×20min） + ΔAC时刻(−32s)` |
| ICPC 记法 | 学员一 `A='+1'`、学员二 `A='+'` |
| **ZSet 原始编码** | `judge:contest:rank:2101640007486836737` member=`2002` score=`1998110998110`（= `1×10^12 + (999999−1889)×10^6 + (999999−80110)`） |
| 从未通过者进榜 | 学员三 `weight=0`、`A='-1'`（**修复项**，见 §五-4） |
| 通过题数优先于罚时 | 学员一 `weight=2 rank=1`；学员二 `weight=1 rank=2` |

### D. 封榜与视图隔离（10/10）
| 断言 | 实测 |
|---|---|
| 手动封榜 | 返回 `true` |
| 公众视图 | `frozen=true, inFreezeWindow=true, fullView=false` |
| 封榜期间成绩不进公开榜 | 冻结前后条目标题完全一致（内容签名相等） |
| **公开榜逐题明细不泄漏** | 学员三仍为 `{'A': '-1'}`，**未**变成 `+`（修复项，见 §五-3） |
| 教师 `full=true` | `fullView=true, frozen=false`，可见学员三已通过 |
| 学员 `full=true` | `403 无权查看全量榜单…` |
| 快照留档 | `contest_rank_snapshot` 中 `type=FROZEN`（rank_json 517 字节） |
| 重复封榜幂等 | 第二次返回 `false` |

### G. 终榜重建（3/3）
`fetched=7 replayed=7 changed=7 participants=3 took=51ms`；重建前/后榜单内容签名相等（回放幂等）；学员触发重建 `403`。

### F. 自动封榜 → 结束 → 解封（9/9）
创建即处于封榜窗口的短赛程竞赛 → **生命周期扫描自动封榜**（FROZEN 快照生成，无人工干预）→ 封榜期间提交并 AC → 公开榜 0 行、内部榜 1 行 → 到点自动 `status=2` → 公开榜自动解封且**包含封榜期间的成绩** → 留档 `[FROZEN, FINAL]`。

> 这一段是封榜一致性策略的核心证据：**「解封无需合并」不是设计口号而是可复现事实** ——
> 封榜期间的成绩从未离开实时榜，解封只是把读取目标从冻结榜切回实时榜。

---

## 三、关键设计决策

### 3.1 WebSocket 推送基座（judge-common）

```
业务服务 ──publish──> Redis Pub/Sub 通道 (judge:ws:broadcast)
                          │
        ┌─────────────────┼─────────────────┐
        ▼                 ▼                 ▼
   实例 A 订阅        实例 B 订阅        实例 C 订阅
   ├ 按 topic 索引的会话表（ConcurrentWebSocketSessionDecorator 做背压）
   ├ 填充 seq（WsSequencer，per-topic 单调）→ 客户端据此判丢包
   └ 推给本实例该 topic 的订阅者
```

- **为什么经 Redis 广播而不是各实例直推**：会话是**进程内**资源。多实例下 A 实例产生的榜单变更，
  B 实例的订阅者收不到。经 Redis Pub/Sub 中转是「一次发布、全实例可达」的最小实现；
  发布端只经 subscribe 回调投递，避免本实例重复收到。
- **统一信封** `WsEnvelope{type, topic, seq, full, ts, data}`：`seq` 保序/判丢，`full` 标记内部全量视图。
- **握手鉴权**：JWT 在网关校验，身份经 `user-info`/`role-info` 头透传，由 `WsIdentityInterceptor`
  落到会话属性；**没有身份头 = 绕过了网关，握手阶段即拒**。
- **浏览器限制的现实妥协**：WebSocket API 无法自定义握手请求头，因此**仅对 `/ws/**`**
  放宽为 `?token=` 查询参数。已知风险（URL 会进访问日志/浏览器历史），缓解手段与
  「一次性 WS 票据」的后续方案写在 `AuthGlobalFilter.resolveToken` 注释里。

### 3.2 排行榜三段编码（**相对 PLAN v1.0 的修正**）

```text
score = 权重 × 10^12 + (999999 − 罚时秒) × 10^6 + (999999 − 末次通过偏移秒)
```

PLAN v1.0 写的是 `passed_count × 10^7 + (10^7 − 1 − penaltySeconds)`，并声称它实现
「通过题数优先、罚时其次、**同分按最后 AC 时间**」三段排序。**该公式只有两段**：
权重与罚时都相同时分值完全相同，ZSet 会退化为按 member（userId 字符串）排序 ——
第三关键字其实做不到。本次按需求把第三个关键字真正编码进去，移位量随之调整。

⚠️ **精度边界**：分值经 IEEE-754 double 传递，仅 ≤ 2^53 的整数可精确表示，
故 **权重须 ≤ 8999**。常量 `WEIGHT_FACTOR / PENALTY_FACTOR / SEGMENT_BASE`
与 `lua/contest_rank_update.lua` 内的 `RANK_SHIFT / MID_SHIFT / SEG_BASE`
**必须成对修改**（已在这两处互相注明）。

**为什么名次必须由编码决定**：名次是分布式共识。若取回列表后在应用侧二次排序，
不同语言/版本的前端会因排序稳定性差异给出不同名次，而榜单的权威性正建立在
「所有人看到同一份名次」上。

### 3.3 封榜三段式一致性

1. 实时榜 `judge:contest:rank:{cid}` **永远更新**（封榜只影响「读哪个榜」，不影响「写哪个榜」）；
2. 封榜瞬间 `ZUNIONSTORE frozen 1 live` **单命令原子**生成冻结榜；
3. **逐题状态副本** `judge:contest:user:frozen:{cid}:{uid}` 与冻结榜同批生成（见 §五-3）；
4. 解封**无需任何合并**，读回实时榜即完整结果；
5. 冻结榜与终榜各落库一条快照，使「当时的事实」不依赖 Redis 存活。

### 3.4 推送频率控制（三道闸门）

| 闸门 | 位置 | 机制 |
|---|---|---|
| ① 生产端限流 | judge-worker `ProgressPublisher` | 距上次推送 < 800ms 的中间态直接丢弃；**末用例/短路帧强制推送**（否则客户端停在 90% 等不到 100%）；按 submissionId 分桶避免并发任务互相压制 |
| ② 窗口合并 | judge-contest `ContestRankPusher` | 以「首次变更时刻 + 固定窗口（1s）」为准合并；**不用 debounce** —— 高负载下变更持续到达会把 debounce 无限推迟直至饿死 |
| ③ 内容去重 + 视图隔离 | `ContestRankPusher.pushView` | 渲染结果签名与上次相同则静默（不推进版本号）；public / full 是两个独立主题，封榜期间 public 天然无变化 → 自动静默 |

### 3.5 模块职责边界

| 类 | 职责 | 不做什么 |
|---|---|---|
| `ContestService` | 竞赛定义与报名（关系型数据） | 不碰 Redis 榜单 |
| `ContestRankService` | 榜单的**唯一写者**（Redis ZSet + Lua） | 不发 WS 消息 |
| `ContestRankPusher` | 把「榜单变了」转成受控频率的广播 | 不算分数 |
| `ContestLifecycleService` | 时间推导 → 落库 + 触发副作用（快照/推送） | 不判状态（状态是时间的函数） |

---

## 四、文件清单

### 新增：`judge-contest`（9086，30 个文件）
```
pom.xml / ContestApplication.java（@EnableScheduling + @EnableFeignClients）
resources/application.yml                      端口 9086、cj.ws.enabled=true、cj.contest.*
resources/lua/contest_rank_update.lua          排行原子更新脚本
config/    ContestProperties / ContestRedisConfig / ContestWsConfig
domain/po/ Contest / ContestProblem / ContestRegistration / ContestRankSnapshot
domain/dto/ ContestFormDTO / ContestQuery
domain/vo/  ContestVO / ContestDetailVO / ContestRankVO / RankEntryVO / RebuildReportVO
mapper/    ContestMapper / ContestProblemMapper / ContestRegistrationMapper / ContestRankSnapshotMapper
service/   ContestService / ContestRankService / ContestRankPusher / ContestLifecycleService
mq/        ContestResultHandler（消费 RESULT 事件）
ws/        ContestRankWsHandler（/ws/contests/{id}/rank）
controller/ ContestController / ContestRankController / InternalContestController
```

### 新增：WebSocket 推送基座 `judge-common/common/ws`（9 类）
`WsMessageType` `WsEnvelope` `WsSessionRegistry` `RedisPushChannel` `WsProperties`
`WsPushAutoConfiguration` `WsIdentityInterceptor` `WsHeartbeat` `WsSequencer`

### 新增：`judge-api` 契约
`client/contest/ContestClient`、`client/submission/SubmissionClient`、
`dto/contest/ContestContextDTO`、`dto/contest/ContestSubmissionDTO`、`dto/problem/ProblemSummaryDTO`、
`dto/submission/SubmissionProgressMessage`（**不含用例输入/输出摘要，防泄漏**）

### 新增：`judge-submission` 侧
`ws/SubmissionProgressWsHandler`（`/ws/submissions/{id}`，归属校验）、
`service/SubmissionProgressPushService`、`mq/ProgressEventHandler`、
`controller/InternalSubmissionController`、`config/SubmissionWsConfig`

### 新增：其他
`scripts/verify-p4.py`（60 断言）、`docs/P4-REPORT.md`（本文件）

### 修改（影响既有阶段代码，**属行为变更**）
| 文件 | 变更 |
|---|---|
| `judge-common/mq/MqHandler` | 新增 `default subscribeTags()`（默认空 = `*`，既有实现行为不变） |
| `judge-common/mq/RocketMQConsumerContainer` | 按「主题 → Tag 并集」订阅；未声明 Tag 退化为 `*` |
| `judge-common/constants/JudgeRedisKeys` | 新增 4 个竞赛键前缀 + 修正 `WS_SESSION_PREFIX`（补 instanceId 层） |
| `judge-common/mq/MqTopics` | 新增 Tag `PROGRESS`（原 `CREATED/RETRY/RESULT` 保留） |
| `judge-common/pom.xml` + `AutoConfiguration.imports` | 新增 optional 依赖 websocket/data-redis；注册 `WsPushAutoConfiguration` |
| `judge-worker/engine/JudgeEngine` + `WorkerProperties` + yml | 注入 `ProgressPublisher`；RESULT 事件携带 `submitTimeEpochMs`；进度限流参数 |
| `judge-submission/service/SubmissionService` | 注入 `ContestClient`；新增 `validateContest` / `listContestResults` |
| `judge-submission/mq/ResultEventHandler` | 补齐 `subscribeTags` |
| `judge-gateway/resources/application.yml` | 新增 `/ws/submissions/**`、`/ws/contests/**` 两条长连接路由（`response-timeout: 900000`，更具体的 contests 在前） |
| `judge-gateway/filter/AuthGlobalFilter` | `resolveToken` 对 `/ws/**` 放宽支持 `?token=` |
| `judge-problem/controller/InternalProblemController` | 新增 `/summaries` 端点（`InternalOnlyGuard` 保护） |
| 根 `pom.xml` | 放开 judge-contest 模块 |
| `scripts/dev-start-backend.py` | SERVICES 增加 judge-contest:9086（置于 submission 之后） |
| `.env.example` | 新增 `SVC_SUBMISSION_URI`、`CJ_CONTEST_*`、`CJ_WS_*`、`CJ_JUDGE_PROGRESS_*` |
| `sql/seed.sql` | 2 场竞赛（5001 已结束含终榜、5002 进行中未封榜）+ 6 条 contest_problem + 8 条 contest_ranking 报名 |

---

## 五、本轮修复的缺陷（8 项）

按性质分三类。**前三项是阻塞级**：不修则 9084/9086 根本起不来或功能不可用。

### 【代码 · 阻塞】1. `judge-contest` 漏登记 `judge-problem` 静态实例
`SimpleDiscoveryClient` 只配了 `judge-submission` 与 `judge-user`。而**建赛必须经内部 Feign
校验「题目存在且已发布」**（否则会编排出一个「看起来正常、提交永远失败」的竞赛）。
症状：`题目服务不可用，暂时无法建赛` —— 看起来像 judge-problem 挂了，实际是本服务
根本没给它登记地址。**这是运行期才炸的配置缺失，启动期完全正常。**

> 连带教训已固化进验收脚本：负例断言必须校验**拒绝原因**，不能只断言「失败了」。
> 首轮 A4/A5 就是靠「失败即通过」的错误写法蒙混过关的。

### 【代码 · 阻塞】2. `*WsConfig` 与 `*WsHandler` 循环依赖
配置类用 `@RequiredArgsConstructor` 构造注入 handler，同时 handler 又由**同一配置类**的
`@Bean` 工厂方法产出 → 自引用循环，`submissionWsConfig` / `contestWsConfig` 均启动失败。
修法：handler 加 `@Component` 独立成 Bean，配置类只做端点绑定，并删除 `@Bean` 工厂方法
（已在两处配置类注释里写明「不要再加 @Bean 工厂方法」）。

### 【代码 · 封榜泄漏】3. 只冻结 ZSet，逐题明细仍读实时状态 → 公开榜泄漏封榜后进展
榜单行的 `problemStatus`（ICPC 的 `+1` / `-3` 记法）**不是从 ZSet 来的**，而是读
`judge:contest:user:{cid}:{uid}` 实时 Hash 渲染的。只冻结 ZSet 的后果：
封榜期间有人通过题目时，公开榜的**名次不变、但单元格从 `-1` 变成 `+`** ——
等于把「封榜后谁过了题」直接印在公开榜上。**名次冻结了、明细却泄漏，比完全不封榜更糟**
（看起来像没封，进而被误判为封榜功能失效）。

修法：新增 `judge:contest:user:frozen:{cid}:{uid}` 副本，封榜时与冻结榜同批生成；
渲染路径由榜单键自身推导视图（`isFrozenView`），冻结视图只读副本。
验收断言 `D4b` 专门锁死这一条。

### 【代码 · 需求缺口】4. 只交错误解、从未通过的选手不在榜上
Lua 原先只在「事件标志位翻转」时 `ZADD`，导致 0 分选手整行缺失 ——
榜单上看不到「参与了但没解出」的人，ICPC 记法 `-1/-3` 也永远展示不出来，参赛人数少算。
修法：写回逻辑改为**比对当前真实排名值与 ZSet 现值**，不同才写。
这同时天然保住了原有的幂等性（重复 AC / 重投消息值不变 → 不写、不推进版本、推送静默）。

### 【代码 · 需求缺口】5. 同分第三关键字缺失 + IOI 下该字段取错列
- 原编码只含权重与罚时，「其次提交时间早者优先」实际未实现（最终同分靠 userId 字符串排序）；
- `lastAcceptedOffsetOffset` 的取值助手**统一取 `split(":")[1]`**，而 ACM 是
  `ac:<时刻>:<错误数>`、IOI 是 `s:<最高分>:<取得时刻>` —— IOI 下它把**分数**当成了**时间戳**。
  该字段恰是排序第三关键字，取错会直接写坏名次。

修法：第三关键字编入 score；取值助手按前缀分派（`ac:` 取 [1]、`s:` 取 [2]、`w:` 无记录返回 null）。

### 【代码 · 运维噪音】6. MQ 消费组以 `*` 订阅，拉取并丢弃无关 Tag
`judge_submission` 上有 4 种 Tag，而各消费组只关心其中一部分。以 `*` 订阅会导致
无关消息被投递过来、在容器里判空后逐条打 WARN（实测首轮 54 条）。
修法：`MqHandler` 新增可选 `subscribeTags()`（`default` 方法，既有实现零改动），
容器按「主题 → Tag 并集」订阅，未声明则退化为 `*`（**收窄是优化，不能让漏声明变成收不到消息**）。

实测收窄结果：
```
judge-contest    → judge_submission[RESULT]
judge-worker     → judge_submission[CREATED,RETRY]
judge-submission → judge_submission[PROGRESS,RESULT]
```
重启后丢弃告警 **0 条**。

### 【测试脚本】7. `C7` 罚时断言写错
断言「罚时差 = 1200s（±15）」，实测 1168s。**代码是对的**：罚时 = `Σ(AC 前错误数 × 1200) + AC 时刻偏移`，
两人各通过同一题，差额 = `1200 − 两次 AC 的时间差(32s)`。已改为
用两人的 `lastAcceptedOffsetSeconds` 把 1200 **精确还原**出来。

### 【测试脚本】8. `E7` 的 WebSocket URL 拼接错误
写成 `f"{path}?token=..."`，而 path 已含 `?full=true` → 拼出 `…?full=true?token=xxx`，
第二个问号只是 `full` 值的一部分，**token 根本没传进去** → 表现为「网关 401 握手失败」，
看起来像鉴权有问题。已改为独立参数拼接并加注释说明。断言同时收紧为
「必须收到 ERROR 信封」——只断言「连不上」无法区分「无权」与「网络抖了一下」。

---

## 六、有意偏差与遗留

### 6.1 相对 PLAN v1.0 的有意偏差
1. **排行榜 score 编码由两段改三段**（`10^7` → `10^12/10^6/10^6`）。原因见 §3.2：
   原公式表达不了需求要求的第三个关键字。`docs/PLAN.md` §4.2 与
   `docs/PROMPT-ARCHIVE.md` §B7 已同步标注。
2. **`contest_rank` topic 未启用**（保留位）。judge-contest 直接以**独立消费组**消费
   `judge_submission` 的 `RESULT` 事件：同一份事件、不同消费组是标准发布订阅语义，
   多插一跳「submission 转投 contest_rank」只增加一个丢失点与一份重复报文，换不来任何保证。
3. **`judge:ws:session` 多一层 instanceId**。会话是进程内资源，不含实例标识时
   两个服务/实例会写进同一个 Set 互相覆盖。该 key 只服务运维观测，推送正确性依赖广播通道。
4. **`freezeMinutes` 是时长而非时刻**（封榜时刻 = 结束时间 − 封榜时长），
   避免「延长赛程却忘了改封榜时刻」的运营事故。

### 6.2 遗留待确认
1. **验收脚本会留下测试竞赛数据**：本次 3 轮验收共在 `judge_contest.contest` 留下
   4 场 `【P4验收】…` 竞赛（含各自报名/提交/快照/Redis 键）。如需清理：
   ```sql
   -- ⚠️ 破坏性操作，执行前请确认；Redis 侧键会随 TTL 自然过期
   DELETE FROM judge_contest.contest_rank_snapshot WHERE contest_id IN (SELECT id FROM judge_contest.contest WHERE title LIKE '【P4验收】%');
   DELETE FROM judge_contest.contest_registration  WHERE contest_id IN (SELECT id FROM judge_contest.contest WHERE title LIKE '【P4验收】%');
   DELETE FROM judge_contest.contest_problem       WHERE contest_id IN (SELECT id FROM judge_contest.contest WHERE title LIKE '【P4验收】%');
   DELETE FROM judge_contest.contest               WHERE title LIKE '【P4验收】%';
   ```
   （`judge_submission.submission` 中对应 `contest_id` 的提交行同理，未一并列出以免误删。）
2. **WS 凭证走 URL 查询参数**：仅限 `/ws/**`，已知风险与缓解见 §3.1。
   彻底消除需 P6 引入「一次性 WS 票据」，属独立需求。
3. **IOI 赛制未做端到端验收**：编码逻辑（取最高分、满分封顶、两次段同源）已实现并有单元级推理，
   但验收脚本只覆盖 ACM —— 沙箱侧缺少「部分得分」用例（现有 6 题均为全对/全错制）。
   若要 IOI 验收成立，需先构造带部分分值的题目。
4. **推送频率控制未做高并发压测**：三道闸门的机制验证成立（E3 实测 5 帧限流效果、
   E11 合并推送），但「一场竞赛最后十分钟每秒几十次 AC」的极端场景属 P6 压测范围。
5. **榜单 `totalParticipants` 语义**：现为「ZSet 成员数」，即**至少提交过一次**的参赛者
   （含 0 分）。报名但一次未提交者不计入 —— 与 ICPC 榜面惯例一致，但若产品要求
   「显示报名人数」，需另用 `contest_registration` 统计。

---

## 七、下一步

P4 已验收通过，按「上一阶段验收不过不进下一阶段」的约定，可进入 **P5：AI 代码点评 + RAG + SSE**
（`judge-ai` 9087，pgvector `vector(1024)` + HNSW，LLM 未配置时走 `enabled=false` 降级路径）。

> 冷启动入口：`docs/CONTEXT.md` §5。新会话直接说「读 docs/CONTEXT.md，然后继续 P5」。
