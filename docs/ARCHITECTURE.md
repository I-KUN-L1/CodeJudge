# CodeJudge 架构说明

> 分布式在线编程评测平台。本文说明**系统边界、模块职责、关键链路与设计取舍**。
> 接口清单见 [`API-REFERENCE.md`](./API-REFERENCE.md)，部署与配置见 [`DEPLOYMENT.md`](./DEPLOYMENT.md)，
> 阶段进度与硬约束见 [`CONTEXT.md`](./CONTEXT.md)。

---

## 1. 系统全景

```
                        ┌──────────────────────────────┐
     浏览器 / JMeter ───▶│  judge-gateway  :9080        │
                        │  · JWT 校验（RS256 + JWKS）  │
                        │  · 路由转发（静态实例，无 Nacos）│
                        │  · CORS / requestId 透传     │
                        │  · WebSocket 握手（?token=） │
                        └──────┬───────────────────────┘
                               │
     ┌───────────┬─────────────┼─────────────┬────────────┬───────────┐
     ▼           ▼             ▼             ▼            ▼           ▼
 :9081       :9082         :9083          :9084        :9086       :9087
judge-auth  judge-user  judge-problem  judge-submission judge-contest judge-ai
 登录/令牌   用户/RBAC     题目/用例        提交/队列/WS     竞赛/榜单/WS  AI点评/SSE
                                                │                        │
                                     ┌──────────┴──────────┐             │
                                     ▼                     ▼             ▼
                            ┌────────────────┐   ┌──────────────────────────────┐
                            │ RocketMQ 4.9.7 │   │ PostgreSQL 16 + pgvector     │
                            │ judge_submission│   │ judge_ai（向量知识库）        │
                            └───────┬────────┘   └──────────────────────────────┘
                                    │ 消费
                                    ▼
                            judge-worker :9085（可多实例）
                            拉任务 → Docker 沙箱执行 → 回写结果
                                    │
                            ┌───────┴────────────────────────┐
                            │ codejudge/judge-java21          │
                            │ codejudge/judge-python312       │
                            │ codejudge/judge-gcc13           │
                            │ codejudge/judge-go122           │
                            └─────────────────────────────────┘

  共享基础设施：MySQL 8 :3307 ｜ Redis 7 :6380 ｜ MinIO :9000
```

**服务数量**：8 个可运行服务（`9080`–`9087`）。加上基础设施共 12 个容器/进程。

### 为什么没有 Nacos
底座（zx-learn）默认关闭 Nacos，网关用 `SimpleDiscoveryClient` 静态实例 + `GW_*_URI` 直连，
服务间 Feign 也走静态 `url` 配置。理由：本地与单机房部署下，注册中心的收益（动态扩缩容发现）
远小于它的运维成本（多一个必须健康的组件、启动顺序耦合、健康检查抖动导致的路由丢失）。
**代价**：新增实例必须改配置并重启网关。判题机多实例（9085/9185/9285）靠 MQ 消费组天然分流，
不需要服务发现。

---

## 2. 模块职责

| 模块 | 端口 | 职责 | 关键实现 |
|---|---|---|---|
| `judge-common` | — | 公共库：统一响应、异常体系、用户上下文、守卫注解、MQ 封装、WS 信封、雪花 ID、Redis 序列化 | 被所有服务依赖；`R<T>` / `PageDTO` / `CommonException` |
| `judge-api` | — | 跨服务契约：`Language` 枚举、判题进度/结果消息体 | 只放 DTO，无逻辑；worker 与 submission 共用 |
| `judge-gateway` | 9080 | 统一入口：JWT 校验、路由、CORS、`user-info`/`role-info` 头注入、WS 握手鉴权 | `AuthGlobalFilter` + `JwtProperties` 白名单 |
| `judge-auth` | 9081 | 登录（学员/管理员）、令牌刷新、JWKS 公钥、首次登录强制改密、登出 | RS256 签发；账号密码不在本库，经 Feign 查 `judge_user.user` |
| `judge-user` | 9082 | 用户 CRUD、学员/教师/员工分角色管理、首管理员引导、RBAC 菜单与权限 | `judge_user.user`(BCrypt)；`UserFormDTO` 强校验 |
| `judge-problem` | 9083 | 题目、题面版本快照、测试用例（含隐藏用例）、标签 | 内部接口 `/internal/problems/**` 供判题机取用例 |
| `judge-submission` | 9084 | 提交落库、幂等控制、判题任务下发、判题进度 WS 推送、判题机集群视图 | Redis ZSet 队列 + RocketMQ 双通道；`WorkerViewService` |
| `judge-worker` | 9085 | 判题执行：拉任务 → Docker 沙箱编译运行 → 逐用例比对 → 回写结果 | 无 Controller；MQ 消费 + Redis 心跳（TTL 30s） |
| `judge-contest` | 9086 | 竞赛、报名、实时排行榜、封榜/解封、榜单快照、榜单 WS 推送 | Redis ZSet 三段编码 score；Lua 原子更新 |
| `judge-ai` | 9087 | AI 代码点评（SSE 流式）、RAG 向量知识库、多轮追问 | **WebFlux + Netty**（全项目唯一响应式服务） |

