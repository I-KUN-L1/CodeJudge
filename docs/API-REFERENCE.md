# CodeJudge 接口参考

> 统一入口：`http://127.0.0.1:9080`（judge-gateway）。
> **所有接口都经网关访问**，不要直连下游服务 —— 下游不解析 JWT，只认网关注入的
> `user-info` / `role-info` 头；直连时这两个头缺失，一律 401。

---

## 0. 通用约定

### 0.1 统一响应体

```json
{ "code": 200, "msg": "OK", "data": { }, "requestId": "..." }
```

| 字段 | 说明 |
|---|---|
| `code` | **`200` 为成功**（不是 `1`），失败时为业务错误码（如 `401` / `403` / `429` / `500` 或自定义码） |
| `msg` | 可展示给用户的提示（**字段名是 `msg`，不是 `message`**） |
| `data` | 业务数据，失败时通常为 `null` |
| `requestId` | 链路标识，与响应头及日志一致 |

> ⚠ **HTTP 200 不等于业务成功**。网关正常转发时 HTTP 一律 200，成败只看响应体的 `code`。
> 压测断言与前端判断都必须看 `code`：
>
> - JMeter：断言「响应体含 `"code":200`」（注意是 `:200` 不是 `:1`）；
> - 前端：`judge-web/src/api/http.js` 的响应拦截器按 `body.code === 200` 判定，
>   成功则直接返回 `data`，失败则抛 `Error(msg)` 并把业务码挂在 `err.code` 上。

### 0.2 分页

请求参数（`PageQuery`）：`pageNo`（默认 1）、`pageSize`（默认 10，上限 200）、`sortBy`、`isAsc`。

响应（`PageDTO<T>`）：

```json
{ "code": 1, "data": { "total": 42, "pages": 3, "list": [ ] } }
```

> ⚠ 参数名是 **`pageNo` / `pageSize`**，不是 `page` / `size`。写错不会报错，
> 只会静默使用默认值 —— 表现为「传了 50 条却只返回 10 条」。

### 0.3 鉴权

| 项 | 说明 |
|---|---|
| 认证头 | `Authorization: Bearer <accessToken>` |
| 令牌算法 | RS256，公钥经 `GET /jwks` 暴露 |
| 网关白名单 | `/accounts/login`、`/accounts/admin/login`、`/accounts/refresh`、`/accounts/password/first-change`、`/students/register`、`/jwks`、`/v3/api-docs`、`/doc.html` |
| 下游可见身份 | 网关注入 `user-info: <userId>`、`role-info: <roleId>` |
| 角色 | `1` 管理员（员工）｜ `2` 学员 ｜ `3` 教师 |

### 0.4 服务与端口

| 服务 | 端口 | 路由前缀 |
|---|---|---|
| judge-gateway | 9080 | — |
| judge-auth | 9081 | `/accounts/**` `/menus/**` `/roles/**` `/privileges/**` `/jwks/**` |
| judge-user | 9082 | `/users/**` `/students/**` `/teachers/**` `/staffs/**` |
| judge-problem | 9083 | `/problems/**` `/tags/**` `/test-cases/**` |
| judge-submission | 9084 | `/submissions/**` `/workers/**` |
| judge-worker | 9085 | 无 HTTP 接口（MQ 消费者） |
| judge-contest | 9086 | `/contests/**` `/ws/contests/**` |
| judge-ai | 9087 | `/ai/**` |

---

## 1. 认证（judge-auth）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/accounts/login` | 匿名 | 学员/教师登录。**有令牌桶限流**：2 req/s，突发 5 |
| POST | `/accounts/admin/login` | 匿名 | 管理员登录（独立入口，便于审计） |
| POST | `/accounts/refresh` | 匿名（携 refreshToken） | 刷新 accessToken |
| POST | `/accounts/password/first-change` | 匿名 | 首次登录强制改密 |
| POST | `/accounts/logout` | 登录 | 登出（清 Redis 会话） |
| GET | `/jwks` | 匿名 | 公钥集（供其它服务验签） |

**登录请求**

