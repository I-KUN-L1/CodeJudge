# CodeJudge 项目规划书（阶段 0 产出 · 零代码）

> 版本：v1.0 ｜ 日期：2026-09-20 ｜ 状态：待确认
> 本文档仅为规划，**未生成任何代码**。确认后再按阶段逐模块产出可编译代码。

---

## 0. 摘要与「提示词假设 vs 底座实况」修正表

规划前已实读 `D:\1\zx-learn` 源码（18 模块）。以下 **8 处**提示词中的假设与底座实况不符，若照抄会直接编译失败或埋下返工，故先修正：

| # | 提示词假设 | 底座实况（已核实） | 处置 |
|---|---|---|---|
| 1 | 包名 `com.zx.*` → `com.codejudge.*` | 实际根包是 **`com.zhixing.*`**（groupId `com.zhixing`） | 替换规则改为 `com.zhixing.* → com.codejudge.*`；groupId 改 `com.codejudge` |
| 2 | 统一异常 `BizException` | **不存在该类**。实为 `CommonException` + `ErrorCode` 家族：`BizIllegalException` / `BadRequestException` / `UnauthorizedException` / `ForbiddenException` / `AccountDisabledException` / `RequestTimeoutException` / `DbException` | 原样复用整套异常体系，不新造 `BizException` |
| 3 | 分页 `PageResult` | 实为 **`PageDTO` + `PageQuery`**（`com.zhixing.common.domain`） | 复用 `PageDTO`/`PageQuery` |
| 4 | 权限表 `permission` | 实际实体是 **`Privilege`**：表 `privilege` / `role_privilege` / `menu` / `role_menu` / `account_role` / `login_record` | 表名与实体沿用 `privilege` 体系 |
| 5 | `judge_auth.account` 表（账号密码在 auth 库） | **`zx_auth` 库没有 `account` 表**。账号/密码（BCrypt）在 `zx_user.user`，`auth` 只管 RBAC + `login_record`，登录时经 Feign `UserClient` 拉 `UserDTO` | 见 §3.1 决策 D1（推荐方案 A） |
| 6 | MQ Topic 用点号：`judge.submission.created` | 底座规范是 **`zx_xxx` 下划线前缀 + Tag 全大写**（`MqTopics`），且是**手写的 `rocketmq-client` 4.9 封装**（`RocketMQTemplate` / `RocketMQConsumerContainer` / `MqHandler` / `MessageCodec` / `RocketMQProperties`），**不是** spring-rocketmq / spring-cloud-stream | Topic 改为 `judge_submission` + Tag `CREATED/RETRY/RESULT`，见 §4.1 |
| 7 | RocketMQ 只需避让到 9877 | 只避让了 namesrv。**broker 端口 10909/10911/10912 会与 zx-learn 直接冲突** | 追加避让：broker 用 `10919/10921/10922`，见 §2.3 |
| 8 | 沙箱用 Docker / gVisor 均可 | 本机 Docker Desktop 29.7.2，**仅注册了 `runc` 系列运行时，无 `runsc`（gVisor）** | 默认走**加固 runc + seccomp/AppArmor/cgroup v2**，gVisor 作为可切换项（`SANDBOX_RUNTIME`），见 §6 |

**其他环境实况**：JDK 21.0.7 Temurin ✅ ／ Maven 3.9.6（`D:\1\apache-maven-3.9.6`）✅ ／ Docker Desktop 29.7.2（overlayfs, Linux VM, cgroup v2）✅ ／ Node v22.22.2 ✅ ／ **MinIO 底座中不存在，属新增** ／ 压测资产可复用 `zx-learn/perf-test/` 与 `D:\1\jmeter`。

---

## 1. 目录结构

```
D:\1\CodeJudge\
├── pom.xml                       # 聚合 + dependencyManagement（对齐 zx-learn 版本矩阵）
├── judge-common\                 公共底座（复制 zx-common 改造）
├── judge-api\                    Feign 契约 / DTO（复制 zx-api 改造）
├── judge-gateway\                网关 :9080
├── judge-auth\                   认证 :9081
├── judge-user\                   用户 :9082
├── judge-problem\                题目 :9083
├── judge-submission\             提交/判题调度 :9084
├── judge-worker\                 判题机 :9085（多实例 9085/9185/9285）
├── judge-contest\                竞赛/排行榜 :9086
├── judge-ai\                     AI 代码点评 :9087
├── judge-web\                    前端 Vue3 :5174
├── sandbox\
│   ├── docker\                   4 语言判题镜像 Dockerfile（java21 / python3.12 / gcc13 / go1.22）
│   └── seccomp\                  seccomp profile（禁网络、禁危险 syscall）
├── sql\
│   ├── init.sql                  6 库建库建表 + 种子数据
│   └── migrations\               后续增量 SQL（沿用 zx-learn 的日期命名习惯）
├── scripts\
│   ├── dev-start-backend.sh      / .ps1（含端口注入防护 + 沙箱环境自检）
│   ├── build-sandbox-images.sh   构建 4 个判题镜像
│   └── verify-core-chain.sh      提交→判题→WS 一键冒烟
├── docs\                         见 §7 交付物
├── perf-test\                    JMeter 脚本 + 报告模板
├── docker-compose.yml            MySQL/Redis/RocketMQ/PG+pgvector/MinIO
└── .env.example
```