---

## 3. 关键链路

### 3.1 登录与鉴权

```
客户端 ──POST /accounts/login──▶ 网关（白名单放行，不校验 JWT）
                                   │
                                   ▼
                              judge-auth
                                   │ Feign: UserClient.getByCellPhone()
                                   ▼
                              judge-user（查 judge_user.user，BCrypt 校验）
                                   │
                            RS256 签发 accessToken（含 userId/roleId claim）
                                   ▼
                            返回 { accessToken, refreshToken, userId, username }
```

之后每个请求在网关校验 JWT，把 `userId`/`roleId` 写入 **`user-info` / `role-info` 请求头**下发。
下游服务通过 `UserContext`（ThreadLocal）读取，因此**下游服务不解析 JWT**。

> ⚠ `judge-ai` 是 WebFlux，`UserContext`（ThreadLocal）在响应式线程模型下必然为空，
> 所以它改用 `AiIdentity.from(ServerHttpRequest)` 直接读 `user-info` 头，
> 并且**不启用** `InternalOnlyGuard` / `OwnerAccessGuard` —— 这两个守卫在 WebFlux 下
> 会把所有外部请求误判为内部调用而放行。

### 3.2 提交 → 判题 → 回传（核心链路）

```
① POST /submissions
   ├─ 幂等快路径：Redis key judge:submission:idempotent:{userId}:{problemId}:{contestId}:{codeHash}
   ├─ 落库 judge_submission + judge_task（status=0 待判）
   ├─ 双通道投递：Redis ZSet（judge:judge:queue:zset，低延迟）+ RocketMQ judge_submission:CREATED（可靠）
   └─ 立即返回 submissionId（异步，不阻塞用户）

② judge-worker（可多实例）
   ├─ Redis 心跳 judge:worker:heartbeat:{workerId}（TTL 30s，过期即视为离线）
   ├─ SETNX 任务锁（必须在 DB CAS 认领成功之后再挂，否则会出现「锁住了但没人执行」）
   ├─ 拉题目判据 GET /internal/problems/{id}/judge-info（含隐藏用例）
   ├─ Docker 沙箱：编译 → 逐用例运行（CPU/内存/时间限制）
   └─ 回写结果 + 投递 judge_submission:RESULT

③ 进度推送
   └─ WebSocket /ws/submissions/{id}：START → 逐用例 CASE → END（含 verdict）
```

**判题结果语义**：`AC / WA / TLE / MLE / RE / CE / SE`。

### 3.3 竞赛排行榜

```
参赛者提交 → judge-contest 通过内部接口取结果 → 计算 score
           → Lua 脚本原子更新 ZSet judge:contest:rank:{contestId}
           → WebSocket /ws/contests/{id}/rank 广播增量
```

**score 三段编码**（一维 ZSet 表达「过题数优先 → 罚时少优先 → 越早通过越好」）：

```
score = 权重 × 10^12 + (999999 − 罚时秒) × 10^6 + (999999 − 末次通过偏移秒)
        └─ 过题数   ┘   └──── 罚时（越少越大） ────┘   └──── 达成时间（越早越大） ────┘
权重 ≤ 8999：保证 权重×10^12 不超过 double 的安全整数位（2^53）
```

**封榜**：封榜瞬间对每个用户的 ZSet 做一次原子快照（`judge:contest:user:frozen:*`），
再用 `ZUNIONSTORE` 生成冻结榜。解封时无需合并计算 —— 这是「封榜一致性」的关键：
实时榜照常更新，冻结榜是独立副本，两者互不干扰。

### 3.4 AI 点评（SSE 流式）

```
POST /ai/review/stream  （Accept: text/event-stream）
  │
  ├─ 事件序列：START → RETRIEVAL → DELTA×N → END
  ├─ 错误走**事件**不走状态码：
  │    订阅前失败 → application/json + R{401}
  │    订阅后失败 → text/event-stream + event:ERROR （HTTP 仍 200）
  └─ 断线重连：Last-Event-ID 按 **seq** 过滤回放（不按下标）

RAG 检索：智谱 embedding-3 → pgvector（vector(1024) + HNSW）
内部契约：GET /internal/submissions/{id}/review-context?maskHidden=
          ⚠ 学员调用必须 maskHidden=true，否则可借 AI 问出隐藏用例的期望输出
```

---

## 4. 数据存储分工