```json
POST /accounts/login
{ "cellPhone": "13900000001", "password": "123456" }
```

**登录响应**

```json
{ "code": 1, "data": {
  "accessToken": "eyJ...", "expireTime": 1758432000000,
  "refreshToken": "eyJ...", "userId": 2001, "username": "student001" } }
```

### RBAC（管理端）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/menus`、`/menus/{id}`、`/menus/parent/{pid}` | 菜单树/详情 |
| GET | `/menus/me` | 当前用户可见菜单 |
| POST / PUT / DELETE | `/menus`、`/menus/{id}` | 菜单维护 |
| POST / DELETE | `/menus/role/{roleId}` | 角色-菜单绑定 |
| GET / POST / PUT / DELETE | `/privileges`、`/privileges/{id}` | 权限点维护 |
| GET | `/privileges/options/{menuId}` | 按菜单取权限点 |
| POST / DELETE | `/privileges/role/{roleId}` | 角色-权限绑定 |
| GET / POST / PUT / DELETE | `/roles`、`/roles/{id}`、`/roles/list` | 角色维护 |

---

## 2. 用户（judge-user）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/students/register` | 匿名 | 学员注册（**type 强制为 2**，忽略入参） |
| PUT | `/students/password` | 学员 | 修改本人密码 |
| GET | `/students/page` | STAFF | 学员分页（`keyword` 支持姓名/用户名/手机号） |
| POST | `/teachers/register` | **STAFF** | 教师开号（已取消匿名自助注册） |
| GET | `/teachers/page` | STAFF | 教师分页 |
| GET | `/staffs/page` | STAFF | 员工分页 |
| GET | `/users/me` | 登录 | 当前用户信息 |
| GET | `/users/{id}` | 登录 | 用户详情 |
| GET | `/users/page` | STAFF | 用户分页 |
| POST / PUT / DELETE | `/users`、`/users/{id}` | STAFF | 用户维护 |
| PUT | `/users/{id}/status/{status}` | STAFF | 启停用（1 启用 / 0 停用） |
| PUT | `/users/{id}/password/default` | STAFF | 重置为默认密码 |
| GET | `/users/bootstrap/admin-exists` | 匿名 | 是否已有管理员（引导页用） |
| POST | `/users/bootstrap/admin` | 匿名（仅首次） | 创建首个管理员 |
| GET | `/users/checkCellphone` | 匿名 | 手机号是否已注册 |
| GET | `/users/stats/total` | 登录 | 用户总数（前端看板） |

**注册请求体**（`UserFormDTO`）

```json
{ "cellPhone": "13900010000", "password": "123456", "name": "压测学员1000", "username": "load001000" }
```

校验：手机号 `^1\d{10}$`、密码 ≥ 6 位。字段全集：
`id, cellPhone, username, password, name, type, status, icon, email, city, gender`。

---

## 3. 题目（judge-problem）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/problems/page` | 登录 | 列表。学员自动只见已发布且属于自己的可见范围 |
| GET | `/problems/{id}` | 登录 | 详情（含题面、模板代码、标签） |
| GET | `/problems/{id}/versions` | 登录 | 题面历史版本 |
| POST | `/problems` | STAFF | 建题，返回题目 id |
| PUT | `/problems/{id}` | STAFF+归属 | 改题 |
| PUT | `/problems/{id}/status/{status}` | STAFF+归属 | 0 草稿 / 1 已发布 / 2 已下线 |
| DELETE | `/problems/{id}` | STAFF+归属 | 删题 |
| GET | `/problems/{id}/test-cases` | STAFF | 用例列表（**含隐藏用例**） |
| POST | `/problems/{id}/test-cases` | STAFF | 追加用例，返回条数 |
| PUT | `/problems/{id}/test-cases` | STAFF | 整体替换 |
| PUT / DELETE | `/test-cases/{caseId}` | STAFF | 单条用例维护 |
| GET | `/tags` | 登录 | 标签列表 |
| POST / DELETE | `/tags`、`/tags/{id}` | STAFF | 标签维护 |

**建题请求**（`ProblemFormDTO`）