**建议的结构微调（相对提示词）**：沙箱不单独拆服务，而是作为 `judge-worker` 内的 `com.codejudge.worker.sandbox` 包 + `SandboxExecutor` SPI（`DockerSandbox` / `RuncSandbox` / 未来 `GvisorSandbox`）。理由：沙箱是判题机的实现细节而非独立服务，拆成模块会白白多一层跨进程开销；用 SPI 保留"换运行时"的扩展点。

---

## 2. 模块职责与端口

### 2.1 模块职责

| 模块 | 端口 | 职责 | 关键复用 |
|---|---|---|---|
| judge-common | — | 底座：响应/异常/分页/链路追踪/JWT/Redis/雪花/常量/Feign 解码/MQ 封装/Jackson/Knife4j | zx-common 全量 |
| judge-api | — | Feign 契约 + DTO（判题领域） | zx-api 骨架 |
| judge-gateway | 9080 | 路由、JWT 双 Token 全局过滤、user-info 透传、白名单、限流、CORS、SSE 超时放宽 | zx-gateway 全量 |
| judge-auth | 9081 | 登录、刷新、RBAC、首管理员安全引导、首登强制改密、JWK | zx-auth 全量 |
| judge-user | 9082 | 学员/教师/管理员基础信息与详情 | zx-user 骨架 |
| judge-problem | 9083 | 题目/版本/测试用例/标签 CRUD、可见性隔离 | 新建（骨架复用） |
| judge-submission | 9084 | 提交落库+幂等、MQ 投递、结果查询、重判调度 | 新建 |
| judge-worker | 9085 | MQ 消费、沙箱执行、用例逐跑、结果回写、心跳、故障转移 | 新建 + 沙箱 |
| judge-contest | 9086 | 竞赛/报名、Redis ZSet 实时榜、封榜、快照 | 新建 |
| judge-ai | 9087 | LLM 点评、RAG 检索、SSE 流式 | zx-aigc 全量骨架 |
| judge-web | 5174 | 前端：题目/编辑器/提交/榜单/点评/管理端 | 新建 |

### 2.2 端口避让对照（已核验 zx-learn 占用 8080–8095）

| 组件 | zx-learn | CodeJudge | 说明 |
|---|---|---|---|
| 网关 / 认证 / 用户 / 题目 / 提交 / 判题机 / 竞赛 / AI | 8080–8089 | 9080–9087 | 整体上移到 9xxx 段，互不干扰 |
| 判题机多实例 | — | 9085 / 9185 / 9285 | `workerId` 由 `SERVER_PORT`+主机名派生 |
| 前端 | 5173 | 5174 | Vite dev server |
| MySQL | 3306 | 3307 | |
| Redis | 6379 | 6380 | |
| PostgreSQL+pgvector | 5432 | 5433 | |
| RocketMQ NameServer | 9876 | 9877 | |
| **RocketMQ Broker** | **10909/10911/10912** | **10919/10921/10922** | ⚠️ 提示词遗漏，必须同步改 broker.conf |
| Dashboard | 18080 | 18081 | |
| MinIO | — | 9000 / 9001 | 新增组件 |

### 2.3 服务发现策略
沿用底座：**本地默认关闭 Nacos**，用 `spring.cloud.discovery.client.simple.instances` 静态实例 + 网关 `GW_*_URI` 直连；生产置 `NACOS_ENABLED=true` 走注册中心。虚拟线程保持开启（`spring.threads.virtual.enabled=true`）。

---

## 3. 数据库设计

6 个库：`judge_auth` / `judge_user` / `judge_problem` / `judge_submission` / `judge_contest` / `judge_ai`。

### 3.1 决策 D1：账号密码放哪？（必须你拍板）

- **方案 A（推荐）**：完全沿用底座拆法 —— 账号/密码/状态/类型放 `judge_user.user`，`judge_auth` 只放 `role/menu/privilege/account_role/role_privilege/role_menu/login_record`，登录经 `UserClient`（Feign）校验。
  - 优点：auth 模块零改造，`AdminBootstrapRunner`、`JwtTool`、双 Token 全部即插即用；与 zx-learn 语义一致。
  - 代价：判题平台"账号"概念与"用户"合并（对判题场景完全够用）。
