# P3 交付报告 —— 提交服务 / 判题机 / Docker 沙箱

> 阶段：P3 ｜ 日期：2026-09-20 ｜ 状态：**已完成并验收通过**
> 范围：judge-submission(9084) + judge-worker(9085) + Docker 沙箱 + 判题链路（提交 → MQ → 判题执行 → 结果回写）

---

> ## 首次投递（Tag `CREATED`）缺失缺陷及修复
>
> **首次投递在提交主路径上整条缺失**：
> `SubmissionService.createSubmissionWithTask()` 在 `judgeTaskMapper.insert(task)` 之后直接返回，
> 从未调用 `JudgeEventPublisher.publishTaskCreated()`（该方法零调用点）。
>
> **实测后果**：任务只能等 `JudgeCompensationService` 的「滞留重发」兜底
> （`fixedDelay=10s` + `pending-rescue-delay-ms=15000` + `RETRY_DELAY_LEVEL=2`=5s），
> 判题端到端从 **~3.4s 恶化到 ~30s**（DB 实测 `update_time − submit_time` = 27 000/29 000/30 000 ms，
> 而同期沙箱内 `time_ms` 仅 23–30ms）。worker 侧日志 1158/1158 条均为 `source=RETRY`。
>
> **为何 P3/P6 验收未拦住**：`verify-p3.py` 断言的是「最终能出 AC」，**不设时限** ——
> 补偿路径让它照样通过（只是慢 10×）。属「假绿」家族：功能断言通过 ≠ 设计路径生效。
>
> **修复方式**（2026-09-22，1 行附加式，未改动任何既有逻辑）：
>
> ```java
> // SubmissionService.createSubmissionWithTask()，judgeTaskMapper.insert(task) 之后
> TxSupport.afterCommit(() -> eventPublisher.publishTaskCreated(submission.getId(), task.getId(), 0));
> ```
>
> 修复后实测：`投递判题任务：ok=true` 出现、worker 侧 `source=CREATED`、端到端 **3.4–4.5s**。
> 滞留重发**保留**为兜底（覆盖"MQ 发送失败"这一真实场景），二者是主路径与兜底的关系，不是二选一。
>
> 📌 连带更正：`docs/PERF.md` §3.3 的「单实例判题吞吐 ≈1.5 题/秒」是在该缺陷路径下测得的
> **积压排空速率**（被 10s 扫描周期限流），并非 worker 的真实处理能力 ——
> 该项待 1 vs 3 worker 对比轮次重新标定。

---

## 一、交付物总览

| 模块 / 资产 | 说明 |
|---|---|
| `judge-submission` (9084) | 提交受理（幂等双保险）、频控、judge_task 创建、CREATED/RETRY 投递、结果/详情查询、重判、/workers 集群视图、**补偿调度**（故障转移/滞留重发/死信兜底） |
| `judge-worker` (9085) | MQ 消费（CREATED/RETRY）、三重幂等防线、Docker 沙箱执行、判定引擎（AC/WA/TLE/MLE/RE/CE/SE）、结果回写（单写者 CAS）、RESULT 事件、心跳/负载上报 |
| `judge-api` 扩展 | `ProblemClient`（judge-info 内部契约）、`JudgeInfoDTO`/`JudgeCaseDTO`、`SubmissionTaskMessage`/`SubmissionResultMessage`、`Language` 枚举 |
| `judge-problem` 扩展 | `/internal/problems/{id}/judge-info`（InternalOnlyGuard 保护，隐藏用例唯一合法出口） |
| `judge-common` 扩展 | `JudgeRedisKeys`（判题域 Redis Key 全局约定）；MqTopics 沿用 P1 定义 |
| `sandbox/docker/` | 4 个判题镜像：`judge-java21` / `judge-python312` / `judge-gcc13` / `judge-go122` |
| `sandbox/seccomp/` | 判题专用 seccomp profile（禁 mount/ptrace/reboot/kexec 等 32 项危险 syscall） |
| `scripts/build-sandbox-images.py` | 4 镜像一键构建（.env 中 CJ_SANDBOX_IMAGE_* 决定 tag） |
| `scripts/dev-start-backend.py` | **新增** Python 启动器（绕开 Git Bash 进程回收 + PS5.1 Start-Process 的 Path/PATH 字典冲突两个实测故障） |
| `scripts/verify-p3.py` | 20 项端到端断言 |
| `scripts/verify-p3-failover.py` | 故障转移混沌测试 |

构建：`mvn clean install -DskipTests` → **8 模块全绿**（0 error）。

---