```json
{
  "title": "A + B Problem", "difficulty": 1,
  "timeLimitMs": 1000, "memoryLimitMb": 256, "status": 1,
  "tagIds": [1, 2],
  "statement": "## 题目描述...", "inputSpec": "...", "outputSpec": "...", "hint": "...",
  "templateCode": { "java": "public class Main {...}", "cpp": "#include <bits/stdc++.h>", "python": "import sys" }
}
```

> ⚠ `templateCode` 的键是**小写语言名**（`java` / `cpp` / `python` / `go`），
> 与提交接口的 `language`（大写枚举 `JAVA`/`CPP`/`PYTHON`/`GO`）不是同一套口径。
> 前端按 `language.toLowerCase()` 取模板。

**用例**（`TestCaseFormDTO`）：`seq, stdin, expectedStdout, isHidden, score, timeLimitMs, judgeMode`

**内部接口**（不经网关，仅供 judge-worker / judge-ai 调用）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/internal/problems/{id}/judge-info` | 判题判据（含隐藏用例期望输出） |
| GET | `/internal/problems/summaries` | 批量题目标题摘要 |

---

## 4. 提交与判题（judge-submission）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/submissions` | 登录 | 提交代码（异步判题，立即返回 id） |
| GET | `/submissions/{id}` | 登录+归属 | 提交详情（含逐用例结果） |
| GET | `/submissions/page` | 登录 | 分页；学员强制只看自己的 |
| POST | `/submissions/{id}/rejudge` | STAFF | 重判 |
| GET | `/workers` | 管理员 | 在线判题机列表 |
| GET | `/workers/metrics` | 管理员 | `{queueBacklog, deadTasks}` |

**提交请求**（`SubmissionFormDTO`）

```json
{ "problemId": 4001, "contestId": null, "language": "PYTHON",
  "code": "import sys\ndata = sys.stdin.read().split()\nprint(sum(int(x) for x in data))" }
```

- `language` 取值：`JAVA` / `PYTHON` / `CPP` / `GO`（源码文件名分别为 `Main.java` / `main.py` / `main.cpp` / `main.go`）
- `code` ≤ 32KB
- `contestId` 为 null 视为普通提交

**提交响应**

```json
{ "code": 1, "data": { "id": 1234567890, "verdict": "PENDING", "idempotent": false } }
```

> **幂等语义**：同一用户 + 同一题目 + 同一竞赛 + **同一代码哈希** 的重复提交，
> 会命中 Redis 快路径或 `uk_submission_idempotent` 唯一索引，直接返回已有记录并置
> `idempotent=true`。
> ⚠ 压测时必须让每次提交的代码不同（如加 `# ${__UUID()}` 注释），
> 否则压的是幂等查询路径，而非判题链路。

**评测结果**：`AC`（通过）/ `WA`（答案错）/ `TLE`（超时）/ `MLE`（内存超限）/
`RE`（运行错误）/ `CE`（编译错误）/ `SE`（系统错误）

**内部接口**

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/internal/submissions/contest/{contestId}/results` | 竞赛判题结果（供 judge-contest 算榜） |
| GET | `/internal/submissions/{id}/review-context?maskHidden=` | AI 点评上下文。⚠ 学员调用必须 `maskHidden=true`，否则可套出隐藏用例期望输出 |

---

## 5. 竞赛与排行榜（judge-contest）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/contests/page` | 登录 | 列表（`keyword` / `status` / `registeredOnly`） |
| GET | `/contests/{id}` | 登录 | 详情（含题目列表） |
| POST | `/contests` | STAFF | 建赛 |
| POST | `/contests/{id}/register` | 学员 | 报名 |
| GET | `/contests/{id}/rank` | 登录 | 排行榜 |
| POST | `/contests/{id}/freeze` | STAFF | 封榜 |
| GET | `/contests/{id}/snapshots` | STAFF | 榜单快照列表 |
| POST | `/contests/{id}/rank/rebuild` | STAFF | 重建榜（数据修复用） |
| GET | `/internal/contests/{id}/context` | 内部 | 竞赛上下文 |

**建赛请求**（`ContestFormDTO`）