- **方案 B（提示词原案）**：judge_auth 新建 `account` + `refresh_token` 表，自己实现登录态。
  - 代价：需重写 `AccountService`、`AdminBootstrapRunner`、首登标记逻辑，Feign 调用链拆断，风险高。

> **默认按方案 A 执行**，若你选 B 请明确告知。

### 3.2 表清单

**judge_auth**
| 表 | 关键字段 | 备注 |
|---|---|---|
| role / menu / privilege | 照抄底座 | 实体 `Role/Menu/Privilege` |
| account_role / role_privilege / role_menu | 关联表 | 照抄 |
| login_record | 登录流水 | 照抄 |
| refresh_token | *仅方案 B* | 方案 A 下 refresh token 由 Redis `auth:refresh:{userId}` 承载 |

**judge_user**
| 表 | 关键字段 |
|---|---|
| user | id(雪花)、cell_phone、username、password(BCrypt)、name、type(1员工/2学员/3教师)、status、icon、email、city、gender、审计 5 字段 + `deleted` |
| user_detail | user_id、intro、school（**新增**，判题场景的学校字段）、signature(签名)、审计字段 |

**judge_problem**
| 表 | 关键字段 | 索引/约束 |
|---|---|---|
| problem | id、title、difficulty(1-5)、time_limit_ms、memory_limit_mb、status(0草稿/1已发布/2下线)、owner_id、submit_count、accepted_count、current_version_id、审计字段 | idx(status,difficulty)、idx(owner_id) |
| problem_version | problem_id、version_no、statement(markdown)、input_spec、output_spec、hint、template_code(JSON 多语言)、created_by | uniq(problem_id, version_no) |
| test_case | problem_id、seq、stdin、expected_stdout、is_hidden、score、time_limit_ms(可覆盖)、judge_mode(0精确/1浮点容差/2特判) | idx(problem_id,seq) |
| tag | name、type | uniq(name) |
| problem_tag | problem_id、tag_id | uniq(problem_id,tag_id) |

**judge_submission**
| 表 | 关键字段 | 索引/约束 |
|---|---|---|
| submission | id、user_id、problem_id、contest_id、language、code(或对象存储 path)、code_hash、status、score、time_ms、memory_kb、compile_info_id、submit_time | **uniq(user_id,problem_id,contest_id,code_hash,submit_round)** 幂等基石 |
| judge_task | submission_id、worker_id、status(PENDING/JUDGING/SUCCESS/FAILED)、attempt、max_attempt、lease_expire_at、lease_owner、timeout_ms、next_retry_at | idx(status,next_retry_at)、idx(lease_expire_at) 供故障转移扫描 |
| judge_result | submission_id、task_id、case_id、seq、verdict(AC/WA/TLE/MLE/RE/CE/SE)、time_ms、memory_kb、output_digest、stderr_digest | idx(submission_id,seq) |
| compile_info | submission_id、success、stdout_log、stderr_log、duration_ms | uniq(submission_id) |

**judge_contest**
| 表 | 关键字段 |
|---|---|
| contest | id、title、rule(ACM/IOI)、start_time、end_time、freeze_minutes、freeze_at、status、owner_id、penalty_minutes |
| contest_problem | contest_id、problem_id、label(A/B/C)、display_order、full_score、submit_count、accepted_count |
| contest_registration | contest_id、user_id、register_time、status、uniq(contest_id,user_id) |
| contest_rank_snapshot | contest_id、snapshot_at、rank_json(封榜/终榜快照) |

**judge_ai**
| 表 | 关键字段 |
|---|---|
| ai_review | id、submission_id、user_id、review_type(1错误诊断/2主动点评/3相似题)、model、prompt_digest、content(长文本)、tokens_in/out、status、create_time |
| knowledge_chunk | id、problem_id、source_type、chunk_no、content、embedding **`vector(1024)`**、create_time ｜ **HNSW 索引**（pgvector 上限 2000 维，与底座 `embedding-3`/1024 维严格对齐） |

### 3.3 种子数据（init.sql）
≥5 题（覆盖 AC/WA/TLE/MLE/RE/CE 可复现场景，含隐藏用例）、2 竞赛（1 场进行中+1 场已结束用于封榜演示）、默认 管理员/教师/学员 账号。
**首个管理员凭据不在 SQL 硬编码** —— 沿用底座 `AdminBootstrapRunner` 安全引导机制（凭据写入 `.bootstrap-credentials`，首登改密后自动删除）。

---

## 4. MQ Topic 与 Redis Key

### 4.1 Topic（按底座 `MqTopics` 规范重写）