## 二、架构与状态机

### 2.1 链路与职责边界

```
用户 → 网关(9080) → judge-submission(9084)
                       │ 受理/幂等/落库 submission+judge_task
                       │ MQ: judge_submission + CREATED
                       ▼
                  judge-worker(9085)  ←—— 心跳/负载 ——→ Redis
                       │ 三重幂等防线 → CAS 认领(租约)
                       │ Feign → judge-problem /internal/.../judge-info（含隐藏用例）
                       │ Docker 沙箱：编译 → 逐用例执行 → 比对
                       │ DB 回写（judge_result/compile_info/submission/judge_task 终态）
                       │ MQ: RESULT 事件（P4 榜单/WS 消费） ｜ DLQ: judge_submission_dlq
                       ▼
                  judge-submission 补偿调度（每 10s）
                    ├─ 故障转移：JUDGING 且租约过期 → attempt+1 → RETRY
                    ├─ 滞留重发：PENDING 超时未认领 → 重投（覆盖 MQ 发送丢失）
                    └─ 死信兜底：DEAD 任务补投业务 DLQ（SETNX 去重）
```

- **judge-submission 只受理与调度，不执行判题**；**judge-worker 是判题库的单写者**（DB CAS 保证终态只前进不回退）。
- 题目/用例数据仅经内部 Feign（InternalOnlyGuard）流转；携带用户身份的请求一律 403。

### 2.2 判题任务状态机

`PENDING → JUDGING → SUCCESS（verdict=AC/WA/TLE/MLE/RE/CE）`
`　　　　　　　　↘（平台故障）attempt+1 → PENDING（重试）→ 超限 → DEAD + DLQ`

- 认领：`UPDATE ... WHERE status='PENDING' AND attempt=#{attempt}`（行级 CAS，at-least-once 重复投递只有一次生效）。
- 终态写入同样 CAS（`status IN ('PENDING','JUDGING')` + `lease_owner=me`），接管后的旧执行无法覆盖新执行。
- 提交状态机：`PENDING → JUDGING → SUCCESS | FAILED(SE)`。

### 2.3 幂等设计（两处）

**提交幂等**：Redis 快路径 `judge:submission:idempotent:{userId}:{problemId}:{contestId}:{codeHash}`（60s）+ 唯一索引 `uk_submission_idempotent`（最终防线，DuplicateKeyException 回查返回）。同码已终态的重复提交 = 轮次+1 落新行（正常业务）。

**判题幂等**（worker，三重）：attempt 比对 → DB CAS 认领（权威）→ Redis SETNX 任务锁（PLAN §4.2 契约，认领后补挂）。

### 2.4 沙箱隔离（12 项落地）

独立容器+独立 workdir、`--user 1000:1000`、`--read-only` 根文件系统、`--network none`、`--cpus/--memory/--memory-swap(禁swap)/--pids-limit`、容器内 timeout 精确计时 + worker 墙钟兜底强杀（→TLE）、容器 OOM(137)→MLE、非零退出→RE（先于 WA）、编译失败→CE（stderr 落库）、`no-new-privileges + cap-drop ALL + 自定义 seccomp` + 宿主目录仅只读投递、输出上限截断（容器协议截断 + worker 读取上限 + DB 列宽三道）、沙箱自身故障折叠为 SE 与用户代码结论严格区分。

输出协议：run.sh 以哨兵行区分受控元数据（`__CJ_META__`）与程序输出（`__CJ_STDOUT__`/`__CJ_STDERR__`）——程序伪造哨兵行只能污染展示摘要，不影响判定正确性。

---

## 三、验收实证（可复现）

### 3.1 六种 verdict 逐条复现（`python scripts/verify-p3.py`，20 项断言全 PASS）

| 断言 | 提交内容 | 结果 |
|---|---|---|
| A1 4001 → AC | Python 正确 A+B（4 用例含 2 隐藏，score=100） | AC 33ms 17MB |
| A2 4001 → WA | 错误解（a-b） | WA |
| A3 4002 → TLE | Python while True | TLE（内层 timeout 触发） |
| A4 4003 → MLE | 分配 10^7+ 数组（32MB 限制） | MLE |
| A5 4004 → RE | 下标越界 | RE |
| A6 4005 → CE | Java 语法错误 | CE（stderrLog 含 javac 报错原文） |

### 3.2 幂等与安全（7 项安全用例全部拦截）

