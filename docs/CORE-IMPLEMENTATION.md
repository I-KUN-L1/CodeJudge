# CodeJudge 核心功能实现原理

> **文档目的**：面向「想快速理解整个系统到底怎么运作」的读者（新加入的开发者、架构评审者）。
> 本文不讲需求与背景，只回答一个问题：**核心功能在代码里究竟是怎么实现的**。
> 每条关键结论都附 `文件路径:行号` 证据（路径均相对仓库根目录 `D:/1/CodeJudge`），
> 便于逐条核对；未在代码中找到证据的地方会明确标注「未找到」，不做推测。
>
> **最后核对时间**：2026-09-23（代码版本以当日工作区为准）。
>
> 配套阅读：[`ARCHITECTURE.md`](./ARCHITECTURE.md)（模块职责与选型）、[`CONTEXT.md`](./CONTEXT.md)（阶段约束与踩坑记录）、
> [`API-REFERENCE.md`](./API-REFERENCE.md)（接口清单）、[`DEPLOYMENT.md`](./DEPLOYMENT.md)（部署与配置）。

---

## 0. 系统全景与端到端数据流

### 0.1 服务全景

```
                 浏览器 / JMeter
                       │
                       ▼
            ┌─────────────────────┐
            │  judge-gateway :9080│  JWT 验签 → 注入 user-info / role-info
            │  AuthGlobalFilter   │  登录限流 / CORS / WS 握手
            └──────────┬──────────┘
   ┌──────────┬────────┼──────────┬───────────┬──────────┐
   ▼          ▼        ▼          ▼           ▼          ▼
 :9081      :9082    :9083      :9084       :9086      :9087
judge-auth judge-user judge-   judge-      judge-     judge-ai
 登录/令牌  用户/账号  problem  submission  contest    点评/RAG
                      题目/用例  提交/队列   竞赛/榜单   WebFlux
                                  │              ▲
                     RocketMQ     │              │ RESULT(独立消费组)
                judge_submission  ▼              │
                            ┌───────────┐        │
                            │judge-worker│───────┘
                            │   :9085   │  docker run 沙箱
                            └───────────┘
   基础设施：MySQL 3307 ｜ Redis 6380 ｜ PostgreSQL+pgvector 5433 ｜ RocketMQ 9877
```

服务数量与端口：8 个可运行服务 `9080`–`9087`（README.md:38-45，`scripts/dev-start-backend.py:60-69`）。

### 0.2 一次提交的完整数据流（主干）

```
① POST /submissions ──────────── judge-submission
   ├─ Redis 幂等 key（60s）+ DB 唯一索引 uk_submission_idempotent 双保险
   ├─ 事务内落 submission(PENDING) + judge_task(PENDING)
   ├─ Redis ZSet judge:judge:queue:zset 入队（仅作积压指标）
   └─ 事务 afterCommit 投 MQ：judge_submission#CREATED

② judge-worker 消费 CREATED/RETRY
   ├─ attempt 比对 → DB CAS 认领（写租约 lease_expire_at）→ Redis SETNX 任务锁
   ├─ Feign 拉题目判据（含隐藏用例）
   ├─ 编译容器 + 逐用例容器（docker run，独立 --rm 容器）
   └─ 回写 judge_result / submission，投 judge_submission#RESULT

③ 下游消费 RESULT（多消费组并行）
   ├─ judge-submission：摘 ZSet + WebSocket 推终态（topic sub:{id}）
   └─ judge-contest：Lua 原子更新榜单 ZSet → 合并窗口推 RANK_UPDATE

④ 全过程：worker 发 PROGRESS → judge-submission → WS 推进度
```

关键点：**判题任务的可靠投递靠 MQ，不靠 ZSet**。`judge:judge:queue:zset` 只在两个地方被消费端触碰——判题完成/失败时 `ZREM` 摘除（`judge-worker/src/main/java/com/codejudge/worker/engine/JudgeEngine.java:358-359,388`）与补偿调度扫描僵尸成员（`judge-submission/src/main/java/com/codejudge/submission/service/JudgeCompensationService.java:151-193`）。worker 从不从 ZSet 读任务。这与 `ARCHITECTURE.md:128` 的「Redis ZSet 低延迟通道」表述不同，以代码为准。

---

## 1. 判题链路

### 1.1 提交受理：`SubmissionService.submit()`

入口方法名是 `submit()`（不是 `createSubmission`），落库方法名为 `createSubmissionWithTask()`：
`judge-submission/src/main/java/com/codejudge/submission/service/SubmissionService.java:118,189`。

依次执行：

| 步骤 | 做什么 | 证据 |
|---|---|---|
| 1 | 校验语言、代码长度（≤32KB） | `SubmissionService.java:121-129`（常量 `:82`） |
| 2 | 提交频控：Redis `INCR` + 60s，超 30 次/分钟抛 400 | `:573-582`；`JudgeProperties.java:22` |
| 3 | Feign 校验题目存在且 `status==1`，不可用则快速失败 | `:134-143` |
| 4 | 竞赛提交额外校验（赛程内 / 题目归属 / 是否已报名） | `:146-148`、`:246-268` |
| 5 | 计算 `codeHash = sha256(code)`，组装幂等 key | `:150-152` |
| 6 | 幂等快路径：Redis 命中则直接返回已有提交 | `:155-162` |
| 7 | DB 兜底：同码在途记录幂等返回；已终态则 `submitRound+1` 落新行 | `:164-172` |
| 8 | 事务内落库 `submission` + `judge_task` | `:189-220` |
| 9 | 记 Redis 幂等 key + ZSet 入队 | `:179-183` |

**幂等键分两层**（这是链路里最值得记住的设计）：

```java
// 快路径（Redis，TTL 60s）
String idemKey = JudgeRedisKeys.SUBMISSION_IDEMPOTENT_PREFIX + userId + ":"
        + form.getProblemId() + ":" + contestId + ":" + codeHash;   // SubmissionService.java:151-152
// 最终防线（DB，唯一索引）
// UNIQUE KEY uk_submission_idempotent (user_id, problem_id, contest_id, code_hash, submit_round)
```
- Redis key 前缀：`judge:submission:idempotent:`（`judge-common/src/main/java/com/codejudge/common/constants/JudgeRedisKeys.java:14`）。
- DB 唯一索引：`sql/init.sql:320`；并发穿透由 `DuplicateKeyException` 兜底回查（`SubmissionService.java:202-212`）。
- 同一份代码只有判完（终态）后再交才会 `submit_round+1` 落新行（`:164-172`），语义是「正常刷新记录」。

### 1.2 投递到 MQ：Topic / Publisher / 事务时机

| 事实 | 证据 |
|---|---|
| Topic 常量 `TOPIC_JUDGE_SUBMISSION = "judge_submission"` | `judge-common/src/main/java/com/codejudge/common/mq/MqTopics.java:28` |
| Tag 常量 `CREATED / RETRY / PROGRESS / RESULT / DEAD` | `MqTopics.java:56-64` |
| 首次投递方法 `JudgeEventPublisher.publishTaskCreated()` | `judge-submission/src/main/java/com/codejudge/submission/mq/JudgeEventPublisher.java:28-36` |
| 重试投递 `publishRetry()`（5s 延迟，级别 2） | `JudgeEventPublisher.java:39-50`（`RETRY_DELAY_LEVEL=2` 在 `:23`） |
| **事务提交后投递** | `SubmissionService.java:227-228` 用 `TxSupport.afterCommit(...)` 包裹 |
| `TxSupport` 实现（`TransactionSynchronization.afterCommit`） | `judge-common/src/main/java/com/codejudge/common/utils/TxSupport.java:25-39` |