| 常量 | Topic | Tag | 生产 → 消费 |
|---|---|---|---|
| `TOPIC_JUDGE_SUBMISSION` | `judge_submission` | `CREATED` | judge-submission → judge-worker |
| 同上 | `judge_submission` | `RETRY` | worker 自产自消（超时/异常重试） |
| 同上 | `judge_submission` | `RESULT` | worker → judge-submission / contest / WS |
| `TOPIC_JUDGE_DLQ` | `judge_submission_dlq` | `DEAD` | 超最大重试次数 |
| `TOPIC_AI_REVIEW` | `ai_review` | `REQUESTED` | judge-submission / 用户 → judge-ai |
| `TOPIC_CONTEST_RANK` | `contest_rank` | `CHANGED` | judge-submission → judge-contest（可选，或用 Redis 直更） |

> RocketMQ 4.9 原生 `%DLQ%{consumerGroup}` 会同时存在：**双轨**——原生 DLQ 兜底不丢消息，业务 `judge_submission_dlq` 承载可人工重放的补偿记录，便于可观测。
> 新增 Topic 必须登记到 `judge-common` 的 `MqTopics`，禁止散落硬编码（沿用底座约定）。

### 4.2 Redis Key

| Key | 类型 | TTL | 用途 |
|---|---|---|---|
| `judge:submission:idempotent:{submissionId}` | String | 60s | 提交幂等（配合 DB 唯一索引双保险） |
| `judge:judge:queue:zset` | ZSet | — | 待判队列表（score=提交时间），支撑队列长度指标 |
| `judge:task:lock:{taskId}` | String(SETNX) | 判题超时+30s | 判题幂等锁，防重复消费 |
| `judge:worker:heartbeat:{workerId}` | String | 30s | 心跳，过期即判定离线 |
| `judge:worker:load` | ZSet | — | worker 负载（在跑任务数），供负载拉取 |
| `judge:contest:rank:{contestId}` | ZSet | 竞赛结束后 24h | 实时榜，score 三段编码（见下方公式） |
| `judge:contest:rank:frozen:{contestId}` | ZSet | 同上 | 封榜冻结榜（对外只读它） |
| `judge:contest:user:{contestId}:{userId}` | Hash | 同上 | 逐题状态（field=problemId），罚时/总分由它**重算** |
| `judge:contest:user:frozen:{contestId}:{userId}` | Hash | 同上 | **封榜时刻的逐题状态副本**（对外冻结视图只读它，见 §4.2 注） |
| `judge:contest:lock:freeze:{contestId}` | String(SETNX) | 48h | 封榜幂等锁（多实例扫描只生效一次） |
| `judge:contest:push:dirty:{contestId}` | String | 略大于推送窗口 | 推送脏标记，仅运维观测 |
| `judge:problem:hot` | ZSet | — | 热门题目 |
| `auth:refresh:{userId}` | String | 7d | 刷新令牌（方案 A 下承载双 Token） |
| `judge:ws:session:{instanceId}:{userId}` | Set | 会话期 | WebSocket 会话索引（运维观测；推送靠 Redis 广播通道） |
| `judge:submission:rate:{userId}` | String(INCR) | 60s | 提交频控（网关限流配合） |

#### 排行榜 score 编码（P4 实现，**已修正原公式**）

```text
score = 权重 × 10^12 + (999999 − 罚时秒) × 10^6 + (999999 − 末次通过偏移秒)

关键字① 权重          ACM=通过题数 / IOI=总分      → 多者在前
关键字② 罚时秒        ACM=Σ(AC 前错误数 × 罚时分钟) + 各题 AC 耗时
                     IOI=末次得分时刻偏移          → 少者在前
关键字③ 末次通过偏移   同权重同罚时的最终裁决       → 早者在前
```

> ⚠️ **与本文档 v1.0 原公式的偏差（有意）**：原写的是
> `score = passed_count * 10^7 + (10^7 - 1 - penaltySeconds)`。该公式只有**两段**，
> 权重与罚时都相同时分值完全相同，ZSet 会退化为按 member（userId 字符串）排序 ——
> 即原文声称的"同分按最后 AC 时间"**实际做不到**。P4 按需求把第三关键字真正编码进去，
> 因此移位量由 `10^7` 改为 `10^12 / 10^6 / 10^6`。
>
> ⚠️ 精度边界：分值经 IEEE-754 double 传递，仅 ≤ 2^53 的整数可精确表示 → **权重须 ≤ 8999**。
> 常量定义见 `ContestRankService.WEIGHT_FACTOR / PENALTY_FACTOR / SEGMENT_BASE`，
> 与 `judge-contest/src/main/resources/lua/contest_rank_update.lua` 内同名常量**必须成对修改**。

#### 封榜一致性（三段式，P4 实现）