| 存储 | 端口 | 承载 | 选型理由 |
|---|---|---|---|
| MySQL 8 | 3307 | `judge_auth` / `judge_user` / `judge_problem` / `judge_submission` / `judge_contest` | 事务性数据；MyBatis-Plus 生态 |
| Redis 7 | 6380 | 判题队列 ZSet、幂等 key、worker 心跳、竞赛榜 ZSet、任务锁 | 榜单与队列都是排序/原子计数场景 |
| PostgreSQL 16 + pgvector | 5433 | `judge_ai`（`knowledge_chunk` / `ai_review` 的 embedding） | MySQL 无原生向量索引；HNSW 上限 2000 维 |
| RocketMQ 4.9.7 | 9877 / 10919·10921·10922 | 判题链路 topic `judge_submission`（Tag：CREATED/RETRY/RESULT） | 削峰 + 重试 + 死信 |
| MinIO | 9000 | 超长代码、题目附件 | — |

> **为什么 PG 只给 judge-ai 用**：向量检索是它的独占需求。把整库迁到 PG 会牵动全部模块
> 且无收益；MySQL + PG 并存只多一个容器，边界清晰。

---

## 5. 横向机制

| 机制 | 实现位置 | 说明 |
|---|---|---|
| 统一响应 | `judge-common` `R<T>` | `{code, msg, data, requestId}`；**`code=200` 为成功**（不是 1），判定走 `R.success()` |
| 异常体系 | `CommonException` + `ErrorCode` | 无 `BizException`；`BadRequest` / `Unauthorized` / `Forbidden` / `AccountDisabled` / `DbException` |
| 分页 | `PageDTO` + `PageQuery` | **无 `PageResult`** |
| 鉴权守卫 | `OwnerAccessGuard`（归属）、`InternalOnlyGuard`（内部接口防外呼）、`RequireRole` | judge-ai 不适用（见 §3.1） |
| 链路标识 | `requestId` 透传 | 网关生成，全链路日志可串 |
| ID 生成 | 雪花 ID | 跨库唯一，避免自增暴露业务量 |
| 配置 | 环境变量前缀 `CJ_*` | **零硬编码**：密码 / JWT 密钥 / LLM Key 全部外部注入 |
| 监控 | Micrometer → `/actuator/prometheus` | 8 服务全量暴露；`application` 标签统一；见 `deploy/monitoring/` |

---

## 6. 端口规划（避让 zx-learn）

本机同时存在 zx-learn（8080–8095 / 3306 / 6379 / 5432 / 9876 / 10909 / 10911 / 10912 / 18080），
CodeJudge 整体换段：

| 用途 | zx-learn | **CodeJudge** |
|---|---|---|
| 服务端口段 | 8080–8095 | **9080–9087**（判题机多实例 9085/9185/9285） |
| 前端 | — | **5174** |
| MySQL | 3306 | **3307** |
| Redis | 6379 | **6380** |
| PostgreSQL | 5432 | **5433** |
| RocketMQ namesrv | 9876 | **9877** |
| RocketMQ broker | 10909/10911/10912 | **10919 / 10921 / 10922** ⚠ 最易漏改 |
| RocketMQ Dashboard | 18080 | **18081** |
| MinIO | — | 9000 / 9001 |
| Prometheus | — | **9090** |
| Alertmanager | — | **9093** |
| Grafana | 3000 | **3001** |

> ⚠ RocketMQ broker 的端口必须与 `deploy/rocketmq/broker.conf` 的 `listenPort=10921`
> 及 `fastRemotingPort=10919` / HA `10922` 严格一致，改一处不改另一处会导致 broker 起不来。

---

## 7. 设计取舍记录

| 决策 | 取舍 |
|---|---|
| 关闭 Nacos | 省一个必须健康的组件；代价是新增实例要改配置 |
| `judge-common` **不引入** Redisson | P3/P4 的并发场景已由 MySQL 唯一索引 / MQ 消费组 / Redis ZSET 覆盖，不需要分布式锁。触发重引入的条件：出现跨服务的「先查后改」临界区 |
| 判题走「Redis 队列 + MQ」双通道 | 队列给低延迟、MQ 给可靠性；代价是两条路径都要幂等，靠 `judge_task` 状态 CAS 收敛 |
| 教师**不做**「申请—审核」流程 | 管理员已有 `POST /teachers/register` 与 `POST /users` 两条开号路径，无产品缺口 |
| 取消匿名教师自助注册 | 保留 STAFF-only 收紧（行为变更，见 `P1-遗留项处置.md`） |
| SSE 错误走事件不走状态码 | 订阅建立后 HTTP 头已发出，无法再改状态码；客户端必须处理 `ERROR` 事件 |
| PG 表插入用 `INSERT ... RETURNING id` | PG 驱动在 `RETURN_GENERATED_KEYS` 下返回整行，`KeyHolder.getKey()` 抛异常 → 静默数据损坏（P5 实测缺陷） |