为什么必须挂在 afterCommit：若早于提交发送，worker 可能在事务可见前认领、CAS 查不到任务（`SubmissionService.java:222-226` 注释）。发送失败不抛异常，因为任务已落库，由补偿调度重发兜底（`JudgeEventPublisher.java:13-16`）。

### 1.3 worker 消费、租约与补偿重投

| 事实 | 证据 |
|---|---|
| 消费 topic `judge_submission`，仅订阅 `CREATED` / `RETRY` | `judge-worker/src/main/java/com/codejudge/worker/mq/SubmissionTaskHandler.java:56-71` |
| 消费组 `cj_worker_consumer` | `judge-worker/src/main/resources/application.yml:64` |
| 通用消费容器（异常 → `RECONSUME_LATER`） | `judge-common/src/main/java/com/codejudge/common/mq/RocketMQConsumerContainer.java:68-101` |
| 幂等三重防线 | `SubmissionTaskHandler.java:104-123` |
| 认领 CAS（写租约） | `judge-worker/src/main/java/com/codejudge/worker/mapper/WorkerJudgeTaskMapper.java:20-35` |

三重防线顺序（任一命中即丢弃消息）：

1. **attempt 比对**：消息 attempt ≠ 任务当前 attempt → 旧消息，丢弃（`SubmissionTaskHandler.java:104-109`）。
2. **DB CAS 认领**（权威防线）：
   ```sql
   UPDATE judge_task SET status='JUDGING', worker_id=?, lease_owner=?, lease_expire_at=?
   WHERE id=? AND status='PENDING' AND attempt=? AND deleted=0
   ```
   `WorkerJudgeTaskMapper.java:20-31`。
3. **Redis SETNX 任务锁** `judge:task:lock:{taskId}:{attempt}`，在认领成功**之后**补挂，TTL = 任务超时 + 30s（`SubmissionTaskHandler.java:122-123`；`JudgeRedisKeys.java:22-25`）。顺序反了会把锁挂死（`SubmissionTaskHandler.java:112-113` 踩坑注释）。

**租约时长** = `task.timeoutMs`（默认 120000ms，来自 `cj.judge.task-timeout-ms`）：

```java
int timeoutMs = task.getTimeoutMs() == null ? 120_000 : task.getTimeoutMs();
LocalDateTime leaseExpireAt = LocalDateTime.now().plusNanos(timeoutMs * 1_000_000L);
int claimed = taskMapper.claim(taskId, attempt, identity.workerId(), leaseExpireAt);
```
`SubmissionTaskHandler.java:114-117`；配置项 `judge-submission/.../config/JudgeProperties.java:15-16`。

**失败与补偿**（`JudgeCompensationService`，运行在 judge-submission 侧，四个定时任务）：

| 任务 | 周期 | 作用 | 证据 |
|---|---|---|---|
| `failoverScan` | 10s | 租约过期的 JUDGING → `takeoverExpiredLease`（attempt+1、置 PENDING）→ 投 RETRY；超限转 DEAD | `JudgeCompensationService.java:69-95`；接管 SQL `judge-submission/.../mapper/JudgeTaskMapper.java:45-60` |
| `rescuePendingScan` | 10s | PENDING 滞留超 `pendingRescueDelayMs`(15s) 未被认领 → 重发 RETRY | `JudgeCompensationService.java:100-118`；`JudgeProperties.java:27-28` |
| `deadLetterScan` | 30s | DEAD 任务补投业务死信 | `JudgeCompensationService.java:123-131` |
| `queueReconcileScan` | 60s | 摘除 ZSet 中已终态的僵尸成员（批次 500） | `JudgeCompensationService.java:151-193`（常量 `:54`） |

worker 侧失败处理：`JudgeEngine.failTask()` —— attempt+1 交还 PENDING 并投 RETRY，或超限 `markDead` + 投业务 DLQ（`judge-worker/.../engine/JudgeEngine.java:374-412`）。**最大重试次数 `maxAttempt=3`**（`JudgeProperties.java:18-19`；`sql/init.sql:333`）。

worker 探活：心跳 key `judge:worker:heartbeat:{workerId}`（TTL 30s），负载 ZSet `judge:worker:load`（`judge-worker/.../heartbeat/WorkerHeartbeat.java:32-38`）；workerId = `主机名:端口`（`judge-worker/.../mq/WorkerIdentity.java:22-30`）。

### 1.4 沙箱执行：`docker run` 参数与容器数量

构造命令的唯一位置：`judge-worker/src/main/java/com/codejudge/worker/sandbox/DockerSandbox.java:104-176`。

实际下发的隔离 / 限制参数：

| 参数 | 取值 | 证据 |
|---|---|---|
| `--rm` | 结束即删 | `DockerSandbox.java:108` |
| `--runtime` | 默认 `runc`，可切 gVisor `runsc` | `:110-113`；`WorkerProperties.java:22-23` |
| `--cpus` | 默认 1.0 | `:118`；`WorkerProperties.java:29` |
| `--memory` / `--memory-swap` | 同值 = 禁 swap；运行期取题目内存限额（默认 256MB） | `:119-120`；`JudgeEngine.java:93` |
| `--pids-limit` | 运行期 64，编译期 256 | `:117,121`；`WorkerProperties.java:32,44` |
| `--read-only` | 根文件系统只读 | `:123` |
| `-v <artifact>:/work:rw`（编译型）或 `--tmpfs /work`（Python） | 编译产物跨容器复用 | `:124-133` |
| `--tmpfs /tmp:...noexec` | /tmp 可写禁执行 | `:134` |
| **`--network none`** | 完全断网 | `:136` |
| **`--user 1000:1000`** | 非 root | `:138` |
| `--security-opt no-new-privileges` | 禁提权 | `:139` |
| `--cap-drop ALL` | 丢弃全部 capabilities | `:140` |
| `--security-opt seccomp=<file>` | 自定义 seccomp，缺失回退 docker 默认 | `:142-145`、`:308-319` |
| `-v <workDir>:/src:ro` | 源码 / stdin / run.sh 只读投递 | `:147,55` |
| 内层 `timeout -k 1 <secs>s` | 容器内精确计时 | `:226-248` |
| 墙钟兜底强杀 | `waitFor(wallClockMs)` + `destroyForcibly()` | `:161-168`；`JudgeEngine.java:231,266` |
| stdout/stderr 截断 | `maxOutputKb=64` | `:156-157,321-335`；`WorkerProperties.java:47` |

**每题启动几个容器**：每次 `SandboxExecutor.execute()` 都新建独立容器（`:80-100`，工作目录 `judge-<uuid>` `:283-289`）。