1. 实时榜**永远更新**（封榜只影响"读哪个榜"，不影响"写哪个榜"）；
2. 封榜瞬间用 `ZUNIONSTORE frozen 1 live` **单命令原子**生成冻结榜 —— 不存在拍到一半的中间态；
3. **逐题状态必须与冻结榜同批冻结**（`judge:contest:user:frozen:*`）。只冻结 ZSet 会留下
   "名次冻结、明细泄漏"的口子：`problemStatus` 读的是实时 Hash，封榜后有人通过题目时公开榜的
   单元格会从 `-1` 变成 `+`，等于公开宣布"封榜后谁过了题"。（P4 验收首轮被断言 D4 抓到并修复。）
4. 解封（竞赛结束）**不需要任何合并动作**：读回实时榜即完整结果，"封榜期间的成绩"从未丢过；
5. 冻结榜与终榜各落库一条快照（`contest_rank_snapshot`），使这份"当时的事实"不依赖 Redis 存活。

---

## 5. 核心接口清单（REST + WS）

统一约定：网关前缀路由（`/accounts/**`、`/users/**`、`/problems/**`、`/submissions/**`、`/contests/**`、`/ai/**`、`/workers/**`、`/ws/**`），全部返回 `R<T>` 或 `PageDTO<T>`；写操作经 JWT 全局过滤 + `RoleInterceptor` 角色校验 + `OwnerAccessGuard` 归属校验；`requestId` 全链路透传；Knife4j `/doc.html`。

| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| POST | `/accounts/login` | 公开 | 账密登录，返回双 Token |
| POST | `/accounts/refresh` | 公开 | 刷新令牌 |
| POST | `/accounts/password/first-change` | 已登录 | 首登强制改密 |
| GET | `/users/me` | 已登录 | 当前用户 |
| POST | `/problems` | 教师/管理员 | 建题（含模板代码） |
| PUT | `/problems/{id}` | 教师/管理员(归属) | 改题 → 生成新 `problem_version` |
| GET | `/problems/page` | 已登录 | 分页（隐藏用例不下发） |
| GET | `/problems/{id}` | 已登录 | 详情（含可见用例样例） |
| POST | `/problems/{id}/test-cases` | 教师/管理员(归属) | 用例管理（含隐藏） |
| POST | `/submissions` | 学员/教师 | 提交 → 返回 `submissionId` |
| GET | `/submissions/{id}` | 本人/教师/管理员 | 判题详情 + 逐用例结果 |
| GET | `/submissions/page` | 已登录 | 提交记录（按角色过滤可见范围） |
| POST | `/submissions/{id}/rejudge` | 教师/管理员 | 重判 |
| GET | `/workers` | 管理员 | 在线 worker + 负载 + 队列积压 |
| POST | `/contests` | 教师/管理员 | 建竞赛 |
| GET | `/contests/page` / `/contests/{id}` | 已登录 | 竞赛列表/详情 |
| POST | `/contests/{id}/register` | 学员 | 报名 |
| GET | `/contests/{id}/rank` | 已登录 | 排行榜（封榜后返回冻结榜，管理员可 `?full=true` 看全量） |
| POST | `/ai/review` | 已登录 | 触发 AI 点评（失败提交自动触发或手动） |
| GET | `/ai/review/stream` | 已登录 | **SSE** 流式返回点评 |
| GET | `/ai/review/{submissionId}` | 已登录 | 历史点评 |
| WS | `/ws/submissions/{submissionId}` | 本人 | 判题进度 / 最终结果 |
| WS | `/ws/contests/{contestId}/rank` | 已登录 | 排名变化推送 |

---

## 6. 沙箱设计要点（基于本机 runc-only 现实）

**运行时策略**：`SANDBOX_RUNTIME=runc`（默认，本机可用）｜`runsc`（gVisor，需另装，作为可选增强）。同一 `SandboxExecutor` SPI 下两套实现，不改上层代码。

**隔离清单（12 项要求逐条落地）**
1. 独立容器/进程命名空间，每次判题独立 `workdir`（挂载 tmpfs），结束即删。
2. `--user 1000:1000` 非 root。
3. 根文件系统 `--read-only`，`/tmp` 挂 `tmpfs,size=64m,nosuid,nodev,noexec`。
4. `--network none`。
5. `--cpus`、`--memory`+`--memory-swap`（相等即禁 swap）、`--pids-limit`（防 fork 炸弹）。
6. 超时强杀 → `TLE`（wall clock 优先，CPU time 兜底）。
7. OOM/超内存 → `MLE`（区分 `--memory` 触发与容器内 RLIMIT_AS）。
8. 非零退出码 → `RE`（先于 WA 判定）。
9. 编译阶段失败 → `CE`，`compile_info.stderr_log` 落库（截断保护）。
10. `--security-opt no-new-privileges` + 自定义 seccomp（禁 `mount/ptrace/reboot/kexec` 等）+ 不挂载任何宿主目录（仅只读投递代码）。
11. 输出上限（`--stdout` reader 截断 + 容器侧 `ulimit -f`），防输出爆炸。
12. 用例逐跑，单用例独立超时；`SE` 覆盖沙箱自身异常（Docker 不可用、镜像缺失）并与业务判定区分。