- B1/B2：重复同码提交返回同一 submissionId，`idempotent=true`。
- D1 无限循环→TLE；D2 内存爆炸→MLE；D3 socket 外连（--network none）→RE；D4 写 /etc/passwd（只读根）→RE；D5 fork 炸弹（pids-limit=64）→RE；D6 输出爆炸→截断+终止（TLE/RE）；D6b 输出摘要长度有界（≤512 列宽）。
- C1：学员视角下隐藏用例（2 个）的输出/错误摘要为 null，仅教师/管理员可见。

### 3.3 故障转移混沌测试（`python scripts/verify-p3-failover.py`）

```
注入故障：task=JUDGING lease_expire_at<NOW() owner=dead-worker（等价 worker 被杀残留现场）
  轮询1：task=JUDGING  | 0 | dead-worker
  轮询2：task=PENDING  | 1 | NULL      ← 补偿调度 CAS 接管，attempt+1
  轮询3：task=JUDGING  | 1 | Sakura:9085 ← 在线 worker 重新认领
  轮询4：task=SUCCESS  | 1 | Sakura:9085 ← 重新判题完成
[PASS] 租约过期 → 补偿接管 → RETRY 重投 → 重新判题完成
```

### 3.4 集群视图（管理员）

`GET /workers` 返回心跳（`Sakura:9085`，TTL 30s）与在跑任务数；`GET /workers/metrics` 返回队列积压与死信数；学员访问 403。

---

## 四、本轮修掉的实测缺陷（均已闭环）

1. **MySQL PreparedStatement 不支持 `INTERVAL ? MILLISECOND`** —— 租约到期时间改为 Java 侧计算后作为参数传入（两个模块的 claim SQL）。
2. **Redis SETNX 预检锁放在认领之前会"锁死合法重投"** —— 一次消费异常后 TTL 内拦截全部重试。改为认领成功后补挂（DB CAS 本身已足够权威）。
3. **沙箱源码未落盘**（run.sh/stdin 有、main.py 没有 → 全部 RE）—— SandboxSpec 增加 sourceFile/sourceCode，由 DockerSandbox 统一写入。
4. **run.sh 协议不回传 stderr** —— 增加 `__CJ_STDERR__` 段（CE 诊断与 RE 定位依赖它）。
5. **摘要截断溢出 DB 列宽**（512+后缀 > VARCHAR(512) → 插入失败 → 误入死信）—— 截断上限降至 460。
6. **judge-problem 传递引入 data-redis**（judge-api P1 设计）导致 healthcheck 连 6379 判 DOWN —— application.yml 补 `spring.data.redis` 指向 6380。
7. **WorkerProperties 双重注册**（@Component + @EnableConfigurationProperties）→ 注入冲突启动失败 —— 收敛为单一注册。
8. **4 个 Dockerfile 的 uid 1000 幂等创建**（eclipse-temurin 自带 GID 1000，groupadd 直接失败）。
9. **dev-start-backend.ps1 两个宿主故障**：① 无 BOM 的 UTF-8 在 PS5.1 按 GBK 解析报语法错（已加 BOM）；② 宿主环境 Path/PATH 双写导致 Start-Process 字典冲突 —— 新增 `dev-start-backend.py`（DETACHED 进程 + `--wait` 看护模式）为**当前推荐启动方式**。

## 五、有意偏差（PLAN 对照）

| 偏差 | 理由 |
|---|---|
| worker 直连 judge_submission 库写结果（而非 Feign 回传） | 判题机是执行域单写者；避免大结果跨服务传输与 Feign 超时链 |
| 首个非 AC 用例短路（ACM 语义），score=AC 用例分值和 | P3 无赛制开关；IOI 全量计分留 P4 |
| judge_mode=2（特判）暂按精确比对 | 特判脚本引擎属 P5（AI/评测扩展），已在注释声明 |
| 业务 DLQ 消息体复用 SubmissionResultMessage | P3 无死信消费者；Dashboard 可查，重放工具留 P6 |
| PENDING 滞留重发代替完整本地消息表 | 补偿扫描 + Redis 防抖 + worker CAS 幂等已覆盖同等语义，复杂度更低 |

## 六、当前状态与下一步

- 服务常驻：`python scripts/dev-start-backend.py --wait`（看护模式）。
- **遗留 2 项（不阻塞 P4）**：
  1. broker 消息存储仍在容器可写层（未挂载卷，P1 已知取舍）；
  2. pids 耗尽类攻击（D5）容器内 shell 无法回传元数据，按约定 exit=255 → RE（已文档化，属判定语义而非缺陷）。
- P4 待授权：WebSocket（判题进度/竞赛榜推送）+ judge-contest(9086) + Redis ZSet 实时榜 + 封榜。