- 编译型语言（JAVA / CPP / GO）：**1 个编译容器 + N 个用例容器**（N=用例数）。编译产物经宿主持久目录 `/work` 跨容器共享（`JudgeEngine.java:121-138` 编译、`:154-182` 逐用例循环；`DockerSandbox.java:124-129,256-271`），`finally` 幂等释放（`JudgeEngine.java:98-101`）。
- PYTHON：**0 编译 + N 个用例容器**（`LanguageProfiles.compileCommand` 对 PYTHON 返回 null，`judge-worker/.../config/LanguageProfiles.java:33`）。
- CE 时不启用例容器，直接进终态（`JudgeEngine.java:127-134`）。
- 旁证：`scripts/dev-start-backend.py:32` 写明「每任务 5 个容器」。

> 注：`--read-only`、`--memory-swap` 在 Docker Desktop 上的实际生效程度未在仓库中找到验证代码，此处只确认参数已下发。

### 1.5 七种结论分别在哪里判定

| 结论 | 判定位置 |
|---|---|
| CE（编译错误） | `JudgeEngine.java:127-134`（`compile.isSuccess()==false`）；编译进程被信号杀死（非 0/1 退出）改判沙箱故障 `:245-250` |
| TLE | `JudgeEngine.java:287-290`（`isTimedOut() \|\| exitCode==124 \|\| timeMs>timeLimit`）；墙钟强杀标记 `DockerSandbox.java:203-206` |
| MLE | `JudgeEngine.java:291-294`（`isOomKilled() \|\| memKb>limit*1024`）；OOM(137) 标记 `DockerSandbox.java:191-193,207-210` |
| RE | `JudgeEngine.java:295-298`（`exitCode!=0`，先于 WA）；无 META 且 docker 退出 0 时约定置 255 → RE（`DockerSandbox.java:211-214`） |
| WA | `JudgeEngine.java:299-300` + 比对实现 `:305-313`（mode 0 精确 trim / mode 1 浮点容差 1e-6） |
| AC | 同上（比对通过）；提交级分数在 `:165-181`（首个非 AC 短路，score = AC 用例分值之和） |
| SE（系统错误） | `JudgeEngine.java:274-279`（沙箱故障映射）→ 抛 `SandboxException` → `failTask` 重试/死信；死信投递显式 `SE`（`:427`） |

枚举总表：`judge-worker/src/main/java/com/codejudge/worker/engine/Verdict.java:13-28`。

**两条实测补充（2026-09-23，`scripts/probe-snippet-verdicts.py` 跑了 33 个片段得到）**：

| 现象 | 实测 | 原因 |
|---|---|---|
| **Java 的"内存爆炸"判不出 MLE，只会判 RE** | 用 `new long[8000000]`（64MB）对 4003（32MB 限）提交 → **RE**，非 MLE | 容器被 cgroup 限到 32MB，JVM 默认 `MaxHeapSize` ≈ 容器内存的 1/4（约 8MB），分配请求一超过堆上限，JVM 自己先抛 `OutOfMemoryError`、进程以**普通非零码**退出 → 落进 RE 分支（`exitCode!=0`，`JudgeEngine.java:295-298`）。而 MLE 要求进程被 OOM Killer 杀掉（137）或 `memKb > limit*1024` —— Java 在 32MB 容器里两种都到不了。同样写法下 Python / C++ 能正常判 MLE（实测均 MLE） |
| **Python 的语法错误判不出 CE，只会判 RE** | `def main(:` 对 4005 提交 → **RE** | 沙箱对 Python 没有独立编译步（Java 走 `javac`、C/C++/Go 走 `gcc`/`go build`，见 `LanguageProfiles`），解释器加载源码时才抛 `SyntaxError` → 非零退出 → 归入 RE。**后果：界面按「编译错误」筛选永远筛不到 Python 提交。** 若视为缺陷，修法是在沙箱里为脚本语言加一次 `python -m py_compile` 预检 |

> 另有一条与结论无关但同样反直觉的：**"朴素 O(n) 循环"在 C++ 上不必然 TLE**。
> `for(i=1..n) s += i`（n=1e9）被 GCC `-O2` 折叠成闭式公式，实测 220ms 判 AC；
> 加 `volatile` 后 242ms 仍 AC；再加重到 724ms 还是 AC。最终需每次迭代做 4 次带副作用的
> 内存往返才稳定超 1000ms。造 TLE 用例必须实测。

### 1.6 进度与结果的 Topic / Tag

| 阶段 | Topic | Tag | 证据 |
|---|---|---|---|
| 进度发布 | `judge_submission` | `PROGRESS` | `judge-worker/.../mq/ProgressPublisher.java:65`；生产端限流默认 800ms、末用例强制发（`WorkerProperties.java:75`、`ProgressPublisher.java:55-58`） |
| 进度消费 | `judge_submission` | `PROGRESS` | `judge-submission/.../mq/ProgressEventHandler.java:40-48` |
| 进度 WS | — | 类型 `SUB_PROGRESS`，topic `sub:{submissionId}` | `judge-submission/.../service/SubmissionProgressPushService.java:44-46,60` |
| 结果发布 | `judge_submission` | `RESULT` | `JudgeEngine.java:336-356` |
| 结果消费 | `judge_submission` | `RESULT` | `judge-submission/.../mq/ResultEventHandler.java:45-52`（顺带 ZREM `:60`） |
| 结果 WS | — | 类型 `SUB_RESULT` | `SubmissionProgressPushService.java:66-88` |
| 死信 | `judge_submission_dlq` | `DEAD` | `JudgeEngine.java:415-436`；`MqTopics.java:36,64` |

WS 端点 `/ws/submissions/*`（`judge-submission/.../config/SubmissionWsConfig.java:32`）；跨实例广播走 Redis 发布订阅 channel `judge:ws:broadcast`（`judge-common/.../ws/RedisPushChannel.java:49-58`；`WsProperties.java:27`）。推送报文带按 topic 单调自增的 `seq`（`judge-common/.../ws/WsSequencer.java:20-28`）。

---

## 2. 竞赛与排行榜

### 2.1 榜单 key 与 score 三段编码

Redis key（常量类 `judge-common/src/main/java/com/codejudge/common/constants/JudgeRedisKeys.java`）：

| key | 类型 | 说明 | 证据 |
|---|---|---|---|
| `judge:contest:rank:{contestId}` | ZSet | 实时榜（**永远更新**） | `JudgeRedisKeys.java:62` |
| `judge:contest:rank:frozen:{contestId}` | ZSet | 封榜冻结榜 | `:70` |
| `judge:contest:user:{contestId}:{userId}` | Hash | 逐题状态（field=problemId） | `:81` |
| `judge:contest:user:frozen:{contestId}:{userId}` | Hash | 封榜时的逐题状态副本 | `:96` |
| `judge:contest:lock:freeze:{contestId}` | String | 封榜幂等锁 | `:102` |
| `judge:contest:push:dirty:{contestId}` | String | 推送脏标记（仅观测） | `:108` |

score 编码公式（Java 与 Lua 各一份，必须保持一致）：

```lua
local encoded = weight * RANK_SHIFT + (SEG_BASE - penalty) * MID_SHIFT + (SEG_BASE - tie)
-- RANK_SHIFT=10^12, MID_SHIFT=10^6, SEG_BASE=999999
```
`judge-contest/src/main/resources/lua/contest_rank_update.lua:174`（常量 `:58-60`）