**4 语言镜像**：`judge-java21` / `judge-python3.12` / `judge-gcc13` / `judge-go1.22`，由 `scripts/build-sandbox-images.sh` 预构建（避免每判必编译镜像）。**镜像需一次本地构建，不可联网拉取时才可用 → 列为阶段 3 前置条件。**

**安全测试用例**（阶段 3 必过）：无限循环、读 `/etc/passwd`、开 socket、fork 炸弹、输出爆炸（无限打印）、内存爆炸、`Runtime.exec` 逃逸尝试。

---

## 7. 分阶段实施计划

每阶段**只产出一个可编译、可运行、可自测的增量**，附验收命令；上一阶段验收不过不进下一阶段。

| 阶段 | 内容 | 交付物 | 验收（硬标准） |
|---|---|---|---|
| **P1** | 初始化 + 底座复用（common/api/gateway/auth）+ 目录骨架 + `.env.example` + compose | 根 pom、judge-common、judge-api、judge-gateway(9080)、judge-auth(9081)、scripts 启动脚本、sql/init.sql（auth+user 库） | `mvn clean install -DskipTests` 通过；`docker compose up -d` 全基础设施健康；登录 `/accounts/login` 拿到双 Token；`/doc.html` 可开 |
| **P2** | 用户 + 题目管理 | judge-user(9082)、judge-problem(9083)、problem 相关 Feign + DTO、题目/用例 CRUD、种子 5 题 | 教师建题→学员列表可见→隐藏用例不下发；`OwnerAccessGuard` 拦截越权改题（**跨角色负例必测**） |
| **P3** | 提交 + MQ + worker + Docker 沙箱 | judge-submission(9084)、judge-worker(9085)、4 个沙箱镜像、幂等/重试/DLQ/故障转移 | 提交→`PENDING`→`JUDGING`→终态可达；6 种 verdict 可复现；**7 项安全用例全部拦截**；杀 worker 后任务被接管 |
| **P4** | WebSocket + 竞赛 + Redis 排行榜 | WS 端点、进度推送、judge-contest(9086)、ZSet 榜、封榜、快照 | 提交后 WS 秒级收到进度与结果；榜单实时更新；封榜后公开榜冻结、后台仍在记录、解封后正确合并 |
| **P5** | AI 代码点评 + RAG + SSE | judge-ai(9087)、`knowledge_chunk` + pgvector、Prompt 构造、SSE 流式 | `/ai/review/stream` 流式逐字返回；RAG 命中题目知识点；LLM 未配置时优雅降级（`ZX_LLM_ENABLED=false` 不报 500） |
| **P6** | 前端 + 可观测 + 压测 + 文档 | judge-web(5174) 6 类页面、Micrometer/Prometheus/Grafana、JMeter 报告、全套 docs | README 里的 curl 全链路可跑通；Grafana 面板出数；压测数据**真实测出**并写入 `docs/PERF.md` |

**依赖提醒**：P3 依赖 Docker 可用（已确认）+ 4 个镜像构建成功；P5 依赖 LLM Key（未提供时走降级路径，不阻塞）。

---

## 8. 复用 zx-learn 清单与改造点（逐项）