```json
{ "title": "秋季赛 #1", "description": "...", "rule": "ACM",
  "startTime": "2026-10-01T14:00:00", "endTime": "2026-10-01T18:00:00",
  "freezeMinutes": 60, "penaltyMinutes": 20,
  "problems": [ { "problemId": 4001, "label": "A", "displayOrder": 1, "fullScore": 100 } ] }
```

**WebSocket 端点**

| 路径 | 参数 | 说明 |
|---|---|---|
| `/ws/submissions/{id}` | `?token=<accessToken>` | 判题进度推送 |
| `/ws/contests/{id}/rank` | `?token=...`；内部视图加 `?full=true` | 榜单增量推送 |

> **`?full=true` 是内部视图**（可见封榜期间的真实排名），仅限内部/管理员使用。

---

## 6. AI 点评（judge-ai）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/ai/review/stream` | 登录 | **SSE 流式点评（推荐）** |
| GET | `/ai/review/stream` | 登录 | SSE 流式点评（调试用，参数在查询串） |
| POST | `/ai/review` | 登录 | 非流式点评（一次性返回） |
| GET | `/ai/review/{submissionId}` | 登录+归属 | 取该提交的点评 |
| GET | `/ai/review/detail/{reviewId}` | 登录+归属 | 按点评 id 取详情 |
| POST | `/ai/knowledge/upload` | STAFF | 上传知识（`problemId, sourceType, title, content, replace`） |
| POST | `/ai/knowledge/search` | STAFF | 向量检索（`query, problemId, topK, includeHistory`） |
| POST | `/ai/knowledge/preview` | STAFF | 切分预览（不落库） |
| GET | `/ai/knowledge/count` | STAFF | 知识条目数 |
| DELETE | `/ai/knowledge` | STAFF | 清空知识库 |

**点评请求**（`ReviewRequest`）

```json
{ "submissionId": 1234567890, "reviewType": 1, "question": "为什么我的二分写错了？" }
```

`reviewType`：`1` 错误诊断（默认）｜ `2` 主动点评 ｜ `3` 相似题推荐

### 6.1 SSE 事件契约

```
event: START      data: {"reviewId":123,"degraded":false,"model":"glm-4"}   ← seq=1
event: RETRIEVAL  data: {"sources":[...],"summary":"..."}                    ← seq=2
event: DELTA      data: {"text":"这段代码..."}                                ← seq=3..N
event: END        data: {"finishReason":"stop"}                              ← seq=N+1
```

**错误处理（务必注意）**：

| 时机 | HTTP | Content-Type | 形态 |
|---|---|---|---|
| 订阅建立**前**失败（未登录、参数非法） | 401 / 400 | `application/json` | 普通 `R{}` 响应体 |
| 订阅建立**后**失败（模型调用、检索失败） | **200** | `text/event-stream` | `event: ERROR` 事件 |

> 客户端**必须**同时处理两种：先判断 Content-Type，再在流内监听 `ERROR` 事件。
> 只看状态码会把「订阅后失败」当成成功。

**断线重连**：请求头携带 `Last-Event-ID: <seq>`，服务端只回放**尚未送达**的事件，
不重新生成内容（省 token）。按 `seq` 过滤，不是按数组下标。

---

## 7. 监控端点

所有服务暴露 `/actuator/prometheus`（Prometheus 抓取用，不经网关、不需 JWT）：

| 路径 | 可用端口 |
|---|---|
| `/actuator/health` | 9080–9087 |
| `/actuator/prometheus` | 9080–9087 |
| `/actuator/metrics` | 9080–9087 |

**自定义业务指标（judge-submission 提供）**

| 指标 | 含义 | 告警线 |
|---|---|---|
| `judge_queue_backlog` | 待判队列积压 | > 200 持续 5 分钟 |
| `judge_dead_tasks` | 死信任务数 | **> 0 即告警** |
| `judge_workers_online` | 在线判题机数 | == 0 持续 2 分钟 |

统一标签：`application`（服务名）、`tier`（edge/core/ai）。详见 `deploy/monitoring/`。