即 `score = 权重×10^12 + (999999−罚时秒)×10^6 + (999999−末次通过偏移秒)`，语义为「权重高者在前 → 罚时少者在前 → 通过更早者在前」。Java 侧常量与解码：

```java
public static final long WEIGHT_FACTOR  = 1_000_000_000_000L; // 10^12
public static final long PENALTY_FACTOR = 1_000_000L;         // 10^6
public static final long SEGMENT_BASE   = 999_999L;
```
`judge-contest/.../service/ContestRankService.java:88,90,92`；解码 `:436-439`。ZSet 升序存、读榜用 `reverseRange`（`:393-396`）。精度边界：权重须 ≤ 8999，否则超 `double` 安全整数位（`ContestRankService.java:66-68`、Lua `:28-32`）。

### 2.2 一次通过后榜单如何更新 / 为什么必须用 Lua

链路：judge-worker 发 RESULT → judge-contest 用**独立消费组**消费同一 topic（不引入转发主题）：

- 消费入口：`judge-contest/src/main/java/com/codejudge/contest/mq/ContestResultHandler.java:70-75`；订阅 `RESULT`（`:48-52`）。
- 消费组 `cj_contest_result_consumer`（`judge-contest/src/main/resources/application.yml:74`）。
- 真正更新：`ContestRankService.executeUpdate()` 通过 `redis.execute(contestRankScript, keys, args)` 跑 Lua（`ContestRankService.java:174-194`）。
- 脚本 Bean 装配：`judge-contest/.../config/ContestRedisConfig.java:22-27`。

Lua 脚本做四件事（`contest_rank_update.lua`）：

1. 读该用户该题状态 Hash（`:79`）；
2. 按赛制更新状态（IOI 取最高分 `:85-118`；ACM 首次 AC 才计分、此前错误计数 `:119-162`）；
3. **重算**总罚时 / 权重（而非增量累加，`:147-160`）；
4. 与 ZSet 现值比对，不同才 `ZADD`，最后 `EXPIRE`（`:169-185`）。

为什么必须用 Lua（脚本顶部原文）：

```lua
-- 若罚时/通过数是「先 GET 状态 → 在 Java 里累加 → 再 ZADD」，
-- 两个并发事件会各自基于同一份旧状态计算出相同结果，出现**丢更新**。
-- Lua 在 Redis 单线程内执行，把「读状态 → 改状态 → 重算 → 写榜单」变成一次原子操作。
```
`contest_rank_update.lua:4-9`。附带收益：因为用「重算」而非「累加」，重复消费天然幂等（`:169-180`）。

### 2.3 封榜 / 解封 / WS 推送

| 事实 | 证据 |
|---|---|
| 封榜流程 `freeze()`：快照幂等检查 → SETNX 锁 → 冻结逐题副本 → `ZUNIONSTORE` 原子生成冻结榜 → 落库 FROZEN 快照 | `ContestRankService.java:206-251` |
| 冻结逐题副本 `freezeUserStates()`（防止「名次冻结、明细从 -1 变 +」泄漏） | `:264-290`；问题背景 `JudgeRedisKeys.java:83-96` |
| 关键设计：实时榜永远写，封榜只影响「读哪个榜」 | `ContestRankService.java:70-80`；读榜选 key `:344-346` |
| 冻结榜 / 实时榜 key | `:657-663` |
| WS topic：`contest:{id}:rank:public` / `contest:{id}:rank:full` | `:641-643`（后缀常量 `:101-102`） |
| 版本号来源：`WsSequencer`（REST 与 WS 同源） | `:645-651`；`judge-common/.../ws/WsSequencer.java:20-28` |
| 推送：合并窗口（默认 1000ms）+ 内容去重，`setSeq(nextVersion)` | `judge-contest/.../service/ContestRankPusher.java:86-139`；`ContestProperties.java:26` |
| 状态事件 `CONTEST_STATUS`（开赛/封榜/结束）绕过去重无条件推 | `ContestRankPusher.java:148-169` |
| 榜单 WS 端点 `/ws/contests/{contestId}/rank`，非特权 `full=true` 直接拒连（fail-closed） | `judge-contest/.../ws/ContestRankWsHandler.java:53-89` |
| 终榜重建 `rebuild()`：按提交时间升序回放提交表重算 | `ContestRankService.java:550-635` |

### 2.4 竞赛状态推进者与扫描周期

- 推进者：`ContestLifecycleService.scan()`，`@Scheduled(fixedDelayString="${cj.contest.lifecycle-scan-interval-ms:10000}")`（`judge-contest/.../service/ContestLifecycleService.java:43-63`）。
- 配置项名：`cj.contest.lifecycle-scan-interval-ms`，默认 10000ms（`judge-contest/.../config/ContestProperties.java:28-29`）。
- 设计：真实状态是「当前时间的函数」`effectiveStatus`，扫描只把推导结果落库并触发副作用（CAS 迁移 + 封榜 + 终榜快照），服务重启后可自愈（`ContestLifecycleService.java:16-25,65-95`；`Contest.java:70-80`）。
- 状态值：0 未开始 / 1 进行中 / 2 已结束（`Contest.java:23-28`；`sql/init.sql:403`）。

### 2.5 罚时计算

- ACM：`penalty = Σ(该题错误次数 × penaltyPerWrong + (AC时刻 − 开赛时刻))`，只对已通过的题累加（`contest_rank_update.lua:147-160`，公式在 `:157`）。
- `penaltyPerWrong = penaltyMinutesOrDefault() × 60`（分钟转秒）（`ContestRankService.java:177`；`Contest.java:86-88`）。
- 默认每次错误罚时 20 分钟（ICPC 惯例）（`Contest.java:55-56`；`sql/init.sql:402`）。
- IOI：不计错误罚时，`penalty = tie = 最后一次得分时刻 − 开赛时刻`，满分封顶（`contest_rank_update.lua:85-118`）。
- 罚时/总分由逐题状态 Hash **重算**，因此重判导致的结论回退也能自愈（`JudgeRedisKeys.java:72-81`；`contest_rank_update.lua:121-124`）。
- 竞赛结束后的提交不计入榜单（按提交时刻判定）（`ContestRankService.java:150-157`）。

---

## 3. AI 点评

### 3.1 SSE 事件类型与顺序

事件类型 `START / RETRIEVAL / DELTA / ERROR / END`，定义在 `judge-ai/src/main/java/com/codejudge/ai/domain/ReviewEventVO.java:67-90`（说明表 `:15-23`）。

发出顺序：`START → RETRIEVAL → DELTA×N → END`，见 `judge-ai/src/main/java/com/codejudge/ai/service/ReviewService.java:170-183`：

```java
Flux<ReviewEventVO> body = Flux.concat(
    Flux.defer(() -> Flux.just(ReviewEventVO.start(reviewId, submissionId, degraded, model))),
    Flux.defer(() -> Flux.just(ReviewEventVO.retrieval(retrievalSummary, submissionId, sources))),
    deltas.map(d -> ReviewEventVO.delta(d, null)),
    Flux.defer(() -> { ...; return Flux.just(ReviewEventVO.end(...)); })
);
```