| 来源（zx-learn） | 目标（CodeJudge） | 复用内容（实测类名） | 必须删除 |
|---|---|---|---|
| `zx-common` | `judge-common` | `domain`: `R`/`PageDTO`/`PageQuery`/`BasePO`；`exceptions` 全 9 类 + `ErrorCode`；`advice` 3 个；`annotation`: `NoWrapper`/`RequireRole`；`interceptor` 3 个（`RequestIdInterceptor`/`UserInfoInterceptor`/`RoleInterceptor`）；`utils`: `SnowflakeIdGenerator`/`UserContext`/`WebUtils`/`AssertUtils`/`BeanUtils`/`CollUtils`/`StringUtils`/`TxSupport`/`OwnerAccessGuard`/`InternalOnlyGuard`/`CookieBuilder`；`config` 2 个；`jackson` 2 个；`mq` 6 个（RocketMQ 封装）；`feign.RDecoder`；`handler` 2 个；`constants` | 无业务实体需要删（common 本身干净）；`MqTopics` 中 `zx_order_paid` 等 5 个主题**全部替换**为 judge 主题 |
| `zx-api` | `judge-api` | 包结构 `client`/`dto`/`cache`/`config`；`RequestIdRelayConfiguration`（Feign 链路透传）；`RDecoder` 约定；`dto/user` 4 个 DTO（`LoginFormDTO`/`UserDTO`/`PasswordChangeDTO`/`BootstrapAdminDTO`）；`UserClient` + `FallbackFactory` | 删 `dto/course`、`dto/exam`、`dto/learning`、`dto/trade`、`constants/PointsSource`；删 client：`course`(5)/`exam`/`insight`/`learning`(2)/`message`/`pay`/`promotion`/`remark`/`search`/`trade`(2)/`aigc`；**新增** `problem`/`submission`/`contest`/`worker` 契约与判题 DTO |
| `zx-gateway` | `judge-gateway` | `AuthGlobalFilter`（JWT 双 Token + 白名单）、`JwtProperties`、`JwtUtils`、`GatewayErrorResponse`/`GatewayExceptionHandler`、`application.yml` 的 CORS 段（`allowedOriginPatterns` 覆盖 localhost+127.0.0.1，含踩坑注释）、`metadata.response-timeout: 900000` 的 SSE 路由写法、`httpclient` 超时与弹性连接池 | 删 `SeckillKeyResolver` → 替换为 `SubmitRateLimitKeyResolver`（按 userId+IP）；**重排 routes**：url 前缀换为 judge 前缀，并将 `GW_*_URI` 全部改 908x |
| `zx-auth` | `judge-auth` | `JwtTool`/`JwtConstants`/`LoginResultVO`/`FirstChangePasswordDTO`/`AdminBootstrapRunner`（首个管理员安全生成，凭据落 `.bootstrap-credentials`）/`AccountController`/`JwkController`/`RoleController`/`MenuController`/`PrivilegeController` + 7 个 Mapper + `AccountService` 骨架 | 删 `LoginRecord` 之外的课程/订单相关引用；DB 由 `zx_auth` → `judge_auth`；**待确认**：首登强制改密的标记位（`user` 表无 `first_login` 列）→ 见 §9 TODO-1 |
| `zx-aigc` | `judge-ai` | `LlmClient`（OpenAI 兼容）、`ChatService` + SSE 流式、`ChatMemory`/`RedisMessage`、`KnowledgeService`/`KnowledgeVectorRepository`（**自研 pgvector 检索，非 Spring AI VectorStore**）/`EmbeddingService`/`TextSplitter`/`SessionService`/`ToolRunner`、`LlmProperties`/`RagProperties`、`RoleGuardWebFilter`、`ChatEventVO`/`KnowledgeChunk`/`ChunkHit`、SSE 并发上限与降级策略、`agents` 框架（`AbstractAgent`/`RouteAgent`/`AgentType`/`ChatContext`） | 删 `BuyAgent`/`ConsultAgent`/`RecommendAgent`/`KnowledgeAgent` 等教育业务 Agent → 新增 `ReviewAgent`（复杂度/边界/优化建议）；删 `CourseTools`/`OrderTools` → 新增 `ProblemTools`；删 `AudioController`；PG 库 `zx_aigc` → `judge_ai` |
| `zx-user` | `judge-user` | 模块骨架、`UserController`、`User`/`UserDetail` PO + Mapper + `UserService`、`UserVO`/`UserFormDTO` | 删 `StudentController`/`TeacherController`/`StaffController` 的课程业务逻辑（保留角色化查询端点即可）；`user_detail` 增 `school`/`signature` |
| `scripts/dev-start-backend.{sh,ps1}` | `scripts/` | **端口注入防护**（`unset SERVER__PORT SERVER__HOST`，注释里详述了"宿主注入 SERVER__PORT 导致所有服务抢同一端口"的根因）、`java.io.tmpdir` 重定向到仓库内可写目录、Maven 自动探测、模块白名单启动 | 服务名数组换 judge-*；新增沙箱环境自检（Docker 可用性 + 4 镜像存在性） |
| `docker-compose.yml` | 同名 | 服务定义结构、镜像版本（mysql:8.0 / redis:7 / `pgvector/pgvector:pg16` / apache/rocketmq:4.9.7 / rocketmq-dashboard:2.0.0）、健康检查与命名卷写法 | 容器名 `zx-learn-*` → `codejudge-*`；端口按 §2.2 全量上移；**新增 MinIO**（9000/9001） |
| `.env.example` | 同名 | 变量分组与占位风格（`your-*-password`） | 前缀 `ZX_*` → `CJ_*`；新增沙箱/判题/竞赛相关变量；`ZX_USER_DEFAULT_PASSWORD` → `CJ_USER_DEFAULT_PASSWORD` |
| `docs/*` | `docs/` | `ARCHITECTURE`/`API-REFERENCE`/`DATABASE`/`DEPLOYMENT`/`CHANGELOG`/`ROADMAP`/`PERF`/`GO-LIVE-CHECKLIST`/`RAG-DEMO` 的结构与写法 | 内容全部重写为判题领域；`PERF.md` 作为压测报告模板保留空表待实测填入 |
| `perf-test/` | `perf-test/` | `run-perf.ps1`/`aggregate-csv.ps1`/`report-template.md`/`jmeter` 资产 | 场景改为提交接口、判题队列、榜单查询 |
| — | `sandbox/` | **全新**（底座无沙箱能力；底座 `deploy/` 只有 mysql/pgvector/redis/rocketmq 的初始化配置） | — |