配套机制：

| 机制 | 证据 |
|---|---|
| 唯一出口 `wrap()` 分配自增 id、写重连缓冲、包 SSE | `ReviewService.java:504-517` |
| 心跳 `:ping`（默认 15s），END 后终止整条流 | `:519-527`、`:201-203`；`RagProperties.java:43` |
| 断线重连按 `Last-Event-ID` / `seq` 回放（不按下标） | `:558-584` |
| 并发上限 `cj.rag.max-concurrent-streams`（默认 200），超限返回 429 + END(BUSY) | `:112-123,206-211`；`RagProperties.java:49` |
| 控制器 `POST/GET /ai/review/stream`（`text/event-stream`） | `judge-ai/.../controller/ReviewController.java:76,101` |
| 错误走 `ERROR` 事件而非 HTTP 状态码 | `ReviewEventVO.java:25-31`；`ReviewService.java:220-233` |

### 3.2 pgvector 检索

| 事实 | 证据 |
|---|---|
| 表 `knowledge_chunk`（题目知识切片）+ `ai_review`（历史点评，同表存 embedding） | `deploy/pgvector/init.sql:17-30,44-63` |
| 向量维度 `vector(1024)`（两表一致；配置默认 `cj.llm.embedding-dimension=1024`） | `deploy/pgvector/init.sql:28,56`；`judge-ai/.../config/LlmProperties.java:70-71` |
| 索引 HNSW + `vector_cosine_ops`；ai_review 为部分索引 `WHERE status=1 AND embedding IS NOT NULL` | `deploy/pgvector/init.sql:33-34,81-83` |
| 召回 SQL：`SELECT ..., (1-(embedding <=> ?)) AS score ... ORDER BY embedding <=> ? ASC LIMIT ?` | `judge-ai/.../repository/KnowledgeVectorRepository.java:82-97` |
| 历史点评召回过滤 `problem_id`、`status=1`、排除本次提交 | `judge-ai/.../repository/AiReviewRepository.java:156-172` |
| **双路召回**：题目知识（topK=4）+ 历史点评（historyTopK=3），题目知识在前 | `judge-ai/.../service/KnowledgeService.java:101-129`；`ReviewContextService.java:179-190`；`RagProperties.java:30-37` |
| 未配置 apiKey 时 embedding 降级为「哈希伪向量」（维度一致、无语义） | `judge-ai/.../service/EmbeddingService.java:52-59,133-146` |
| 检索 query 不是直接丢代码，而是「题目标题+结论关键词+语言+点评类型+追问」 | `ReviewContextService.java:192-248` |

### 3.3 未配置 LLM 时的「结构化降级」

| 事实 | 证据 |
|---|---|
| 判定 `degraded = !llmProperties.available()` | `ReviewService.java:133,259`；`LlmProperties.java:73-76` |
| 流式路径改为 `degradedDeltas(ctx)` | `ReviewService.java:156-161,400-409` |
| 非流式路径改为 `buildDegradedContent(ctx)` | `:262-264` |
| 降级正文内容：结论 / 判题数据 / 编译错误 / 逐用例表 / RAG 命中 / 下一步建议 | `:323-398` |
| 落库 model 写 `builtin-fallback`，END 的 `finishReason=DEGRADED` | `:70-71,415-427,180-181` |
| 降级内容不入向量索引（避免雷同模板霸榜） | `:458-462` |

设计取向：LLM 不可用时仍返回**平台已知事实的结构化摘要**并标注 `degraded=true`，而不是一句「AI 不可用」（`:60-63` 类注释）。

### 3.4 为什么 judge-ai 是响应式（WebFlux）

| 事实 | 证据 |
|---|---|
| 服务跑在 WebFlux / Netty，SSE 逐段返回 | `judge-ai/src/main/java/com/codejudge/ai/AiApplication.java:20` |
| 约束一：**不使用 `UserContext`**（ThreadLocal + Servlet 拦截器在 WebFlux 下恒为 null），改从 `user-info`/`role-info` 头取值 | `AiApplication.java:24-28`；`judge-ai/.../security/AiIdentity.java:31-41` |
| 约束二：**不用 `InternalOnlyGuard` / `OwnerAccessGuard`**（否则外部请求会被误判为内部调用而放行） | `AiIdentity.java:16-23` |
| 所有阻塞 IO 切到 `Schedulers.boundedElastic()` | `AiApplication.java:28-31`；`ReviewService.java:136-138,203,247-248`；`ReviewController.java:175-178,187-189` |
| 鉴权用可显式调用的 `AiAccessGuard`（非 WebFilter） | `judge-ai/.../security/AiAccessGuard.java:24-41` |
| LLM 客户端 `WebClient` + `Flux` 解析 SSE `choices[0].delta.content` | `judge-ai/.../service/LlmClient.java:87-111` |
| 网关对 `/ai/**` 放宽响应超时到 900000ms | `judge-gateway/src/main/resources/application.yml:152-157` |

---

## 4. 鉴权与网关

### 4.1 登录签发双 Token

| 事实 | 证据 |
|---|---|
| access TTL = 30 分钟；refresh TTL = 30 天 | `judge-auth/.../service/AccountService.java:46-47` |
| 签发 `createAccessToken(id, user.type, ACCESS_TTL)` + `createRefreshToken(id, REFRESH_TTL)`，`user.type` 写入 role claim | `:126-128`；`judge-auth/.../util/JwtTool.java:35-57` |
| **签名算法为 HMAC**（`Keys.hmacShaKeyFor(CJ_JWT_SECRET)`），非 RS256 | `JwtTool.java:23-25`；网关验签侧一致 `judge-gateway/.../util/JwtUtils.java:19-21,41-48` |
| 角色判定唯一依据是 `user.type`（1 员工 / 2 学员 / 3 教师） | `AccountService.java:118,130,151-153` |
| **refresh cookie 按角色二选一**：员工 `judge-admin-refresh-token`，其余 `judge-refresh-token`；登录时清掉另一种 | `judge-auth/.../controller/AccountController.java:119-132` |
| cookie 属性：path=/、maxAge 30 天、HttpOnly、SameSite=Lax | `AccountController.java:124-131` |
| refresh 读取顺序「先普通、后管理」 | `AccountController.java:103-106` |
| Cookie 名常量 | `judge-auth/.../common/constants/JwtConstants.java:10,12` |
| 刷新时补查 `user.type` 以保留 role claim | `AccountService.java:155-179` |

### 4.2 网关传身份 / 下游读身份

| 事实 | 证据 |
|---|---|
| 过滤器 `AuthGlobalFilter`（`GlobalFilter`，`getOrder()=-100`），验签后注入 `user-info`(userId) 与 `role-info`(user.type) | `judge-gateway/.../filter/AuthGlobalFilter.java:24-63,138-141` |
| header 名常量 | `AuthGlobalFilter.java:26-27` |
| 白名单「可选鉴权」：带合法 token 才透传，否则剥离伪造的身份头 | `AuthGlobalFilter.java:83-106` |
| token 解析：`Authorization: Bearer`；仅 `/ws/**` 放宽为查询参数 `token` | `AuthGlobalFilter.java:108-136` |
| 下游 `UserContext` 由 `UserInfoInterceptor` 读 header 填充，`afterCompletion` 清理 | `judge-common/.../interceptor/UserInfoInterceptor.java:16-39` |
| 拦截器注册 `/**`，且先于 `RoleInterceptor` | `judge-common/.../config/MvcConfig.java:19-21` |
| `UserContext` role 语义 = `user.type` | `judge-common/.../utils/UserContext.java:3-7` |

一句话：**下游服务不解析 JWT**，只信任网关注入的两个请求头；`user-info` 缺失即未登录。

### 4.3 按钮级能力码 `Capabilities`

唯一定义类：`judge-auth/src/main/java/com/codejudge/auth/domain/Capabilities.java:41`。

| 角色 | 能力码数量 | 证据 |
|---|---|---|
| 全量目录 `CATALOGUE` | **20** | `Capabilities.java:99-126` |
| 学员 `STUDENT` | **6** | `:133-137`；测试硬断言 `judge-auth/src/test/.../CapabilitiesTest.java:68` |
| 教师 `TEACHER` | **16**（= 学员 ∪ 建题/赛务/看全部提交/隐藏用例/知识库） | `Capabilities.java:140-144`（人工计数；测试未断言教师数量） |
| 员工 `STAFF` | **20**（= CATALOGUE 键集） | `:146-147`；测试 `CapabilitiesTest.java:87` |

未知 / 空 `user.type` 返回空集（fail-closed，`:164-174`；测试 `CapabilitiesTest.java:28-38`）。下发接口 `GET /accounts/me/capabilities`（`AccountController.java:71-75`）。设计要点：能力码由后端计算、前端只负责渲染，前端不做「角色 → 能做什么」的推导（`Capabilities.java:12-40`）。

### 4.4 `/jwks` 的问题（**已修** —— 2026-09-25 采用方案 A 删除）

> **2026-09-25 更新**：本节记录的缺陷已修复 —— 删除 `JwkController` 与
> `JwtTool.getPublicKeyBase64()`，`JwtProperties` 白名单与网关路由里的 `/jwks` 一并摘除，
> `judge-api` 的 `AuthClient`（唯一"调用方"，实际无人用）同步清空。
> 同时补上 token 类型校验（网关 `parseIdentity` 只放行 `type=access`；`/accounts/refresh`
> 只接受 `type=refresh`），堵住「refresh token 可当 access token 用」的同源缺口。
> 下文为修复前的原始分析，保留作证据。

（历史）`JwkController` 的注释写着「JWK 公钥接口，供网关验签」，但这两件事都不成立：

`JwkController` 的注释写着「JWK 公钥接口，供网关验签」，但这两件事都不成立：

| 观察 | 证据 |
|---|---|
| 网关**不消费**它 —— 网关直接用共享密钥验签 | `judge-gateway/.../util/JwtUtils.java:19-21,41-48` 用 `properties.getSecret()`；全仓库无 `getPublicKeyBase64` 的调用方 |
| 它返回的不是公钥，而是**HMAC 密钥本体**的 base64 | `JwtTool.getPublicKeyBase64()` = `Base64.encode(key.getEncoded())`（`JwtTool.java:81-83`）。对称密钥没有"公钥"，`getEncoded()` 就是密钥本身 |
| 它在免鉴权白名单里，匿名可读 | `JwtProperties.java:39-40` 列了 `/jwks`、`/jwks/**` |

**实测（本机，2026-09-23）**：

```
① GET http://localhost:9080/jwks            → 200，无需任何凭证
   {"code":200,"msg":"OK","data":"OU01QU5OSzZjczJtNm1yNUVHZ1l5blNxSGpfajhqd0VuQm1VZTlySkltcTVOZi1FdUY2MWlXaG9YekNwMHRESg=="}
② base64 解码 → 64 字节可打印 ASCII（即 CJ_JWT_SECRET 原文）
③ 用该密钥自签 {userId: <真实管理员id>, roleId: 1, type: "access"}（HS256）
④ GET /problems/4001/versions（@RequireRole({STAFF, TEACHER})，只读）
   无凭证 → 401 未登录或登录已过期
   带自签令牌 → 200，正常返回题面版本
```

**影响**：任何能访问网关的客户端都能取到签名密钥，进而伪造**任意 userId、任意角色**的
令牌，绕过全部接口级鉴权（含管理端）。这是完整的认证绕过，不是"信息泄露"级别的问题。

**修复方向（二选一，需先定方向）**：

- **A. 删掉 `/jwks`**（推荐，改动最小）：HMAC 是当前既定设计，网关与 auth 本就共享密钥，
  这个端点没有任何消费方。删除 `JwkController`，并从 `JwtProperties` 白名单与网关路由里
  摘掉 `/jwks`。同时补一条守卫（`scripts/check-hardcoded-defaults.py` 一类）扫描
  「把对称密钥当公钥输出」的写法，防复发。
- **B. 改为非对称（RS256/ES256）**：`/jwks` 才有正当语义 —— 返回真正的公开 JWK
  （`{"kty":"RSA","n":…,"e":…}`），网关用公钥验签，私钥只留在 auth。
  代价是密钥分发/轮换、以及网关侧要换成公钥加载逻辑。

> 无论选哪个，**当前状态在修好之前不应对外暴露**（本地演示环境风险有限，但不该带着它上线）。

---

## 5. 服务与端口全景

| 服务名 | 端口 | 模块目录 | 一句话职责 |
|---|---|---|---|
| judge-gateway | 9080 | `judge-gateway` | 统一入口：路由、JWT 验签、登录限流、CORS、SSE/WS 超时放宽 |
| judge-auth | 9081 | `judge-auth` | 统一登录（按 `user.type` 判定角色）、双 Token、能力码下发、首管理员引导 |
| judge-user | 9082 | `judge-user` | 学员/教师/管理员基础信息与密码校验（BCrypt） |
| judge-problem | 9083 | `judge-problem` | 题目、题面版本、测试用例（含隐藏）、标签 |
| judge-submission | 9084 | `judge-submission` | 提交落库 + 幂等 + MQ 投递 + 结果查询 + 进度 WS + 补偿调度 |
| judge-worker | 9085（可多实例 9085/9185/9285） | `judge-worker` | 判题机：MQ 消费 + docker 沙箱执行 + 心跳 + 租约 |
| judge-contest | 9086 | `judge-contest` | 竞赛、报名、Redis ZSet 实时榜、封榜/终榜重建、榜单 WS |
| judge-ai | 9087 | `judge-ai` | LLM 代码点评 + pgvector RAG + SSE 流式（WebFlux） |
| （前端） | 5174 | `judge-web` | Vue 前端，`/api` 代理到 9080 |
| （监控栈） | 9090 / 9093 / 3001 | `deploy/monitoring` | Prometheus / Alertmanager / Grafana |

证据：各模块 `application.yml` 第 2 行 `server.port`；`scripts/dev-start-backend.py:60-69`；README.md:38-45。

非服务类模块（无端口）：`judge-common`（公共工具 / MQ / WS 基座）、`judge-api`（Feign 客户端 + 跨服务 DTO）——二者无 `application.yml`。

---

## 6. 数据存储分工