**命名与规范统一**：包名 `com.zhixing.*` → `com.codejudge.*`；服务名 `zx-*` → `judge-*`、`application.name` 同步；artifactId/groupId 全部改名；**所有密码 / JWT 密钥 / LLM Key 走环境变量，仓库零硬编码**（沿用底座做法，`zx.jwt.secret: ${ZX_JWT_SECRET}` 这类占位符改为 `${CJ_JWT_SECRET}`）。

---

## 9. 风险与 TODO（不编造，待现场确认）

| # | 事项 | 影响 | 处置 |
|---|---|---|---|
| TODO-1 | 首登强制改密的标记位存在哪里（`zx_user.user` 表无 `first_login` 列，仅有 `FirstChangePasswordDTO` 与 `.bootstrap-credentials`） | auth P1 复用 | P1 现场读 `AccountService`/`AdminBootstrapService` 源码后确定；不猜测列名 |
| TODO-2 | Docker 是否能在沙箱终端内正常调用（`docker version` 在本会话有权限，但判题 worker 需长期高频创建容器） | P3 阻塞风险 | P3 首日做 `docker run --rm hello-world` + 一次 100 并发容器创建压测，确认可用 |
| TODO-3 | gVisor 未安装 | 沙箱强度 | 默认 runc + seccomp 加固；如需 gVisor，另附安装步骤并验证 |
| TODO-4 | LLM Key 未提供 | P5 | 沿用底座 `enabled=false` 降级路径，SSE 返回结构化降级提示而非 500 |
| TODO-5 | 判题代码存储：DB 存全文 还是 MinIO 存对象 | 提交表设计 | 默认两者并存（DB 存 ≤32KB 全文用于展示，超长转 MinIO 存 path）；P3 定稿 |
| TODO-6 | 竞赛罚时规则细节（ACM 20 分钟/次，IOI 按分） | 排行榜 | P4 前确认规则开关，ZSet score 编码已预留扩展位 |

**已知风险**：RocketMQ broker 端口若不同步改 `broker.conf`，会与 zx-learn 抢 10911 → broker 起不来（P1 必查）。Windows + Docker Desktop 下容器内 cgroup 限流基于 Linux VM，`--cpus`/`--pids-limit` 生效但精度略低于原生 Linux，压测数据需标注环境。

---

## 10. 验收标准映射（提示词 8 条 → 阶段）

| 验收标准 | 落地阶段 | 验证方式 |
|---|---|---|
| 1. `mvn clean install -DskipTests` 成功 | P1 | 命令行 |
| 2. `docker compose up -d` 启动全部基础设施 | P1 | 健康检查 + 端口探测 |
| 3. 最小链路可启动（gateway/auth/user/problem/submission/worker） | P2–P3 | `scripts/dev-start-backend.sh judge-gateway ...` |
| 4. 登录→建题→提交→判题→WS 收结果 | P3–P4 | `scripts/verify-core-chain.sh` |
| 5. 沙箱拦截无限循环/读文件/开网络/fork 炸弹 | P3 | 7 项安全用例脚本 |
| 6. 排行榜实时更新 + 封榜 | P4 | 并发提交 + 封榜/解封断言 |
| 7. AI 点评 SSE 流式返回 | P5 | `curl -N` 观察增量输出 |
| 8. README 中 curl 可直接验证核心链路 | P6 | 逐条实跑 |

---

## 请确认

1. **§3.1 决策 D1**：账号密码走方案 A（推荐）还是 B？
2. **§1 结构微调**：沙箱内置于 judge-worker（推荐）还是独立 `judge-sandbox` 模块？
3. **§9 TODO-5**：判题代码存储策略是否按默认（DB 全文 + 超长转 MinIO）？
4. 确认后即进入 **P1**，产出：根 pom + judge-common + judge-api + judge-gateway + judge-auth + scripts + sql/init.sql + docker-compose.yml + .env.example（全部可编译可运行）。