### 6.1 MySQL 5 个库（`sql/init.sql`，按服务分库）

| 库名 | 表清单 | 用途 | 证据 |
|---|---|---|---|
| `judge_auth` | `role` `menu` `privilege` `account_role` `role_menu` `role_privilege` `login_record` | RBAC 与登录流水（账号密码在 judge_user） | `sql/init.sql:37-139` |
| `judge_user` | `user` `user_detail` | 账号 + BCrypt 密码；JWT role claim 取自 `user.type` | `sql/init.sql:146-188` |
| `judge_problem` | `problem` `problem_version` `test_case` `tag` `problem_tag` | 题目、题面版本、测试用例（隐藏/分值）、标签 | `sql/init.sql:198-287` |
| `judge_submission` | `submission` `judge_task` `judge_result` `compile_info` | 提交、判题任务（含租约）、逐用例结果、编译信息 | `sql/init.sql:292-385`；唯一索引 `:320` |
| `judge_contest` | `contest` `contest_problem` `contest_registration` `contest_rank_snapshot` | 竞赛、竞赛题目、报名、封榜/终榜快照 | `sql/init.sql:390-463` |

### 6.2 PostgreSQL（judge_ai，含 pgvector）

- 库 `judge_ai`（PostgreSQL 16 + pgvector），与 MySQL 并存（`deploy/pgvector/init.sql:3-8`）。
- `knowledge_chunk`：题目知识切片，`embedding vector(1024)` + HNSW 余弦索引（`deploy/pgvector/init.sql:17-34`）。
- `ai_review`：点评记录 + 正文向量，`status` 0 生成中 / 1 完成 / 2 失败，部分 HNSW 索引（`:44-63,81-83`）。
- 连接配置 `org.postgresql.Driver`（`judge-ai/src/main/resources/application.yml:44-46`）。

### 6.3 Redis（按 key 前缀）

| key 前缀 / 名称 | 类型 | 用途 | 证据 |
|---|---|---|---|
| `judge:submission:idempotent:` | String(60s) | 提交幂等快路径 | `JudgeRedisKeys.java:14` |
| `judge:submission:rate:` | String INCR(60s) | 提交频控 | `:50` |
| `judge:judge:queue:zset` | ZSet | 待判队列（积压指标） | `:19` |
| `judge:task:lock:` | String SETNX | 任务级幂等锁 | `:25` |
| `judge:task:rescue:` | String SETNX(30s) | 滞留重发防抖 | `:45` |
| `judge:task:dlq:` | String SETNX(24h) | 死信投递去重 | `:39` |
| `judge:worker:heartbeat:` | String(30s) | worker 心跳 | `:30` |
| `judge:worker:load` | ZSet | worker 负载 | `:33` |
| `judge:contest:rank:` / `:frozen:` | ZSet | 实时榜 / 冻结榜 | `:62,70` |
| `judge:contest:user:` / `:frozen:` | Hash | 逐题状态 / 封榜副本 | `:81,96` |
| `judge:contest:lock:freeze:` | String SETNX | 封榜幂等锁 | `:102` |
| `judge:contest:push:dirty:` | String | 推送脏标记 | `:108` |
| `judge:ws:session:{instanceId}:{userId}` | Set | WS 会话索引（观测） | `:117` |
| `judge:ws:broadcast` | Pub/Sub | WS 跨实例广播通道 | `judge-common/.../ws/WsProperties.java:27` |
| `judge:ai:memory:` | String(7d) | 点评会话记忆 | `judge-ai/.../constants/AiRedisKeys.java:20` |
| `judge:ai:sse:` | List | SSE 断线重连缓冲 | `AiRedisKeys.java:28` |
| `rate:login:` | 令牌桶 | 网关登录防爆破限流 | `judge-gateway/.../ratelimit/LoginRateLimitKeyResolver.java:23,31` |

### 6.4 RocketMQ Topic

| Topic | Tag | 用途 | 生产 → 消费 | 证据 |
|---|---|---|---|---|
| `judge_submission` | `CREATED` | 首次投递判题 | submission → worker | `MqTopics.java:28,56`；`JudgeEventPublisher.java:28-36` |
| `judge_submission` | `RETRY` | 超时/宕机/沙箱异常后重投（5s 延迟） | submission/worker → worker | `MqTopics.java:57`；`JudgeEventPublisher.java:39-50` |
| `judge_submission` | `PROGRESS` | 判题中途进度 → WebSocket | worker → submission | `MqTopics.java:59-60`；`ProgressPublisher.java:65` |
| `judge_submission` | `RESULT` | 判题终态回写 | worker → submission + contest（两个消费组各收一份） | `MqTopics.java:61-62`；`JudgeEngine.java:353` |
| `judge_submission_dlq` | `DEAD` | 业务死信（可读/可重放/可告警） | worker / submission → 人工/重放 | `MqTopics.java:36,64`；`JudgeEngine.java:431` |
| `ai_review` | `REQUESTED` | AI 点评异步预生成（默认关闭） | submission/用户 → ai | `MqTopics.java:39,66`；`judge-ai/.../mq/AiReviewMqConsumer.java:43-56` |
| `contest_rank` | `CHANGED` | **P4 未启用，保留位** | 未实现 | `MqTopics.java:42-52,68` |

消费组：submission `cj_submission_result_consumer`、worker `cj_worker_consumer`、contest `cj_contest_result_consumer`、ai `cj_ai_review_consumer`（各自 `application.yml` 的 `rocketmq.consumer-group`）。生产者组：submission `cj_submission_producer`、worker `cj_worker_producer`。NameServer 默认 `localhost:9877`。

---

## 7. 关键设计取舍与坑（最值得先读的部分）

| # | 设计 / 坑 | 结论与证据 |
|---|---|---|
| 1 | **提交幂等必须「Redis + DB 唯一索引」双保险** | Redis 只是快路径，并发穿透靠唯一索引 `uk_submission_idempotent` 兜底，缺一都会漏判（`SubmissionService.java:54-61,202-212`；`sql/init.sql:320`） |
| 2 | **MQ 首次投递必须挂在事务 afterCommit** | 早发会与事务可见性竞争；漏挂这一步会让端到端从 ~3.4s 恶化到 ~30s（`SubmissionService.java:222-228`） |
| 3 | **判题任务只从 MQ 取，ZSet 不是队列** | worker 代码从不读 ZSet，只在终态/死信时 ZREM；ZSet 服务 `judge_queue_backlog` 指标（`JudgeEngine.java:358-359,388`；`JudgeCompensationService.java:151-193`）。`ARCHITECTURE.md:128` 的「双通道投递」表述与代码不符 |
| 4 | **Redis SETNX 任务锁必须在 DB CAS 认领之后挂** | 顺序反了，一次消费异常会把锁挂住、在 TTL 内拦截合法重投（`SubmissionTaskHandler.java:112-113`） |
| 5 | **补偿调度是与失败原因无关的兜底** | 故障转移 / 滞留重发 / 死信 / 队列对账四件套，全部用 CAS / SETNX 防并发（`JudgeCompensationService.java:28-43`） |
| 6 | **沙箱每次执行起独立容器，编译产物靠宿主持久目录跨容器存活** | 若改回容器内 tmpfs，编译型语言产物随编译容器退出即销毁，全部判不出 AC（`DockerSandbox.java:124-129`；`JudgeEngine.java:76-102`） |
| 7 | **编译期 pids 必须放宽到 256，运行期严守 64** | Go 工具链在 64 下稳定失败，运行期用 64 防 fork 炸弹（`WorkerProperties.java:31-44`；`DockerSandbox.java:114-121`） |
| 8 | **seccomp / no-new-privileges / cap-drop ALL / network none / 非 root 五件套** | 隔离参数集中在 `DockerSandbox.runContainer`（`DockerSandbox.java:114-149`） |
| 9 | **竞赛榜必须用 Lua 原子「读-改-写」** | Java 侧读-算-写会丢更新；Lua 单线程内完成，无需分布式锁（`contest_rank_update.lua:4-9`） |
| 10 | **榜单名次由 score 编码决定，不由应用侧二次排序** | 名次是分布式共识，前端二次排序会因排序稳定性差异产生不同名次（`ContestRankService.java:62-64`；Lua `:22-26`） |
| 11 | **封榜只冻结 ZSet 不够，必须同时冻结逐题状态副本** | 否则「名次冻结、明细从 -1 变 +」，等于公开宣布封榜后谁过了题（`ContestRankService.java:254-262`；`JudgeRedisKeys.java:86-93`） |
| 12 | **封榜写关键顺序：先冻结逐题副本，再 `ZUNIONSTORE` 覆盖冻结榜** | 反了则靠当前冻结榜反查旧成员集失败，旧副本残留（`ContestRankService.java:230-234`） |
| 13 | **LLM 未配置时做「结构化降级」而非报错** | 返回判题结论、逐用例、编译错误、RAG 命中的结构化摘要并标 `degraded=true`（`ReviewService.java:60-63,315-398`） |
| 14 | **judge-ai 是 WebFlux，禁用 `UserContext` 与两个 Guard** | `UserContext` 恒为 null；复用 `InternalOnlyGuard` 会把所有外部请求误判为内部调用而放行（`AiIdentity.java:10-23`） |
| 15 | **SSE 错误走事件不走 HTTP 状态码** | 首个事件发出后响应头已是 200，无法再改状态码（`ReviewEventVO.java:25-31`） |
| 16 | **重连回放必须按 `seq` 过滤，不能按下标** | 缓冲做过 `trim`，下标与 id 会整体偏移（`ReviewService.java:551-557`） |
| 17 | **PG 插入点评用 `INSERT ... RETURNING id`** | PG 驱动在 `RETURN_GENERATED_KEYS` 下 `getKey()` 抛异常 → 「INSERT 成功但拿不到 id」的静默故障（`AiReviewRepository.java:49-70`） |
| 18 | **JWT 当前是 HMAC 对称签名** | `Keys.hmacShaKeyFor(CJ_JWT_SECRET)`（`JwtTool.java:23-25`；`JwtUtils.java:19-21`）。`ARCHITECTURE.md:14` 的「RS256 + JWKS」为陈旧描述，以代码为准 |
| 19 | **能力码 fail-closed** | 未知 `user.type` 返回空集，而不是给满（`Capabilities.java:159-174`） |
| 20 | **多实例判题必须限制消费线程数** | `实例数 × CJ_MQ_CONSUME_THREADS ≤ 宿主机 CPU 核数`，否则并发容器冷启动超墙钟预算 → 正确解被判假 TLE（`judge-worker/.../application.yml:67-70`；`RocketMQConsumerContainer.java:37-59`） |
| 21 | 🔴 **`/jwks` 端点把 HMAC 密钥当"公钥"匿名发出（可签发任意身份令牌）** | 详见下方「4.4 `/jwks` 的问题」。一句话：签名是**对称**的，密钥的 `getEncoded()` 就是共享密钥本体，把它 base64 发出去等于公开签名能力（`JwkController.java:20-23`；`JwtTool.java:81-83`；网关白名单 `JwtProperties.java:39-40`） |

---

## 8. 快速上手路径

**想跑起来**（详见 `README.md`、`DEPLOYMENT.md`）：

```bash
# 1) 构建（judge-worker 依赖沙箱镜像，镜像构建脚本见 scripts/build-sandbox-images.py）
mvn clean install -DskipTests

# 2) 启动全部后端服务（按依赖顺序，最终打印网关入口）
python scripts/dev-start-backend.py --wait
#    网关 http://localhost:9080 ；接口文档 http://localhost:9080/doc.html

# 3) 判题机多实例（可选，压测用）
CJ_MQ_CONSUME_THREADS=4 python scripts/dev-start-backend.py --extra judge-worker=9185:9285 --wait
```
证据：`scripts/dev-start-backend.py:14-32,60-69,152-175`；`README.md:175,193-202`。

**想读懂代码，建议按这个顺序**：

1. `judge-submission` 的 `SubmissionService.submit()` → `createSubmissionWithTask()`（受理、幂等、投递）。
2. `judge-worker` 的 `SubmissionTaskHandler.handle()` → `JudgeEngine.judge()`（消费、认领、判题主流程）。
3. `judge-worker` 的 `DockerSandbox.runContainer()`（沙箱隔离参数）。
4. `judge-submission` 的 `JudgeCompensationService`（故障转移 / 滞留重发 / 死信 / 队列对账）。
5. `judge-contest` 的 `ContestRankService` + `lua/contest_rank_update.lua`（榜单更新与封榜）。
6. `judge-ai` 的 `ReviewService.streamReview()` + `ReviewContextService` + `KnowledgeVectorRepository`（SSE 与 RAG）。
7. `judge-gateway` 的 `AuthGlobalFilter` + `judge-common` 的 `UserInfoInterceptor`（身份透传）。
8. `judge-auth` 的 `AccountService.issue()` + `Capabilities`（登录与能力码）。

**验证脚本**（仓库 `scripts/` 下）：`verify-p1-login.py`、`verify-p2.py`、`verify-p3.py`、`verify-p4.py`、`verify-p5.py`、`verify-p6.py`、`verify-authz.py`。

**观测入口**：`/actuator/prometheus`（含 `judge_queue_backlog` / `judge_dead_tasks` / `judge_workers_online`，README.md:274）；Grafana 面板见 `deploy/monitoring/grafana/dashboards/`。

---

## 附录：本文与既有文档的已知差异

| 项 | 本文结论（以代码为准） | 既有文档表述 |
|---|---|---|
| 判题任务投递通道 | 只走 MQ；Redis ZSet 仅作积压指标 | `ARCHITECTURE.md:128` 称「Redis ZSet + RocketMQ 双通道」 |
| JWT 签名算法 | HMAC 对称签名（`hmacShaKeyFor` + `CJ_JWT_SECRET`） | `ARCHITECTURE.md:14,86` 称「RS256 + JWKS」 |
| `/jwks` 端点 | 存在但**无人消费**，且泄露 HMAC 密钥本体（见 §4.4，待修） | `JwkController` 注释称「供网关验签」，`ARCHITECTURE.md:86` 称 RS256 + JWKS —— 两处都与代码不符 |

> 若这些差异属于有意演进，请同步更新对应文档，避免新同学被误导。
