# CONTEXT.md —— 新会话冷启动必读

> **这份文档的唯一目的**：让一个**全新的对话**（或换了一台机器、重新 clone 仓库之后）能在 30 秒内接上 CodeJudge 项目，不需要重新勘察、不需要你重贴提示词。
>
> 最后更新：2026-09-21 ｜ 维护规则：**每完成一个阶段必须回来更新 §4 与 §5**。

---

## 1. 一句话项目定位

CodeJudge —— 分布式在线编程评测平台（判题 / 竞赛 / AI 代码点评）。
**做法**：从 `D:\1\zx-learn` 复制底座改造，**不修改 zx-learn 原仓库**，不带入课程/订单/优惠券/学情/秒杀业务代码。

---

## 2. 新会话必读顺序（按序读，不要跳）

| 顺序 | 文件 | 读它解决什么 |
|---|---|---|
| 1️⃣ | **本文件** | 当前进度、硬约束、下一步 |
| 2️⃣ | `docs/PROMPT-ARCHIVE.md` | **总提示词的需求条款**（含原文缺失声明，别去别处找原文） |
| 3️⃣ | `docs/PLAN.md` | 规划书 v1.0：目录结构、端口、6 库表设计、MQ/Redis 键、接口清单、沙箱 12 项隔离、复用清单（逐类名）、风险 TODO |
| 4️⃣ | `docs/P1-REPORT.md` | P1 交付与验收实证、复用映射、有意偏离、踩坑 |
| 5️⃣ | `docs/P1-改造说明.md` | **目录结构、包名/端口变更点、底座类清单**（结构层速查） |
| 6️⃣ | `docs/P2-REPORT.md` | **P2 交付：用户域+题目域关联关系、5 表字段语义、逐接口 curl、20 项验收实证、本轮修的 4 个缺陷** |
| 7️⃣ | `docs/P3-REPORT.md` | **P3 交付：判题链路、沙箱 12 项隔离、六种 verdict、故障转移** |
| 8️⃣ | `docs/P4-REPORT.md` | **P4 交付：WS 推送、竞赛/封榜、ZSet 三段编码、60 项验收实证、本轮修的 8 个缺陷**（含 ZSet 编码相对 PLAN 的修正说明） |
| 9️⃣ | `docs/P5-REPORT.md` | **P5 交付：judge-ai 响应式栈取舍、SSE 事件契约、pgvector 双路召回、底座复用映射、46 项端到端验收实证、捕获的 PG 主键回填缺陷（§7.6）** |
| 🔟 | `docs/P5-前端SSE接入说明.md` | **前端如何接流式点评**（为何不用 EventSource、两类错误的分流、增量渲染策略、Last-Event-ID 重连） |
| 1️⃣1️⃣ | `docs/P1-遗留项处置.md` | **P1 三项待拍板项的处置结论 + 实测证据**（教师注册/Redisson/登录实测） |
| 1️⃣2️⃣ | `README.md` | 快速启动、curl 验证、FAQ |
| 1️⃣3️⃣ | `docs/LAUNCH-READINESS.md` | **上线放行单**（阻断项/建议项/已完成/命令清单/已知限制/回滚点）—— 若当前任务是「能不能上线」，优先读这份 |
| 1️⃣4️⃣ | `docs/ADR-001-服务发现选型.md` | **为什么禁用 Nacos**（实测证据 / 适用场景 / 五维分析 / 推翻条件 / 技术债）—— 凡涉及服务发现、网关路由、横向扩容，先读这份 |

> ⚠️ **总提示词原文并未落盘**（任何检索都无法复原）—— 请以本文档为唯一权威来源，不要在别处寻找原文。

---

## 3. 绝不违反的硬约束（21 条速查）

1. **包名** `com.zhixing.*` → `com.codejudge.*`（⚠️ 底座根包是 `com.zhixing`，**不是** `com.zx`）；groupId 同为 `com.codejudge`。
2. **服务名** `zx-*` → `judge-*`，`spring.application.name` 同步。
3. **零硬编码**：所有密码 / JWT 密钥 / LLM Key 走环境变量，前缀 `CJ_*`（底座为 `ZX_*`）。
4. **端口**：服务 9080–9087（判题机 9085/9185/9285），前端 5174，MySQL 3307，Redis 6380，PG 5433，namesrv 9877，**broker 10919/10921/10922**（易漏！zx-learn 占 10909/10911/10912），Dashboard 18081，MinIO 9000/9001。
5. **保留底座代码风格**：`R<T>` 统一响应、`CommonException`+`ErrorCode` 异常体系（**无 `BizException`**）、`PageDTO`+`PageQuery`（**无 `PageResult`**）、`requestId` 透传、MyBatis-Plus、Knife4j、雪花 ID。
6. **归属校验**用 `OwnerAccessGuard`，内部接口用 `InternalOnlyGuard`，角色用 `@RequireRole`。
7. **MQ 用底座手写封装**（`RocketMQTemplate`/`RocketMQConsumerContainer`/`MqHandler`/`MessageCodec`/`MqTopics`），**不是** spring-rocketmq；Topic 命名下划线 + Tag 大写；新 Topic 必须登记进 `MqTopics`，禁止散落硬编码。
8. **服务发现默认关闭 Nacos**：用 `SimpleDiscoveryClient` 静态实例 + 网关 `GW_*_URI` 直连；生产置 `NACOS_ENABLED=true`。
9. **工作方式**：每阶段**只产出一个可编译、可运行、可自测的增量**，附验收命令；**上一阶段验收不过不进下一阶段**。
10. **本机 `find` 命令被 Windows `FIND.EXE` 抢占** → 脚本里禁用 `find`，用 Glob/Grep。
11. **灌 SQL 必须带字符集声明** —— `docker exec <ctr> mysql < file.sql` 在 LANG 未设置时客户端字符集退化为 latin1，会把 UTF-8 **双重编码**。两个 sql 文件顶部已内置 `SET NAMES utf8mb4;`，**不要删掉那一行**；手工执行时也建议加 `--default-character-set=utf8mb4`。
12. **`test_case` / `problem_tag` / `tag` 的删除必须走物理删除**（`deletePhysically*` 方法）—— 它们的唯一键不含 `deleted` 列，逻辑删除会保留行，导致 seq 与标签**无法复用**（"删了序号就再也加不回去"）。`problem` / `problem_version` 仍用逻辑删除（雪花 id 不复用）。
13. **宿主会向子进程注入 `SERVER__PORT` / `SERVER__HOST`（已实测确证）** —— 本机 IDE 终端下 JVM 环境含 `SERVER__PORT=56298`（= 宿主自身监听端口），Spring 松散绑定将其解析为 `server.port`，**优先级高于 `application.yml`**，导致服务绑错端口启动失败。绕开启动脚本直接 `java -jar` 时二选一：① `unset SERVER__PORT SERVER__HOST SERVER_PORT SERVER_HOST`；② 显式传 `--server.port=<端口>`（优先级最高，最稳）。`scripts/dev-start-backend.{sh,ps1}` 已内置清变量逻辑。
14. **`judge-ai` 是唯一的响应式（WebFlux/Netty）服务** —— 因此在该服务内**禁止**使用
    `UserContext`（ThreadLocal + Servlet 拦截器填充，WebFlux 下**恒为 null**）、
    `InternalOnlyGuard` / `OwnerAccessGuard`（其规则是"无 `user-info` 头 ⇒ 判定为内部 Feign 直连 ⇒ 放行"，
    在 WebFlux 里会让**所有外部请求被误判为内部调用而放行**）。身份一律从请求头经
    `AiIdentity.from(ServerHttpRequest)` 显式取，归属校验在 `ReviewContextService` 内实现。
    另：`judge-ai` 的 `application.yml` **必须**显式写 `spring.main.web-application-type: reactive`
    —— knife4j 会把 `spring-webmvc` 带进类路径，而 `jakarta.servlet` 被排除，
    类路径推断会得出 `WebApplicationType.NONE`（服务"启动成功"却**不监听端口**，无任何报错）。
15. **PostgreSQL 表插入后要取自增主键，一律用 `INSERT ... RETURNING id`** —— **禁止**
    `Statement.RETURN_GENERATED_KEYS` + `KeyHolder.getKey()`。PG 驱动在该模式下把**整行所有列**
    都作为 key 返回，`getKey()` 随即抛 `InvalidDataAccessApiUsageException: ... contains multiple keys`；
    MySQL 驱动只返回主键列，故**从 MySQL 项目照搬的代码必然踩中**。
    后果是「**INSERT 成功却拿不到 id**」：记录写进表但永远停在 `status=0`、事件不带 `reviewId`、
    非流式接口返回空 body —— **不抛错、只丢数据**，编译与静态审视都发现不了。
    （P5 端到端验收实测捕获，详见 `docs/P5-REPORT.md` §7.6。）
16. **验收脚本里 `all(...)` / `not any(...)` 形式的断言，必须同时断言样本非空** ——
    `all()` 对空序列返回 `True`，零事件时恒真，会把「全链路失效」装饰成「全绿」（**假绿**比 FAIL 更危险）。
    P5 首次实跑即修掉 3 处这类假绿（`F1`/`F2`/`G1`/`G4`）。
17. **判题机多实例必须同时限并发：`CJ_MQ_CONSUME_THREADS`，且「实例数 × 该值 ≤ 宿主机 CPU 核数」** ——
    默认 20 消费线程 = 20 个并发 `docker run`；三实例 60 并发时**容器冷启动本身**就超过沙箱墙钟预算
    （`wallClockMs = 时限 + CJ_SANDBOX_WALL_GRACE_MS`，实测 6000ms），**正确解会被墙钟兜底强杀判成 TLE**
    （实测 3 实例：抽样 18/25 假 TLE，日志 `沙箱墙钟超时强杀` 48 次）。
    ⚠️ 且**加实例并不提升吞吐**：本机实测 1 实例 0.94 题/s、3 实例 0.96 题/s（瓶颈是每题 5 个容器的启停）。
    **扩容判题能力的正确方向是减少每题容器数，不是加实例。** 详见 `docs/PERF.md` §3.7。
18. **登录只有 `POST /accounts/login` 一个入口，角色由账号自身 `user.type` 决定** ——
    前端不提供「我是什么角色」的选择，后端也不接受该参数（`loginAuto()` 内部固定 `staffOnly=false`）。
    旧端点 `/accounts/admin/login` 保留为**兼容别名**（额外要求账号为员工，已标 `@Deprecated`，
    且**不得退化成第二套独立逻辑** —— 它只是 `login` 多一道门槛，签发同样的 token/cookie）。
    ⚠️ 收敛前该端点**不在网关登录限流谓词内**（谓词是 `Path=/accounts/login` + `Method=POST`），
    等于管理员入口完全没有爆破保护；收敛后缺口消失。**不要再造第二个登录入口。**
    ⚠️ refresh cookie 按角色二选一（`judge-refresh-token` / `judge-admin-refresh-token`），
    登录时写一枚必须**清掉另一枚**，否则续签读到过期身份 →「刚登录就被踢下线」。
19. **按钮级权限一律走「能力码」** —— 由 `judge-auth` 的 `Capabilities` 以 `user.type` 为**唯一输入**
    计算，经 `GET /accounts/me/capabilities` 下发；前端只用 `v-perm` 指令与 `user.can(code)` 查表，
    **不得出现任何「角色 → 能做什么」的推导**（`isStaff` / `canManage` / `meta.roles` / `USER_TYPES` 已全删）。
    `Capabilities.of()` 对未知 `user.type` 返回**空集**（fail-closed：拿不准就不给按钮，**不是给满** ——
    给满会在 type 出现脏值时把管理面按钮画给学员）。能力集是严格嵌套 学员 ⊂ 教师 ⊂ 员工（6/16/20）。
    RBAC 六表（`role`/`menu`/`privilege`…）**无种子数据、不参与鉴权**，仅保留为管理面 CRUD；
    启用它会引入 `user.type` 与 `account_role` 双源，一旦漂移就表现为「按钮画了但接口 403」。
    守卫：`python scripts/verify-authz.py`（56 项，含**前端静态断言**：源码中不得残留角色判定、不得外显默认账密）。
20. **首个管理员凭据文件是相对路径，按进程工作目录解析** —— 两种受支持的启动方式都把 cwd 固定为
    仓库根 ⇒ 规范落点 `<仓库根>/.bootstrap-credentials`。`AdminBootstrapService` 启动时
    **无条件打印解析后的绝对路径**（只打路径、不打口令）。手工 `java -jar` 必须自己保证 cwd，
    否则凭据落到别处、且 `isBootstrapPending()` 静默为 `false`（登录响应的 `mustChangePassword` 提示随之消失）。
    历史踩坑：`judge-auth/` 下留下一份含旧弱口令 `123456` 的**孤儿**凭据文件而无人察觉。
21. **启动顺序只有三条硬边**：① 基础设施先于任何服务；② **`judge-user` 先于 `judge-auth`**；③ 网关最后。
    ② 的原因：auth 的首个管理员引导（`ApplicationRunner`）一启动就经 Feign 调 user 问「有没有管理员」，
    顺序反了异常会被 catch 吞成一行 error →「**服务全绿、健康检查全过，却没有管理员账号也没有凭据文件**」。
    聚合入口是 `python scripts/start-all.py`，它**复用** `dev-start-backend.py` 的派生逻辑（不复制，
    避免两处真相），并按真实依赖序逐个等待 `/actuator/health`。

---

## 4. 当前进度

| 阶段 | 内容 | 状态 |
|---|---|---|
| P1 | 底座复用（common/api/gateway/auth/user）+ 骨架 + 基础设施 | ✅ **已完成并验收通过**（2026-09-20） |
| P2 | judge-problem(9083) 五表 CRUD + 隐藏用例隔离 + 种子 6 题；judge-user 分页补齐 | ✅ **已完成并验收通过**（2026-09-20，20 项断言） |
| P3 | judge-submission(9084) + judge-worker(9085) + Docker 沙箱 + 判题全链路 | ✅ **已完成并验收通过**（2026-09-20，20 项断言 + 故障转移混沌测试，详见 `docs/P3-REPORT.md`） |
| P4 | WebSocket + judge-contest(9086) + Redis 排行榜 | ✅ **已完成并验收通过**（2026-09-20，60 项断言 + 封榜一致性闭环，详见 `docs/P4-REPORT.md`） |
| P5 | judge-ai(9087) + LLM 点评 + pgvector RAG + SSE | ✅ **已完成并验收通过**（2026-09-21，46 项断言 + 网关端到端；捕获并修复 1 个静默数据损坏缺陷，详见 `docs/P5-REPORT.md` §7.5/§7.6） |
| P6 | judge-web(5174) + 可观测 + 压测 + 文档 | ✅ **已完成并验收通过**（2026-09-21，98 项断言 + 真实判题闭环 + 真实监控容器 + 真实压测；修复 1 个队列积压缺陷与 1 处全项目契约错误，详见 `docs/P6-REPORT.md`） |

> 🎉 **六个阶段全部交付**：8 个可运行服务（9080–9087）+ 前端（5174）+ 监控栈（9090/9093/3001）
> + 4 个沙箱镜像 + 压测体系 + 完整文档。上线前事项见 `docs/DEPLOYMENT.md` 生产检查清单
> 与 `docs/P6-REPORT.md` §8。

### P5 交付实证（详见 `docs/P5-REPORT.md`）
- `mvn -o clean install -DskipTests` → **11 模块全绿**（新增 judge-ai）
- 制品核对：`spring-webflux` + `reactor-netty` 在、**`jakarta.servlet` 完全缺失**、无 tomcat-embed-core、无 mybatis/mysql → 响应式栈成立
- **`python scripts/dev-start-backend.py judge-ai --wait` → `Netty started on port 9087`，5.9s 启动**，TCP 9087 LISTENING
- **8 项运行时契约实测通过**（无任何基础设施条件下）：
  无身份头→401 JSON · 合法身份头+上游不可用→`SSE ERROR{503}`+`END{ERROR}` · POST 形态同构 ·
  非流式→JSON 503 · 学员访问知识库→403 · **畸形身份头→401（非 500）** · 缺 submissionId→`ERROR{400}` · 帧格式符合规范
- 新增密钥事实：**P5 起实际可运行服务 = 8 个**（9080–9087）；`judge-ai` 是唯一响应式服务
- ⚠️ **`scripts/verify-p5.py`（A–M 段）尚未执行** —— Docker 起不来（`npipe:////./pipe/docker_engine` 连接失败，沙箱拦截 GUI/服务启动），MySQL/Redis/PG/MQ 全未运行。环境恢复后需补跑

### P4 验收实证（可复现，详见 `docs/P4-REPORT.md`）
- `mvn clean install -DskipTests` → **10 模块全绿**（1m05s）
- 7 个服务 9080–9086 全部 `/actuator/health` **UP**（**注意：P4 起实际是 7 个可运行服务**，多出 judge-contest:9086）
- **`python scripts/verify-p4.py` → 60 项断言全 PASS / 0 FAIL / 0 SKIP**（耗时约 6 分钟，F 段需等短赛程竞赛自然结束）
  - A 建赛与生命周期 6 · B 报名与提交校验 6 · C ZSet 榜单与同分规则 13 · D 封榜与视图隔离 10 · E WebSocket 12 · F 自动封榜/解封 9 · G 终榜重建 3
  - 关键实测值：ZSet 原始编码 `member=2002 score=1998110998110`；罚时差额 `1168s = 1200 + ΔAC时刻(−32s)`；封榜期间明细致命断言 `{'A': '-1'}`（未泄漏）；WS 推送版本 `4 → 5` 单调递增
- MQ 订阅已收窄：contest `[RESULT]` / worker `[CREATED,RETRY]` / submission `[PROGRESS,RESULT]`，重启后 0 条丢弃告警
- 起服务仍用：`python scripts/dev-start-backend.py --wait`（看护模式常驻；**P4 起含 judge-contest**）

### P3 验收实证（可复现，详见 `docs/P3-REPORT.md`）
- `mvn clean install -DskipTests` → 8 模块全绿
- 4 个沙箱镜像已构建：`codejudge/judge-{java21,python312,gcc13,go122}:latest`（`python scripts/build-sandbox-images.py`）
- **`python scripts/verify-p3.py` → 20 项断言全 PASS**：六种 verdict 逐条复现（4001 AC/WA、4002 TLE、4003 MLE、4004 RE、4005 CE-Java）、提交幂等、隐藏用例摘要遮蔽、7 项安全用例拦截、/workers 集群视图（管理员凭据走环境变量 `CJ_P3_ADMIN_PHONE/CJ_P3_ADMIN_PASS`）
- **`python scripts/verify-p3-failover.py` → 故障转移闭环 PASS**：租约过期 → 补偿接管(attempt+1) → RETRY 重投 → 在线 worker 重新判题完成
- 服务启动（当前推荐）：`python scripts/dev-start-backend.py --wait`（看护模式常驻）

### P1 验收实证（可复现）
- `mvn clean install -DskipTests` → BUILD SUCCESS，34s，0 error / 0 warning
- `docker-compose up -d` → 6 容器 running（mysql/redis/pg healthy，broker `boot success`）
- MySQL 5 库 **22 表**；种子 5 学员 + 2 教师（密码 `123456`）+ 10 标签
- PG `knowledge_chunk.embedding` = `vector(1024)`
- 服务 9080 / 9081 / 9082 health **UP**（db + redis UP）
- 无 token `/users/me` → 401；伪造 `user-info` → 401；`/teachers/register` → 401（已加固）
- `/doc.html` 在 9081 / 9082 返回 200（网关不聚合文档，属正常）
- **`python scripts/verify-p1-login.py` → 8 组 43 项断言全 PASS**（登录双 Token / 刷新 / 网关白名单 / 教师注册加固 / 改密 fail-closed；需 9080+9081+9082 已启动）

### P1 遗留的 3 个待确认项 → ✅ **已全部闭环**（2026-09-20，详见 `docs/P1-遗留项处置.md`）
1. **【行为变更】教师注册收紧为 STAFF-only —— 保留收紧，不回退。** 理由：教师是特权角色（可建题、可见隐藏用例），
   且管理员本就有两条开号路径（`POST /teachers/register`、`POST /users`），不存在产品缺口。
   "教师申请—审核"流程**不做**（无前端/审核台，属 P6 独立需求）。已端到端验证 7 项断言。
2. **judge-common 保持移除 Redisson。** P3/P4 的并发场景（提交幂等 / 任务分配 / 排行榜）分别由
   **MySQL 唯一索引 / MQ 消费组 / Redis ZSET 原子操作**覆盖，均不需要分布式锁。
   重新引入的触发条件已写明（需可重入+看门狗续期的复合临界区、或 Redisson 特有数据结构）。
   静态扫描：全仓仅剩 1 处注释性提及，无依赖与代码引用。
3. **`POST /accounts/login` 双 Token 下发 —— 已实测通过。** 改用脚本 `scripts/verify-p1-login.py`
   （凭据走环境变量，命令行不出现明文），**8 组 43 项断言全绿**，覆盖登录/负例/刷新/网关端到端/白名单/改密 fail-closed。
   ⚠️ 两点未做（有意）：首次改密的**成功路径**（会改写管理员密码并删除凭据文件，等你决定何时执行）、登录限流 429 压测（属 P6）。

### P2 验收实证（可复现）
- `mvn clean install -DskipTests` → BUILD SUCCESS，42s，**6 模块**全绿
- judge-problem(9083) 启动，`/actuator/health` → `{"status":"UP","db":{"status":"UP"}}`
- 种子幂等：首灌与重放后均为 **6 题 / 17 用例（11 隐藏）/ 12 标签关联**
- `template_code` JSON 列 `JSON_VALID=1`；中文 `CHAR_LENGTH` 全部正确
- **`python scripts/verify-p2.py` → 20 项断言全 PASS**（可见性隔离 + 归属越权负例）

### ✅ P2 顺带修掉的 4 个缺陷（**都影响 P1 已有代码，属行为变更**）
1. **中文种子双重编码** —— `docker exec mysql < file.sql` 在 LANG 未设时客户端字符集退化为 latin1，把 UTF-8 双重编码（`数组` 存成 `æ•°ç»`）。**P1 的学员/教师姓名、简介、10 个标签名、所有表 COMMENT 全中招**。已在 `sql/init.sql` / `sql/seed.sql` 顶部加 `SET NAMES utf8mb4;` 免疫载入方式，并清理重灌。
2. **actuator 端点被统一响应体包装** —— `/actuator/health` 返回 `{"code":200,...,"data":{"status":"UP"}}` 而非原生契约。会让 **Docker/K8s healthcheck 判不健康、Prometheus 抓不到指标**（P6 集中爆发）。已让 `WrapperResponseBodyAdvice` 跳过 `/actuator`。**影响全部 5 个 Servlet 服务。**
3. **唯一键不含 `deleted` 导致逻辑删除锁死复用** —— `test_case`/`problem_tag`/`tag` 三表改走**物理删除**（否则"删了序号就再也加不回""取消标签后打不回去"）。
4. 清理 2 处 zx-learn 业务注释残留（`OwnerAccessGuard` 的学情/答题记录举例）。

### P2 遗留待确认（5 项，详见 `docs/P2-REPORT.md` §九）
1. **【破坏性接口变更】** `/students/page`、`/teachers/page`、`/staffs/page` 由 `R<List>` 改为 `R<PageDTO>`（原名 `/page` 却返回全量列表，属实现缺陷）。无前端，此刻改成本最低；需兼容可加 `/list`。
2. 建题默认草稿 `status=0`（PLAN 未定，选了安全默认）。
3. `tagIds` 的 `null`（不改动）与 `[]`（清空）语义不同，前端易忽略。
4. 用例删除是物理删除，`judge_result.case_id` 的审计能力受影响（P3 起）。
5. **网关注释与配置不一致**：`AuthGlobalFilter.optionalIdentity` 注释举例 `/problems/page` 匿名可浏览，但 `JwtProperties.excludePaths` **并未**收录它 → 经网关访问题目列表**必须登录**。未擅改（涉及匿名可见的产品决策）。

> ⚠️ **本机环境限制**（.sh 无法执行 / `SERVER__PORT` 注入 / python 必须绝对路径）已统一收敛到 **§7**，此处不再重复。

---

## 5. 下一步该做什么（下次开新会话直接说这句）

> **「读 docs/CONTEXT.md，P6 已收口 —— 按 §5.0 上线前清单继续」**

### 5.0 P6 已通过端到端验收（2026-09-21）—— 功能开发到此结束

**PASS=98 / FAIL=0**（`scripts/verify-p6.py`，A–H 段，约 35s）。复现序列见 §5.5。

**至此六阶段全部交付，不再有新功能阶段。** 后续只剩上线前准备。

> **预检终态（2026-09-21，`scripts/preflight-check.py`）：FAIL 2 / MANUAL 6 / PASS 12 / WARN 5**
> —— 两条 FAIL 是 **B4** Grafana 默认密码未换、**F1** Alertmanager 路由全指向 `null`，
> 二者分别由第 4 项与第 3 项处置。**2026-09-22 未复跑**（后端服务处于停止状态），
> 改为补做可复现的配置校验：promtool 规则/配置 SUCCESS、amtool 生效配置 SUCCESS。
>
> 📄 **放行决策看 `docs/LAUNCH-READINESS.md`（单页）**：阻断项 / 建议项 / 已完成 / 放行前命令清单 /
> 已知限制 / 回滚点。本表是进度视角，那份是「能不能上线」的决策视角。

#### 上线前 6 项 —— 处置状态（更新于 2026-09-22）

| # | 事项 | 状态 | 说明 |
|---|---|---|---|
| 1 | 告警阈值重标 | 🟡 **口径已修，待生产重标** | 修正 2 处统计口径（排除 `/actuator` 与 SSE，否则 P95 虚高 10×+）；`HikariPoolSaturation` **换判据**（`pending>5` 被实测证伪 → 改「平均获取连接耗时>0.2s」）；提交 5xx 过滤收紧为 `method=POST, uri=/submissions`。重标工具 `scripts/recalibrate-alerts.py` 补两道守卫（样本稀疏不改延迟阈值 / 无提交样本不改提交阈值）。**生产流量稳定后仍需 `--write` 重标** |
| 2 | 生产压测 | 🟡 **恒定负载模型已落地并实测** | 新增 `perf-test/jmx/throughput.jmx`（无限循环+固定时长）与 `perf-test/RUNBOOK.md`（独立压测机手册）。实测 **3 108.8 req/s / 559 035 样本 / 0 错误 / P95≤139ms** @60 线程 180s → 500 req/s 门槛 **PASS（6.2×）**。**同机数据仍是容量下界**，生产须按 RUNBOOK 在独立压测机做爬坡标定 |
| 3 | Alertmanager 外发通道 | 🟡 **机制已就绪，缺真实凭据** | `alertmanager.yml.tpl` + `entrypoint.sh`（awk 渲染）+ compose 环境变量驱动，**不含任何凭据**。已用本地 webhook 接收器**实测打通整条外发链路**（合成告警 + 真实 `HikariPoolSaturation` 均成功送达）。生产只需填 `.env` 的 `ALERTMANAGER_*` 即生效，无需改配置。当前仍是 `null` receiver → preflight **F1 FAIL** 属预期 |
| 4 | 凭据轮换 | 🔴 **需你授权执行** | `scripts/rotate-credentials.py` 已就绪（预演/落盘两段式 + 打印 DB/Redis/MinIO/Grafana 同步命令）。**运行需读取 `.env`，授权超时未执行**。当前 Grafana 仍是 compose 默认密码（preflight **B4 FAIL**）。<br>⚠️ **2026-09-22 补**：静态扫描发现**两处代码内兜底口令**必须被环境变量覆盖，否则静默生效（见下方「三项新发现」），已新增 `scripts/check-hardcoded-defaults.py` 固化该检查 |
| 5 | `docs/DEPLOYMENT.md` §7 清单逐项勾选 | 🟢 **已重构为可勾选 + 标注自动校验** | 由 11 条平铺改为 5 组（凭据/暴露面/通知容量/运行时数据/走查），每条标注 `自动 B4` 之类来源与当前结论；`scripts/preflight-check.py` 覆盖 18 项自动检查，另加 `check-hardcoded-defaults.py` 覆盖「零硬编码」 |
| 6 | 前端单元测试 | 🟢 **已补齐 136 项** | 引入 vitest + jsdom + @vue/test-utils；覆盖 SSE 分帧/协议（含汉字跨 chunk 截半）、HTTP 拦截器业务码契约与 401 单飞刷新、Pinia 登录态、路由守卫角色矩阵、格式化口径、组件。`cd judge-web && npm test` |

> ⚠️ **另有四项本轮新发现（2026-09-21 / 09-22），建议上线前处理**：
> 1. **仓库零提交** —— `git log` 显示 `master` 分支**没有任何 commit**。`docs/` 作为持久化载体的前提是入 git，目前并不成立，且没有回滚点。→ 2026-09-22 已创建首个提交（430 文件）。
> 2. **`.gitignore` 已补两轮** —— ① `perf-test/results/`、`perf-test/csv/`（否则 `git add .` 会带进数十 MB 的 `.jtl` 与**含明文压测账号密码**的 `users.csv`）；② `judge-web/coverage/`（vitest 覆盖率 HTML，56 个文件，首提交预演时发现遗漏）。
> 3. ✅ **两处「兜底弱口令」已于 2026-09-22 修复** —— 原属**静默失效**家族，表现为
>    "不配也能启动、启动无任何提示、只在触发那一刻写一个可预测的值"：
>    - `CJ_ADMIN_INIT_PASSWORD`（Spring 键 `cj.admin-bootstrap.init-password`）——
>      原先 yml 与 `AdminBootstrapService` 的 `@Value` **双份**兜底为 **`123456`**。
>      **现两处兜底全部移除**：未配置时生成 24 位随机强口令，只写入 `.bootstrap-credentials`
>      （**不进日志**，避免口令长期留存于日志文件）；配置但 < 8 位时启动告警。
>    - `CJ_USER_DEFAULT_PASSWORD` —— 原先 `UserService` 用常量
>      `FALLBACK_DEFAULT_PASSWORD = "123456"` 兜底。**现移除**：未配置时「重置密码」接口
>      fail-closed 返回 400 并说明原因。理由：重置密码是把一个已知字符串写进**别人的**账号，
>      可预测 = 全站统一后门。
>    - **判据**：`scripts/check-hardcoded-defaults.py` 由 **FAIL=0 / WARN=2** → **FAIL=0 / WARN=0**。
>    - 同时把 `.env.example` 里那两行字面量 `123456` 换成空值 + 说明（模板不该诱导直接使用）。
> 4. **`.env` 与 `scripts/rotate-credentials.py` 的读取限制（2026-09-22 实测口径）**：
>    `.env` 的 `Read` / `Edit` 实测**可用**；`rotate-credentials.py` 的完整 `Read` 仍可能触发
>    授权超时（`SENSITIVE_APPROVAL=TIMED_OUT`），但 `Grep` 局部检索与 `Edit` 均可正常操作。
>    **仍建议**：涉及密钥生成与轮换的最终动作由你本人执行并复核（命令见 §5.6）。

### 5.7 🔴 2026-09-22：判题首次投递缺失（已修）+ P2-2 稳定性收口待办

**背景**：P0-1「成果与指标」采判题端到端时，每样本稳定 ~28–30s，而沙箱内只跑 23–30ms。

**根因（六条证据）**：`SubmissionService.createSubmissionWithTask()` 在 `judgeTaskMapper.insert(task)`
之后**直接 return，从未投递 MQ** —— `JudgeEventPublisher.publishTaskCreated()` **零调用点**
（注意方法名是 `publishTaskCreated`，不是 `publishCreated`，别 grep 错）。
`SUBMISSION_CREATED` 全仓库仅出现在常量定义、该未被调用的方法、worker 的 `subscribeTags`
（**在订一个永远不会被生产的 tag**）。任务因此只能等「滞留重发」兜底，耗时构成：
`fixedDelay 10s` + `pending-rescue-delay 15s` + `RETRY_DELAY_LEVEL=2`（5s）+ 判题 ~3.4s = **23–33s**。

| 证据 | 结果 |
|---|---|
| `grep -rn publishTaskCreated judge-submission/` | 只有定义，**零调用点** |
| `grep -c "投递判题任务：" logs/judge-submission.dev.log` | **0** ← 该日志行就在 publishTaskCreated 里 |
| `grep -c "投递判题重试："` | **1155**，全部 `reason=pending-rescue` |
| worker 日志 `source=` 分布 | **1158 全 RETRY** |
| DB `update_time − submit_time` | **27 000 / 29 000 / 30 000×4 ms**（卡在扫描周期上，非自然分布） |
| 同期 `time_ms`（沙箱内） | 23–30 ms |

**为何 P3/P6 验收没拦住**：验收脚本断言「最终能出 AC」而**不设时限**，补偿路径让它照样通过（慢 10×）。
属「假绿」家族 —— **功能断言通过 ≠ 设计路径生效**。

**已修复（1 行附加式，2026-09-22）**：`createSubmissionWithTask()` 补
`TxSupport.afterCommit(() -> eventPublisher.publishTaskCreated(submission.getId(), task.getId(), 0));`
→ 复验 `投递判题任务：ok=true`、worker `source=CREATED`、端到端 **3.4–4.5s**。
滞留重发**保留**为兜底（覆盖 MQ 发送失败），主路径与兜底是并存关系。
📄 勘误已写入 `docs/P3-REPORT.md` 顶部。

#### P2-2 稳定性收口 —— ✅ **全部闭环**（2026-09-22 更新）

| # | 事项 | 状态 |
|---|---|---|
| 1 | 判题首次投递缺失（本 defect） | ✅ **已修并复验**（2026-09-22） |
| 2 | `docs/PERF.md` §3.3「单实例约 1.5 题/秒」重新标定 | ✅ **已完成**：新增 `scripts/measure-judge-throughput.py`，1 vs 3 worker 对比轮次已跑 → 单实例 **0.94~1.00 题/s**，**3 实例 0.96 题/s（不随实例数增长）**；旧值 1.5 是坏路径下的积压排空速率。§3.3 已标注该数字不可用于容量规划、§4/§5 两处引用已同步、**新增 §3.7 完整结论** |
| 3 | `deploy/mysql/my.cnf` 被 MySQL 静默忽略 | ✅ **已修**（同日）：改用 `install -m 0644` 落到 `conf.d` 外，`SELECT @@...` × 5 参数实测全部生效、`world-writable` 告警 **0**（原先 `buffer_pool 128M`、`flush=1`、`REPEATABLE-READ`、`long_query_time=10` 一项都没生效） |
| 4 | `scripts/dev-start-backend.py` 里「单实例约 1.5 题/秒」注释 | ✅ **已改**：换成实测口径 + 多实例必须设 `CJ_MQ_CONSUME_THREADS` 的告警；同时修掉 `--extra` 实例**共写同一个日志文件**的问题（现为 `logs/<module>-<port>.dev.log`） |
| 5 | P3 验收脚本应补**时限断言** | ✅ **已补并双向验证**：`verify-p3.py` 新增 `A1b 主路径时限`（默认 15s，`CJ_P3_E2E_BUDGET_S` 可调）；正向 4.2s PASS / 负向（压到 3s）确实 FAIL。**顺带修掉两个同类隐患**：① 60s 幂等窗内重跑会命中幂等返回旧记录 → 每次运行给代码加 nonce，保证"真判"；② `D6b` 的 `all()` 空样本恒真 |

### 5.8 2026-09-22 第四轮：判题吞吐标定（**推翻了「加判题机实例」这条扩容路线**）

**做了什么**：新增 `scripts/measure-judge-throughput.py`（受理速率 / 排空窗口 / 吞吐 / 时延 / 正确性 五项分开），
跑 1 实例 vs 3 实例对照，并落成硬约束第 17 条 + `docs/PERF.md` §3.7。

**三条实测结论**：

| 结论 | 证据 |
|---|---|
| 单实例判题吞吐 **≈0.95 题/s**（旧记 1.5 是故障路径残值） | 150 条 AC：排空 159.12s（复跑 149.49s）；沙箱内仅 ~100ms |
| 🔴 **加实例不涨吞吐** | 1 实例 0.94 → 3 实例 0.96 题/s；瓶颈是**每题 5 个容器**（1 编译 + 4 用例）的启停开销 |
| 🔴 **未限并发的多实例会判假 TLE** | 3×20=60 并发 → 抽样 **18/25 TLE**；日志 `沙箱墙钟超时强杀 wallClockMs=6000` **48 次**；空队列串行提交则全 AC |

**已落地的改动**：
1. `RocketMQProperties` / `RocketMQConsumerContainer` 新增 `consume-thread-min/max`（env `CJ_MQ_CONSUME_THREADS`），
   **默认 0 = 不设置 = 行为与改动前完全一致**；judge-worker 的 yml 已接。
2. `verify-p3.py` 补 `A1b 主路径时限` + 每轮 nonce + 管理员凭据改从 `.env` 取（E 段不再恒 SKIP）+ `D6b` 空样本修正。
3. `dev-start-backend.py`：修正 1.5 题/秒注释、`--extra` 实例日志按端口分文件。
4. `preflight-check.py` C2 文案改为实测口径 + 扩容告警。

**对上线清单的影响**：
- `LAUNCH-READINESS` 的 **A4「判题机多实例」不再是"起 9085/9185/9285 即可"** ——
  多实例必须配 `CJ_MQ_CONSUME_THREADS` 且**跨宿主机**才可能接近线性；单机加实例无收益。
- ⚠️ **提交限流会绊倒验收脚本**：`verify-p3.py` 单轮就要提交 ~20 次，而默认限流是 30 次/分钟/账号
  —— **一分钟内连跑两次 `verify-p3.py` 必然大面积 FAIL**（表现为"回归"，实为限流）。
  跑前先等窗口，或临时放宽（放宽后务必按 `measure-judge-throughput.py --help-limit` 恢复并验证）。
- 仍待办：`E4` 生产重标告警阈值、`F1` 告警通道（需外部凭据）、`M2/M4/M6` 属环境/人工项。

### 5.9 2026-09-23 第五轮：鉴权收敛 + 一键启动聚合（本轮）

**需求（5 项）**：① 服务启动聚合成一条命令并说明顺序依赖；② 鉴权全部收在后端、前端零参与，
登录不选角色；③ 无权限按钮不渲染、按权限分别渲染；④ 修掉登录页初始账密外显；
⑤ 初次运行生成凭据文件、改密后消失（参考 `D:\1\zx-learn` 保持行为一致）。

**先侦察再动手 —— 与需求原文不符的 4 处实况（都已按实况而非原文实现）**：

| 需求里的假设 | 代码实况 |
|---|---|
| ⑤ 需要新建"初次运行写凭据文件"机制 | **已实现**（`AdminBootstrapService` + `AdminBootstrapRunner`），本次只扩了覆盖面（`isBootstrapPending()` + 登录响应 `mustChangePassword`） |
| 角色需要新增判定字段 | `user.type` 早已写进 JWT `roleId` claim 并经网关 `role-info` 透传到 `UserContext.getRole()`，**无需新链路** |
| 启动聚合是新问题 | 真实缺口只是 **`dev-start-backend.py` 的模块顺序反了**（auth 排在 user 前） |
| 登录页只是"显示"了默认账密 | 更实际的是**两个登录入口共存**导致 `/accounts/admin/login` **完全不受登录限流保护**（谓词只覆盖 `/accounts/login`）—— 一个可爆破的安全缺口 |

**拍板记录（3 项，用户均选推荐项）**：能力码以 `user.type` 为**唯一权威**（不启用 DB RBAC 六表）；
演示账号口令**保留 123456、仅移除界面外显**；旧端点**保留为兼容别名**。

**交付物**：

| 类别 | 内容 |
|---|---|
| 后端 | `Capabilities`（20 个能力码 + 三角色映射，新增码自动纳入员工）、`CapabilityService`（10 个菜单 + 3 个落地路由，菜单自带 `perm`）、`CapabilityProfileVO`/`MenuItemVO`/`CapabilityVO`；`AccountService` 收敛为 `loginAuto()` 单入口 + `login()` 兼容别名，角色分支全收拢进 `issue()`；`UserRole` 加 `*_CODE` 编译期常量（switch case 需要，引用时必须写全限定名 `UserRole.STAFF_CODE`，否则 JLS §8.3.3 非法前向引用） |
| 接口 | `GET /accounts/me/capabilities`；`POST /accounts/login` 响应新增 `role` / `roleLabel` / `mustChangePassword`；`/accounts/admin/login` 标 `@Deprecated` |
| 前端 | 新建 `directives/permission.js`（`v-perm`，未授权**从 DOM 移除**而非隐藏）；`stores/user.js` 重写（删全部角色 getter，改 `can()` + `capabilities`）；`router` 的 `meta.roles` → `meta.perm`；`api/http.js` 403 回调；6 个视图改用能力码；`LoginView` 删角色选择器与页脚账密 |
| 启动 | `scripts/start-all.py`（4 阶段、复用 `dev-start-backend.py` 的派生逻辑、`--wait` 看护、监控栈漏带 `--env-file` 时**拒绝启动**）；`dev-start-backend.py` 的 `SERVICES` 顺序修正为 user → auth |
| 测试 | 后端 90 项（judge-common 42 + judge-auth 48，新增 `CapabilitiesTest` 9 / `CapabilityServiceTest` 11 / `AdminBootstrapServiceTest` 11 / `AccountServiceTest` 扩到 13）；前端 **9 文件 153 项**（新增 `permission-directive.spec.js` 6 项，重写 `stores-user` 30 / `router-guard` 17） |
| 验收 | `scripts/verify-authz.py` **56/56**（含前端静态断言）；`verify-p1-login.py` **41/41** 无回归 |

**顺带修掉/发现的 6 个问题**：

1. **安全缺口**：`/accounts/admin/login` 不在登录限流谓词内（可爆破）→ 收敛为单入口后消失。
2. **启动顺序缺陷**：auth 先于 user 起 → 首个管理员引导被 catch 吞掉，
   症状是「服务全绿但没有管理员账号、也没有凭据文件」。
3. **refresh cookie 并存**：登录时写一枚不清另一枚 → 续签读到过期身份（「刚登录就被踢下线」）。
4. **孤儿凭据文件**：`judge-auth/.bootstrap-credentials`（263 B，9/20）残留。**实测其中口令为旧弱值
   `123456` 且已失效**（登录 401，当前管理员口令是 `.env` 里那个 32 位值）→ 已删除。
   根因是 `resolve()` 按进程工作目录解析相对路径，换启动方式就落到别处 →
   已让 `reportInitPasswordSource()` **无条件打印绝对路径**。
5. **死代码**：`Capabilities.groupOf()` / `GROUP_OF`（与菜单自带 `group` 重复、零调用者）、
   前端 `USER_TYPES`（角色选择器的遗留）、`format.js` 中 `typeLabel` 的死引用、凭据文件抬头
   仍写「知行智学」—— 全部清除。
6. 🔴 **`verify-p1-login.py` 第 7 段「改密 fail-closed」此前是假绿**（本轮删掉孤儿文件后才暴露）：
   - 路径写成 `<repo>/judge-auth/.bootstrap-credentials`，而服务实际解析的是
     `<进程 cwd>/.bootstrap-credentials` = `<repo>/.bootstrap-credentials`；
   - 断言只查「文件是否存在」，**前提就是它本来就存在** → 它一直绿，靠的正是第 4 条那个孤儿文件。
     也就是说这条"fail-closed 验证"**从未验证过任何行为**：换全新部署（无文件）或已改密后（文件被删）时它必红。
   - 已改为：文件不存在则临时造哨兵 → 记录内容 → 跑失败路径 → 断言「文件仍在**且内容未被改写**」→
     `finally` 清理，并在开头**自愈**上次异常退出遗留的哨兵（43 项，原 41 项）。
   - ⚠️ 哨兵必须清理干净：`isBootstrapPending()` 的判据就是「这个文件在不在」，
     残留一个哨兵会把登录响应的 `mustChangePassword` 伪造成 `true`（**测试污染被测系统状态**）。

**新增的验证纪律**：`verify-authz.py` 在 `dist/` 缺失时把该子项记为 **SKIP 并计入跳过数**，
不允许"没跑"被算成"通过"（延续硬约束 16 的假绿防线）。

### 5.10 2026-09-23 第六轮：列表统一 + 演示数据重置 + `/jwks` 越权（本轮）

**需求（3 项）**：① 修「标签列表与数据行不对齐」并统一全部列表样式，保证每列整齐对齐；
② 清洗项目内无用/冗余数据，灌一批初始数据供测试与功能审查；③ 讲清核心功能如何实现。

**① 前端列表统一 —— 先定根因再改，根因与"列宽"无关**

`el-table` 用 `table-layout: fixed` + `colgroup`，**列宽本来就是死的**，所以"列没对齐"从来不是
列宽问题。真实症状是**行高参差**与**内容不在同一垂轴**，三处具体成因（读源码定位）：

| 位置 | 成因 |
|---|---|
| `teacher/ProblemManageView.vue` | 标签内联在标题下方且 `v-for` 不截断 → 该行比邻居高，整表"锯齿" |
| `ContestListView.vue` | 三枚标签（状态/封榜中/已报名）内联在标题后 flex-wrap → 挤压标题列 |
| `SubmissionListView.vue` | 「竞赛」标签内联在题目号后带 `margin-left:6px` → 该列左边缘漂移 |

交付：新建 `judge-web/src/styles/data-list.css`（列表页共享骨架：页头/工具条/数据面板/单元格
对齐契约/读数卡片/窄屏）与 `components/TagCell.vue`（唯一标签渲染入口，默认折叠到 2 枚 + `+N`，
`title` 列出被折掉的标签名）；12 个列表/详情视图改为全用全局类，标签一律独立成列；
ECharts 系列色改从 CSS 变量读（`--chart-1..4`，全仓**唯一**用 sRGB hex 的地方，因为 ECharts 不认 oklch）。
验证：`vitest` 9 文件 153 项全绿、`vite build` 通过；另用真浏览器量几何
（`scripts/probe-list-geometry.cjs`，CDP 直连本机 Edge，**7 条路由 × 深浅两主题全部 PASS**）：
每张表**行高集合只含 1 个值**、**列边缘离散 0px**、**列内跨行内容离散 0px**、标题单元格内不再内联标签。

> **量测陷阱（值得记）**：一开始把「单元格内容顶部偏移」**跨列**比较，得到满屏
> 「内容垂轴不齐」（最大 34.7px）—— 但那两行堆叠的单元格（ID+标题+副标题）与单行单元格
> 天然高度不同，这是**测量方法的产物，不是缺陷**。正确问法是：① 同一个列内各行的内容
> 是否落在同一垂直位置（列内跨行离散度，实测 0px）；② 单元格内容是否垂直居中。
> ② 的残差实测稳定在 2.3–2.8px，是 Element Plus「行盒放在单元格内容盒顶部」的系统性结果
> （视觉偏差约 1.3px），**已明确不作为缺陷判定**，只打印数值。

**② 演示数据重置 —— 新增 `scripts/reset-demo-data.py`（默认预演，`--yes` 才落盘）**

五阶段 `backup → clean → seed → rank → verify`。两条原则：**先备份再动手**；
**演示数据不造假**（55 条提交全部经网关投递、由判题机真判，不直接 INSERT verdict；
唯一例外是 pgvector 向量用 bigram 哈希占位，已在代码里显著标注为"非模型产出、不可用于评估检索质量"）。
实测收口：**PASS=13 / FAIL=0**；55/55 提交结论与预期一致；六种结论齐备；
封榜冻结榜 5 人、终榜快照 3 条（FINAL/FROZEN 均由真实链路生成）。

**③ 新增 `docs/CORE-IMPLEMENTATION.md`**（575+ 行，7 节 + 附录）：判题链路、竞赛榜、
AI 点评、鉴权网关、服务端口、存储分工、20 条关键取舍；每条事实都带文件:行号证据。

**本轮发现的缺陷（含 2 个此前未记录的安全/行为问题）**

| # | 问题 | 结论 |
|---|---|---|
| 1 | 🔴 **`/jwks` 匿名泄露 JWT 签名密钥，可伪造任意身份令牌** | `JwtTool.getPublicKeyBase64()` 对 HMAC 密钥返回的就是密钥本体；该端点进免鉴权白名单且**全仓无消费方**（网关自己用共享密钥验签）。实测：匿名 GET `/jwks` → 解码得 64 字节密钥 → 自签 `{userId:管理员, roleId:1}` → `GET /problems/4001/versions`（要求 STAFF）返回 **200**（无凭证时 401）。**未修，方案见 `docs/CORE-IMPLEMENTATION.md` §4.4**（A 删除端点／B 改 RS256） |
| 2 | **Python 语法错误判 RE 而非 CE** | 沙箱对脚本语言无独立编译步，`SyntaxError` 在运行时才抛 → 界面按「编译错误」筛选**永远筛不到 Python 提交**。若视为缺陷，修法是在沙箱加一次 `python -m py_compile` 预检 |
| 3 | **Java 的"内存爆炸"判不出 MLE** | 32MB 容器里 JVM 默认堆约 8MB，`new long[8e6]` 直接抛 `OutOfMemoryError` → 普通非零退出 → RE。MLE 需进程被 OOM Killer 杀（137）或 `memKb>limit`，Java 两者都到不了。同写法 Python/C++ 正常判 MLE |
| 4 | **`4003` 隐藏用例自相矛盾（"无解"）** | `sql/seed.sql:161` 声明 `n=10000000` 却只给 10 个数字，期望却是 `10000000` → 任何正确解都 WA。已改为期望 `10` 并写明理由（`test_case.stdin` 是 `text`，1e7 个数字约 20MB 存不下；MLE 演示靠片段自己按 n 硬分配，与输入长度无关） |
| 5 | **`problem.submit_count/accepted_count` 全仓无写入点** | 只在建题时置 0（`ProblemService.create`），此后无任何代码写入 → 题库「通过率」列在真实运行中**恒为「—」**。本轮只在数据侧按真实提交聚合回填（`resync_counters()`），**未修代码缺陷** |
| 6 | **库的表/列注释是双重编码的乱码（19 处）** | 数据本身正常，仅注释受影响；与 `sql/init.sql` 里的正确注释不一致（历史 latin1 客户端所致）。纯装饰，会让每次 mysqldump 都带乱码；修法：按 `sql/init.sql` 重发 `ALTER TABLE ... COMMENT`。**未改 DDL**（等确认） |

**写脚本本身踩到的坑（都已修，且值得记住）**

- **`#` 不是所有语言的行注释**：给 CPP/GO 追加 nonce 用了 `#` → C++ 当预处理指令、Go 非法字符
  → **全部 CPP/GO 提交判 CE**，而日志只说"结论与预期不符"，归因会一路跑到判题机上。
  已改为按语言选 `NONCE_PREFIX`，并加加载期自检（清单里的 `语言` 必须与代码键里的语言一致）。
- **"朴素循环必然 TLE"是编译器的变量**：`for(i=1..n) s+=i`（n=1e9）被 GCC `-O2` 折叠成闭式
  公式 → 实测 220ms **AC**；加 `volatile` 后 242ms 仍 AC；再加重到 724ms 还是 AC。
  最终需每次迭代做 4 次带副作用的内存往返才稳定超 1000ms。
- **`login_record.id` 没有 `AUTO_INCREMENT`**（应用侧雪花填充），裸 INSERT 报
  `Field 'id' doesn't have a default value`；且该表**没有 `success` 列**（只记成功登录）。
- **网关登录令牌桶（2 req/s、突发 5）会打断播种**：症状是**空响应体**导致
  `JSONDecodeError: Expecting value: line 1 column 1 (char 0)`，看起来像服务没起或路由配错。
  处理是**客户端退避重试**（等 0.8s），**不放宽**线上安全水位。
- **`frozenAt(now) = freezeAt != null && now >= freezeAt && now < endTime`**：封榜**只在赛程内成立**。
  早先脚本先用 SQL 把 `status` 改成 2 再 refreeze → `refrozen=False`、无 FROZEN/FINAL 快照
  （verify 抓到 2 个 FAIL）。正确顺序是「**先在赛程内封榜 → 再把 end_time 推回过去**」，
  让 `ContestLifecycleService` 的 10s 扫描自己推进状态并写终榜。
- **竞赛 id 客户端指定不了**（雪花 ~2.1e18）：早先按"预留段位 5001/5002/5003"写，
  `DELETE WHERE id=5001` 删不到东西、POST 回来也不是 5001，只在日志刷 WARN。
  已改为**按标题幂等**（`resolve_demo_ids()`），seed/rank/verify 三个阶段可各自独立运行。
- **"影响面"表里的假数字**：`redis judge:*` 与 `pg judge_ai` 原本写死 0，实际有 14 个键 / 36 行。
  一张写着 0 的破坏性操作影响面表会让人直接按 `--yes`。已改为真实计数。

**验收/测试状态（本轮收尾）**：`vitest` 9 文件 153 项；`vite build` 通过；
`reset-demo-data.py --phases verify` **13/0**；`probe-snippet-verdicts.py` **33/33**；
`probe-list-geometry.cjs` 7 路由 × 2 主题全通过。备份：`backups/20260923-172551/`（清洗前全量）。

### 5.11 2026-09-23 第七轮：竞赛详情不可达 + 表格列塌陷 + 列样式统一 + 引导凭据闭环

**需求（4 项）**：① 修「点开竞赛提示"竞赛不存在"且一直停在加载中」；② 修竞赛/题库界面的文字重叠；
③ 以题库「通过率 / 操作」列为基准统一各列样式（题库、竞赛、提交记录）；④ 生成
`.bootstrap-credentials`，等初始账号改密后自行消失。

**①② 两个症状同一个根因：路由参数被 `Number()` 截断**

```js
// ContestDetailView.vue —— 错
const contestId = computed(() => Number(route.params.id));   // 2102698982248611841
// → 2102698982248611800，JS 精度上限 2^53-1 ≈ 9.0e15，雪花 id 约 2.1e18（19 位）
```

`Number()` 一旦截断，下游两条链路同时失败：`GET /contests/{id}` 抛 `BizIllegalException(404,"竞赛不存在")`；
`WS /ws/contests/{id}/rank` 的 `contestMapper.selectById(截断id)` 返回 null → `fail(session,...)`
→ **`rank` 永远为 null → `v-loading="!rank"` 永久转圈**。所以"提示不存在"与"永远加载中"不是两个 bug。
修法：`const contestId = computed(() => route.params.id)`（保留字符串，`SafeLongSerializer` 后端本就序列化成字符串）。

> **量测陷阱**：探针一开始把 `code=404` 读成"HTTP 200 正常" —— 本项目接口 HTTP **恒 200**，
> 业务码在 body 的 `code`。判 HTTP 状态码在这里等于什么都没判。

**② 的第二个成因（更贵）：`.cj-hide-sm { display: initial }`**

`main.css` 里 `.cj-hide-sm` 本是"窄屏隐藏"辅助类，却写了 `display: initial`。
**`display` 的 initial 值 = `inline`，不是 `table-cell`** —— 带该类的 `td/th` 脱离单元格流，
浏览器把它与相邻 inline 子元素包成**一个匿名单元格**，列数 ≠ `colgroup` 列数 →
`table-layout: fixed` 重分配全部列宽，多列内容叠在同一块面积上（截图里「赛程/题目/报名」
三个表头竖着叠成一列）。修法：该类**不再声明任何 `display`**，只在窄屏媒体查询里 `display: none`。

实测（`logs/tmp/probe-hide-sm.cjs`，720px 表宽，第 2/3/4 列带该类）：

| 现状 | 第 2/3/4 列的盒子 |
|---|---|
| `display: initial` | 三列并成一个 `[329.8, 573]`，其后右侧全空 |
| 不声明 `display` | 各自 `table-cell`：`[329.3,573.5]` `[573.5,656.5]` `[656.5,739.5]` |

真页面（`logs/tmp/shoot.cjs` 注入旧 CSS 复现修复前）：竞赛页行高 **81.5px**（三列塌陷撑高）、
「竞赛」列 ID 墨迹与标题**重叠 +89.2px**；修复后行高 64px（单一值）、重叠 −8px。

**③ 列样式统一 —— 先证明"哪里真的不一致"，再改**

`logs/tmp/probe-align.cjs` 逐列量了表头/内容的对齐值：**表头与内容是同一个 `align`，没有错位**。
真实不一致是「同一张表里 5 个数据列混了 left/center/right 三种对齐」+「限制列是全表唯一降级显示的
数值列」。故按「通过率 / 操作」基准统一为：**数据列一律 `align="right"`**；标签列右对齐
（`align="right"` + `TagCell align="end"` 必须成对 —— **flex 容器不响应 `text-align`**）；
状态列保留居中；`限制` 改用与 `通过率` 完全相同的 `.cj-cell-stack--end` 两行原语；
竞赛「题目/报名」用新增 `.cj-cell-num`。

同时修掉一处**未被点名**的缺陷：提交记录页「提交」列宽 104px + `el-table` 的 `.cell` 默认
`word-break: break-all` → 19 位提交号**每行都从中间折成两行**（实测"首列行盒数 = 2"）。
列宽 104→172 + 新增 `.cj-cell-id`（`white-space: nowrap` + 等宽 + `tabular-nums`）后为 1。

几何终态（`logs/tmp/probe-columns.cjs`，题库/竞赛/提交/题目管理 4 页）：表头列数 == 表体列数、
每个 `cj-hide-sm` 列 `display: table-cell`、列边缘离散 0px、表头/表体最大边缘偏差 0px、行高单一值 —— **全 PASS**。

> **另记一项未改**：提交记录页 8 列总宽 926px < 面板内宽 ~1382px，右侧空出约 456px
> （题库/竞赛页都是填满的），属跨页骨架不一致，未处理。

**④ 引导凭据闭环 —— 发现「提示写了但没人看得见」+「个人中心改密不删文件」**

需求是「生成文件，等初始账号改密后消失」，但实测这条链路上有两处断点：

| # | 问题 | 证据 / 结论 |
|---|---|---|
| 1 | **登录页那条改密提示永远不会被看到** | `LoginView.vue` 里 `pwdHint.value = ...` 与 `router.replace()` 在**同一个函数**里，中间没有渲染窗口，组件随即卸载。`logs/tmp/verify-bootstrap.cjs`：登录成功（落地 `/admin/users`）但页面 `正文提到 .bootstrap-credentials = false`。**已改**：提示改挂 `AppLayout` 的 `.boot-warn`（复用既有 `.perm-warn` 同一套样式），跨路由存活；`mustChangePassword` 进 store 并落 `sessionStorage`（否则按一次 F5 提醒就永久消失 —— 它只在登录响应里出现，**没有二次查询接口**）。 |
| 2 | 🔴 **个人中心改密不删凭据文件** | `ProfileView.vue` 调的是 `userApi.changePassword`（`PUT /students/password` → `StudentController#changePassword` → `UserService.changePassword`），**只改库里的 BCrypt 摘要，碰不到那个文件**。而删文件的是 `POST /accounts/password/first-change` → `AdminBootstrapService#changeBootstrapPassword`（judge-auth 持有文件路径）。若按注释「改密入口在登录页与个人中心」走后者，**密码改了、明文初始口令文件永久留在磁盘上，且 `isBootstrapPending()` 恒真 ⇒ 每次 STAFF 登录都被提示改密**。**已改**：`ProfileView#changePwd` 按 `user.mustChangePassword` 分流，引导态走 first-change。 |

> 修法取舍：**没有**让前端"改完密顺手删文件"（那要让 judge-user 去动 judge-auth 的文件），
> 而是把引导态下的改密统一收敛到唯一持有文件的一方（judge-auth）。
> `LoginResultVO:30` 那句「改密入口在登录页与个人中心」现在才真正成立。

**验收状态（本轮收尾）**

| 项 | 结果 |
|---|---|
| `vitest run --no-file-parallelism` | **10 文件 / 167 项全绿**（新增 6 项覆盖引导态标记：登录写入、普通账号不留痕、`restore` 恢复、清除、退出登录、刷新 token 不误清） |
| `vite build` | 通过（10.63s） |
| `logs/tmp/verify-bootstrap-banner.cjs` | **10/10 通过** —— 登录后提醒条可见且占位、文案指向凭据文件、标记落 sessionStorage、**刷新后仍在**、**换页后仍在**；个人中心改密请求打到 `/accounts/password/first-change` 且**未**打到 `/students/password`；错口令报「原密码错误」；**凭据文件仍在**（fail-closed，该验证**不消费引导态**） |
| `logs/tmp/probe-banner-contrast.cjs` | 提醒条三种配色对 × 深浅两主题，最低 **5.04:1**（限量处 = 浅色下 `code` 小片 `--tle` on `--surface-3`），高于本项目 4.60 设计目标；`--tle` 原本是解在更难的 `surface-4` 上的 |
| `logs/tmp/probe-banner-visual.cjs` | 深色 1600px / 420px 两种宽度**均无水平溢出**（420px 下折两行，条高 55px） |
| 凭据文件 | `D:\1\CodeJudge\.bootstrap-credentials`（276 字节 / 5 行）；`.gitignore:36` 已忽略、`git ls-files` 未跟踪 |

> ⚠️ **引导态尚未消费（有意为之）**：改密这最后一步留给用户本人执行 ——
> 路径「登录页 → 首次登录修改初始密码」或「个人中心 → 修改密码」，
> 成功后 `AdminBootstrapService.deleteCredentialFile()` 删文件、`mustChangePassword` 回 false、提醒条消失。
> 注意：**只要该文件在，任何 STAFF 账号登录都会拿到 `mustChangePassword=true`**（判据是文件在不在）。

### 5.12 2026-09-25 第八轮：上线前全面审查（安全收口，本轮）

**需求**：全项目结构与代码全面审查，为稳定上线做准备（结构/缺陷/安全/性能/配置/风格六维）。

**做法**：4 路并行探查（业务模块 / 基础与网关 / 配置部署 / 前端），确认约 40 项问题，高危全部当日修复。
详细清单与部署注意见 `docs/LAUNCH-READINESS.md` §G。要点：

| 类别 | 修复（摘要） |
|---|---|
| 🔴 认证 | `/jwks` 端点删除（§4.4 方案 A，历史遗留的最严重漏洞）；refresh token 增 `type` claim 校验（网关只放行 access、续签只收 refresh）；`PUT /users` IDOR 收口（自助改资料与改密彻底分离）；`GET /users/{id}` 补 OwnerAccessGuard |
| 🔴 注入 | `PageQuery.sortBy` 白名单正则（此前客户端可控字符串直拼 ORDER BY，布尔盲注拖库） |
| 登录 | 限流谓词补 `/accounts/admin/login`；`LoginResultVO.refreshToken` `@JsonIgnore`（只走 Cookie）；`CookieBuilder` 修 secure/domain 静默丢弃 + setHeader 吞 cookie |
| 判题正确性 | worker 终态回写改事务（CAS+插结果同生共死，消灭重复 judge_result 行）；`rejudge` 用 UpdateWrapper 显式 set(null)（旧 updateById 漏 null 字段 → "PENDING+旧 verdict"）；死信 CAS 未命中即 return（不再把 SUCCESS 改写成 FAILED/SE）；沙箱墙钟强杀补 `docker rm -f`（容器残留） |
| judge-ai | 生成链路 `publishOn(boundedElastic)`（DELTA 的 Redis 写/END 的 JDBC 原先跑在 Netty 事件循环上）；doOnCancel 的 JDBC 调度出 IO 线程；LLM/Embedding WebClient 全部配 responseTimeout + block 上限；知识入库先向量化后删旧（消灭半写状态） |
| 前端 | AI 点评面板/详情页/编辑页/知识库 9 处雪花 id `Number()` 截断清零；三个详情页补 `watch(route.params.id)`；列表页 5 处请求竞态守卫；进度 WS SNAPSHOT 重置 seq 基线；榜单 giveup 补可见提示；MonitorView globalMetrics 改 ref（Top URI 图表原先恒空） |
| 配置 | 网关 Redis fail-fast；沙箱镜像默认名三处对齐；`task-timeout/max-output` 默认值对齐；`.env.example` 补 `CJ_MQ_CONSUME_THREADS`/`BIND_IP`；中间件端口默认绑回环；Prometheus admin API 移除；Grafana 匿名默认 false；`min-score 0.0` 注释矛盾修正 |
| 依赖 | MySQL 驱动 8.0.23(2021) → `com.mysql:mysql-connector-j` 8.3.0；judge-common 去掉 rocketmq-client 钉死版本 |

**验证**：`mvn clean install`（11 模块含单测）+ `vitest 10 文件 167/167` + `vite build`。
**文档**：API-REFERENCE（令牌算法/白名单/限流）、CORE-IMPLEMENTATION §4.4（标已修）、LAUNCH-READINESS §G。
**遗留未修（记录在案，见审查报告）**：Python 语法错误判 RE 非 CE（需沙箱加 py_compile 预检）；Java 堆内 MLE 判不出；`problem.submit_count/accepted_count` 代码无写入点（数据侧已回填）；`springdoc` 生产应关（未配 profile，靠运维按环境关）；refresh token 无轮换/吊销（建议 Redis jti，属行为变更待拍板）；日志无滚动（建议 common logback-spring.xml）。

### 5.13 2026-09-30 第九轮：上线检查清单 A–L 落地（本轮）

**输入**：`docs/LAUNCH-CHECKLIST.md` 全量执行。**一键复验：`bash docs/launch-verify.sh` → PASS=42 / FAIL=0**。
逐项证据、偏离决策与阻塞项清单见 **`docs/LAUNCH-CHECKLIST-执行报告-2026-09-30.md`**（本轮权威汇总）。

**交付摘要**：
- **A**：9 Dockerfile（多阶段非 root）+ judge-web/nginx.conf（SPA//api/WS/SSE）+ compose app profile
  （9 服务、stop_grace_period 40s、worker 挂 docker.sock）+ startup.sh/.ps1（单服务/透传双形态）+ 优雅停机 8/8 yml
- **B**：ci.yml（JDK21/vitest/artifact/osv-scanner）+ JaCoCo check 门禁（**棘轮 0.04 起步**，
  实测基线 contest 0.047 ~ auth 0.549 写在根 pom 注释）+ SpotBugs/Checkstyle report-only + README 徽章
- **C**：**Flyway 接管**（6 服务 V1__baseline 与 init.sql 同源拆分 + baseline-on-migrate；
  **真机验证存量库路径**：judge-problem baseline=1、数据完好、health UP）；种子数据有意不进 Flyway；
  `scripts/backup-db.py`（mysqldump/pg_dumpall 实跑通过）；`docs/DATA-GROWTH.md`（分区方案+触发线）
- **D**：告警用既有 `prometheus/rules/codejudge-alerts.yml`（11 条，promtool SUCCESS，**不复制成双真相**）；
  看板 4→8（gen_dashboards.py 新增判题机集群/沙箱/MQ 延迟/竞赛榜单）；**Loki+Promtail 已入监控栈并运行**
  （3100 ready，抓 logs/*.log 抽 application/requestId）；`docs/SLO.md`
- **E**：DEPLOYMENT.md §8（TLS 骨架/JWT 轮换步骤/`/v1` 版本化方案+三处联动）；CORS 已收敛核对；
  trivy/osv 命令就绪（**本机漏洞库下载被网络拦截 → 实扫待有网环境**）；沙箱 base 镜像 **digest 固定**（4 个登记）
- **F/G**：可扩副本（app 服务去 container_name）；E2E/soak/浏览器兼容为已知待办（M6 等）
- **H/I**：`scripts/reset-pg-schema.py`（自检+自愈，实测）；ARCHITECTURE.md 队列对账章节；README 凭据风险提示
- **J/K/L**：CONTRIBUTING/CHANGELOG/SECURITY/.editorconfig/.gitattributes/.dockerignore + gitignore 补
  `perf-test/reports/`；`docs/ROADMAP.md`；`docs/launch-verify.sh`

**本轮修掉的缺陷**：`judge-contest` 测试文件 **UTF-8 BOM → `mvn verify` 必挂**
（`\ufeff` 非法字符；增量编译曾用旧 class 掩盖成假绿）。已清，全仓唯一。

**新踩坑（要记住）**：① Git Bash **内联 heredoc 会把 `\n`/`\r` 改写成 `/n`/`/r`**（MSYS 改写家族）——
内联脚本若含反斜杠序列必须先 Write 落盘再执行；② **mvn 运行中改根 pom** → 子模块重读父 pom 半新半旧，
构建诡异失败（本轮 judge-contest 首次失败的真实根因之一）；③ `&` 起的 JVM 随工具调用 SIGTERM 被连带回收
——起服务一律 run_in_background + `--wait`（已知坑的再次确认）。

**阻塞/待办**（10 项，详见执行报告）：镜像构建与 app `up` 实测、trivy/ZAP 实扫（网络）、
E3/E4 行为变更待拍板、G4 E2E、worker 容器化沙箱路径核验、CI 徽章占位符替换。

### 5.14 2026-09-30 第三轮：HANDOFF-PROMPT 执行（T9/T10/T11 代码落地，实测仍卡 Docker）

**输入**：`docs/HANDOFF-PROMPT-2026-09-30.md`。逐项证据见执行报告「第三轮」表。

- **T1 基线提交** ✅：launch-verify 42/0 → commit `35367ef`（68 文件）。
- **T9 SLO 补全** ✅（代码）/⏸（验证）：worker `JudgeE2eMetrics`（`cj_judge_e2e_seconds`，
  埋在 finishTerminal + 死信 SE，CAS 丢弃不计）+ 第 12 条告警 `JudgeE2ELatencyP99Breach`
  （SLO §4 之前声称有但实际缺）+ 看板 9 `codejudge-error-budget`。
- **T10 JSON 日志** ✅（代码）/⏸（验证）：`judge-common/logback-spring.xml`（jar 内收口，
  非 prod=人读+`[req=requestId]` 段、prod=JSON `JsonLogLayout`）+ 8 服务 clean package
  （嵌套 judge-common 复验已含新类）。**新坑**：jar plugin 内容未变跳过重建（forceCreation=false），
  `-pl` 不带 `-am` 时嵌套旧依赖原样保留 → 改 common 后必须 clean 或 install 再打包。
- **T11** ✅：MinIO 入 app profile（submission/worker 加 depends_on healthy）；
  broker store 卷经一次性 `rocketmq-store-init`（root chown 3000）落地，重启丢队列风险关闭；
  提交页「提交时间」列改 min-width 弹性列（骨架对齐）。
- **T0 仍阻塞**：daemon 崩溃后 VM 引擎未起（`192.168.65.7:2376 no route to host` 持续 20+ 分钟，
  explorer.exe 中转拉起有效但 VM 本身坏着）→ **必须用户手动 `wsl --shutdown`**。
  T2/T3/T4/T5 及 T9/T10 运行时验证全部排队在它后面。

### 5.15 2026-10-01 第四轮：T11 遗留收口（文档债 + JaCoCo 首批补测）

**输入**：`docs/HANDOFF-PROMPT-2026-09-30.md`。逐项证据见执行报告「第四轮」表。
**前置探测**：`docker version` 30s 超时（exit=124），T0 仍 BLOCKED，T2–T5/T9-T10 运行时验证继续排队。

- **T11a 文档债清偿** ✅：`LAUNCH-READINESS.md` 头部补「Docker 运行时实测链为放行前置」；
  §E 勾掉 MinIO profile / broker store 卷两条已修复限制（注明实测待 T2）并新增 Docker 通路
  风险行；§F 回滚点补 `35367ef`/`649d51d`；新增 §H（放行视角增量 + 10 项放行前置 + 4 条行为变更清单）。
- **T11b JaCoCo 首批补测** ✅：6 个测试类 49 例（contest 领域逻辑 8 + rank 契约 4、
  ai TextSplitter 7 + PromptBuilder 15、worker LanguageProfiles 9 + E2eMetrics 6）。
  LINE 覆盖率：**ai 0.066→0.21 / worker 0.089→0.136 / contest 0.047→0.05**；
  三模块 `mvn test` 65 例全绿，`mvn verify` 0.04 门禁通过。
- **⚠️ 新坑（第四轮实测）**：**T9 提交时 worker 测试未重跑** —— JudgeEngine 新增
  `JudgeE2eMetrics` 构造依赖，既有 `JudgeEngineFailTaskTest`（@InjectMocks）不注新依赖 →
  死信分支埋点 NPE。已修：注入真实 metrics（SimpleMeterRegistry）并把「死信 SE 计入
  SLO S3 样本」固化为断言（`cj_judge_e2e{verdict="SE"}` count=1）。
  **教训：给 Bean 加构造依赖后必须真跑一次 `mvn test`**，package 跳过 test 时旧产物会掩盖。
- 另：`SimpleMeterRegistry` 在 `io.micrometer.core.instrument.simple` 包（首次 import 踩错，
  编译期即拦下，损失 1 次构建）。

### 5.16 2026-10-01 第五轮：Docker 恢复后的容器化全链路实测（本轮）

**输入**：`docs/HANDOFF-PROMPT-2026-09-30.md`。逐项证据见执行报告「第五轮」表。
**T0 解除**：根因是宿主内存压力——用户拍板停掉 zx-learn 全栈（10 容器）后 daemon 通路即刻
恢复稳定，此前两天的「VM 网络通路反复死亡」再未复现。

- **compose 三处修复** ✅：① gateway healthcheck CMD-SHELL **单引号阻止 `$$CJ_ACTUATOR_TOKEN`
  展开**（token 成字面量被 ActuatorGuardFilter 拦 404 → 永远 unhealthy 假象）→ 改双引号；
  ② broker 容器形态注册地址：`brokerIP1=127.0.0.1` 是宿主形态遗留，容器网络下 worker 连
  注册地址=连自己（closeChannel 死循环、判题全挂）→ 新建 `deploy/rocketmq/broker-compose.conf`
  （brokerIP1=rocketmq-broker，除 brokerIP1 外与 broker.conf 必须一致）；
  ③ broker 有效堆限 1g（实测 RSS 1.19G 吻合）。
- **T2 全绿** ✅：分批重建全栈（infra+init → user/problem/contest/ai → auth/submission/gateway →
  worker/web），8 端口 health 全 UP + web 5174 返回新 dist + verify-p1-login **43/0**。
- **T3 全绿** ✅：verify-p3 **21/0**。容器化形态 `/cj-sandbox` 走真实 Linux 权限，
  `DockerSandbox` 沙箱 uid 1000:1000 → **1001:1001**（artifactDir 属主=worker uid 1001，
  uid 1000 时编译沙箱 `cannot create /work/stdout: Permission denied`）；契约沉淀
  DEPLOYMENT.md §3.3.1。
- **T9 运行时验证** ✅：`cj_judge_e2e_seconds_*` 6 条 verdict 序列入 Prometheus，
  与 verify-p3 实测 12 次终态对齐（AC=1/MLE=2/TLE=2/WA=1/RE=5/CE=1）。
- **T6 落地+验证** ✅：`/v1/problems/page` 与裸路径一致；verify-authz **73/0**
  （脚本修复：内部直连对照用户 id 硬编码 1 不存在 → `STATUS_UID=2001`）。
- **T10 全链路** ✅：gateway 不依赖 judge-common（设计约束）→ 本地 `logback-spring.xml` +
  同包名 `JsonLogLayout` 拷贝（三处同步纪律）；promtail 文件名正则修复（`\\` 字面反斜杠
  永不匹配容器内正斜杠路径 → application 标签从未产出）；Loki 实测 gateway 39 行 `| json`
  解析成功 + auth 流 requestId 命中真实请求行。
- **⚠️ 新坑（第五轮实测）**：
  1. **promtail 优雅停机会把内存 positions 落盘** —— `rm positions.yaml` 后 `docker restart`
     等于没删（停机 flush 覆盖），文件被 Seek 到 EOF 不重读；**要新标签生效就重启业务进程
     产生新行**，别指望 promtail 重读历史。
  2. **Loki 数据随监控栈卷重建丢失**（T2 期间 infra 重建 → 早前 auth 流推送蒸发）；
     证据要能随时重产（one-off 进程 + 打请求）而不是只存 Loki。
  3. **gateway（WebFlux）无 servlet RequestIdInterceptor**，prod JSON 行无 requestId 属设计
     （JsonLogLayout 缺省省略）；requestId 全链路证据由 servlet 服务（auth）承担。
  4. **prod one-off 是会话级进程**：会话结束即被回收，其 prod 日志文件是「历史证据」；
     复验要重新拉起（java -jar + prod profile，无需注入 .env 凭据——启动行与匿名
     public-read 请求不需要密钥）。
- **T5 维持 BLOCKED**：trivy DB 双源被拦（ghcr.io 可达但 ~7KB/s，119MB ETA 5 小时，放弃等待）；
  ZAP 镜像本地不存在、docker.io 拉取被拦。命令与镜像清单已备好，待有网环境。

### 5.17 2026-10-03 第六轮：U0 预检 + U2 JaCoCo contest 补测收口

**输入**：`docs/HANDOFF-PROMPT-2026-09-30.md`（U0–U9）。逐项证据见执行报告「第六轮」表。

- **U0 预检**：Docker daemon 仍 **500**（dockerDesktopLinuxEngine 内部错误）；宿主空闲
  4.4/15.2GB（vmmemWSL 1.9GB，低于 soak 预检线 6GB）；无 CodeJudge 服务在跑。
  按 U0 约定 AI 只做预检不重复拉起手段（硬约束 13），**U1/U3 标注 BLOCKED 未硬凑**。
- **U2 contest 补测（本轮主体）** ✅：
  1. **6 个测试类 66 例**（contest 18 → 84 例，`mvn -pl judge-contest test` 84/0）：
     lifecycle 状态推进（CAS 成功才推送/封榜幂等/单竞赛异常不拖垮整轮）、result handler
     （订阅契约/坏报文丢弃/吞异常不重投）、rank service（applyResult 守卫 + **Lua 参数逐位断言**
     + freeze 锁回滚/空榜清 key + **三段编码解码渲染** + 榜外我的名次）、pusher（双视图合并/内容
     签名去重/CONTEST_STATUS 绕过去重）、service（建赛校验/缺省推导/自代理事务/CAS/context
     实时状态）、WS 端点（**full 视图特权门 fail-closed**/订阅上限/快照失败不断连）。
  2. **覆盖率**：contest LINE **0.047 → 0.748**（jacoco.csv 702/237）。
  3. **双棘轮落 pom**：根 pom 全局下限 0.04 → **0.10**（新最低=common 0.120 留余量）；
     judge-contest 模块专属 check-contest **0.70** 锁高位防回退。
  4. **CI 同命令实证**：全 reactor `mvn -B -ntp verify` **BUILD SUCCESS**（11 模块 321 例全绿、
     双门禁过线）——CI 首日不会因覆盖率门禁变红。顺手修正 ci.yml 过时注释（「起步 30%」→ 实门禁）。
- **新坑（本轮实测，续接者必读）**：
  1. **MyBatis-Plus `LambdaUpdateWrapper.set()` 急切解析实体列名**，单测无 MyBatis 容器会抛
     `can not find lambda cache for this entity` → 测试里用
     `TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Contest.class)`
     手动注册（`@BeforeAll`）；纯 `LambdaQueryWrapper` 走 mock mapper 不触发（懒解析）。
  2. **同一测试方法内对同一 mock 方法二次 `when(...).thenThrow/thenReturn` 会立刻触发第一个桩**
     （`when(mock.call())` 本身是一次调用）→ 改用 `doReturn/doThrow` 或拆成独立测试方法。
  3. **mock 服务返回 null 的返回值会让 `anyString()` 桩失配**（WS 测试 `rankService.topic` 未 stub
     → register 收到 null topic → 桩不命中 → 静默走「订阅达上限」分支）——凡是下游 mock 返回
     String/对象再被 `anyXxx()` 匹配的桩，先把返回值 stub 掉。
  4. **IDE（ECJ）会往 `target/test-classes` 写带错误标记的 class**（运行时报
     `Unresolved compilation problem` 而非编译失败）——遇到先 `mvn clean test` 排除假象再查代码。
- **U4–U6/U8/U9**：未动，卡点与所需配合维持 HANDOFF 记载（见执行报告「第六轮」C 类汇总）。
- **遗留**：本轮改动（6 测试类 + 2 pom + ci.yml 注释 + 3 文档）**尚未 commit**，待用户确认后入库。

### 5.18 2026-10-03 傍晚：U1 首攻——soak.jmx 定时重登落地；WSL2 VM 判定须重启宿主

- **soak.jmx 定时重登（PERF.md §3.8.1 三选一之 a，已落地）**：登录块 `OnceOnlyController` →
  主循环 `IfController`（条件 `${__jexl3(${LOGIN_TS} == 0 || ${__time()} - ${LOGIN_TS} >
  (reloginAfterSec + threadNum × reloginJitterSec) × 1000)}`，默认 1500s+10s/threadNum）+
  JSR223 后置登记 `LOGIN_TS`（**仅登录成功才登记**，失败回退 60s 重试防限流风暴）。
  10min shakedown 实测 18/18 登录成功、0 登录失败——旧计划 30:10 必现的 401 风暴源头已移除；
  25min 续签点待 1h 全量自然验证。不动生产代码，`-J` 可覆盖参数。
- **`run-perf.py -J` 参数必须空格分隔**：`-JtgBrowse.duration=600` 连写会被 argparse 拆散
  （报 `unrecognized arguments: .duration=600`）——短选项后必须有空格再接值。
- **WSL2 VM 当日三段病态（证据链完整，已停止恢复循环）**：晨间 daemon 崩溃；16:22 杀进程 +
  `wsl --shutdown` + explorer 拉起恢复（20s 就绪、探活 10ms、全栈 21 容器自动拉起 8 服务
  healthy——**compose restart 策略 daemon 恢复即自愈，重启后无需手工起栈**）；16:29 soak 上压
  30s 内劣化（2500→6/s、全接口 P95=30s 超时，**非 401**）；16:50 再恢复后**无负载** 4min 内
  daemon API 又 500、宿主→网关单请求 12.1s、vmmemWSL CPU 0%、宿主无换页。判定：病灶在
  **宿主↔VM 通信层（vmcompute/HNS）**，`wsl --shutdown` 重置不掉，**唯一正解重启 Windows**。
  教训：同日多次 VM 崩溃后恢复存活时间递减，别循环恢复，直接请用户重启宿主。
- **U1 剩余路径**（宿主重启后）：Docker Desktop 启动 → 栈自动拉起 → 10min shakedown 过关 →
  1h 全量（分离进程启动防会话回收 + .jtl 落盘事后复盘）→ RUNBOOK §3.2 观测 +
  verify-p1-login 43/0 + 数字补 PERF.md §3.8.2。

### 5.19 2026-10-04 第七轮：U0 复检 + 完成态复核（U2 实证 / soak.jmx 就绪确认）

- **U0 复检（16:37）**：Docker daemon **未运行**——npipe `dockerDesktopLinuxEngine` 管道不存在、
  无任何 docker 进程、vmmemWSL 不存在。较 10-03「daemon 500」更进一步，属 **Docker Desktop
  本体未启动**（疑似宿主已按 §5.18 结论重启，但 Desktop 未拉起）。宿主空闲 **6.36/15.22GB**
  （≥ soak 预检线 6GB，达标）。按硬约束 13 不重试拉起，用户动作 = 直接启动 Docker Desktop
  （若 VM 病态依旧再重启宿主一次）。无 CodeJudge 服务在跑（仅 2 个 IDE java 进程 0.4/0.3GB）。
- **U2 完成态实证复核（不重做）**：`mvn -pl judge-contest test` **84/0 BUILD SUCCESS**（8.6s）；
  jacoco.csv 实测 LINE **702/237 = 0.748**，与 §5.17 记录一致；双棘轮在 pom
  （根全局 0.10 / contest check-contest 0.70）。
- **U1 前置（§5.18 soak.jmx 定时重登）git diff 复核在位**：IfController 条件
  `LOGIN_TS == 0 || now - LOGIN_TS > (1500 + threadNum×10)×1000` + JSR223 仅成功登记、
  失败回退 60s。1h 全量仍 BLOCKED 待 Docker。
- **工作区核对**：第六轮 + U1 首攻改动**仍未 commit**（7 文件：ci.yml 注释 / 根 pom 棘轮 /
  contest pom 棘轮 / soak.jmx 续签 / 3 文档；+6 个新测试类未跟踪），待用户确认后入库。
- 未动：U3（Docker+外网双卡点）/ U4 / U5 / U6 / U8 / U9（卡点同 HANDOFF）；U7 属上线后动作。

### 5.5 P6 复现序列（可直接复制）

```bash
docker-compose up -d                                   # 基础设施 9 容器
python scripts/build-sandbox-images.py                 # 4 个沙箱镜像（首次必需）
python scripts/dev-start-backend.py --wait             # 8 服务 9080–9087
cd deploy/monitoring && docker-compose --env-file ../../.env -f docker-compose.monitoring.yml up -d && cd ../..
python scripts/verify-p6.py                            # A–H 段，约 40s
```

⚠️ **压测前必须先放宽登录限流**，否则登录吞吐恒被钳在 2 req/s（详见 `docs/DEPLOYMENT.md` §4.1）：

```bash
GW_LOGIN_RATE_REPLENISH=500 GW_LOGIN_RATE_BURST=1000 \
    python scripts/dev-start-backend.py judge-gateway --wait
```

⚠️ **只重启单个服务时，按端口 kill 对应 JVM，不要杀 `dev-start-backend.py --wait` 看护父进程** ——
子进程由宿主 Job Object 托管，父进程一死全部服务被回收（2026-09-21 实测）。

### 5.6 🔴 第 4 项「凭据轮换」—— 必须人工在终端执行的命令

本机安全策略会拦截对 `.env` 与 `rotate-credentials.py` 的读取（授权提示超时），
这一步需要在你的终端里手工执行，不要交给自动化流程。按下面三步做。

```bash
cd /d/1/CodeJudge

# ① 预演：只打印将要改哪些值、新值强度如何，不落盘（可反复跑）
python scripts/rotate-credentials.py --rotate

# ② 确认无误后落盘；脚本会同时给出 DB / Redis / MinIO / Grafana 的同步命令
python scripts/rotate-credentials.py --rotate --write

# ③ 把脚本打印的同步命令逐条执行（这些是"改了 .env 但未同步"的经典漏项）：
#    - MySQL 应用账号口令
#    - MinIO MINIO_ROOT_PASSWORD
#    - Grafana admin 口令（这一步做完，preflight B4 才会由 FAIL 转 PASS）
```

然后**重启全部服务与监控栈**，并复验：

```bash
python scripts/preflight-check.py            # 期望 B4 转 PASS
python scripts/check-hardcoded-defaults.py   # 无 FAIL；2 条 WARN 需确认已被环境变量覆盖
python scripts/verify-p1-login.py            # 换密钥后登录链路必须仍然通过
```

⚠️ 三条注意：
1. **`CJ_JWT_SECRET` 换掉后所有已签发的 Token 立即失效**（用户需重新登录）—— 这是预期行为，
   但请选在无人使用的时间窗执行。
2. **`.env` 不入 git**（已 gitignore），换下来的旧值不会留在仓库里；但**旧值仍可能留在
   备份/截图/聊天记录里**，生产上应视为已泄露并同步轮换上游。
3. 两处代码兜底口令（`CJ_ADMIN_BOOTSTRAP_INIT_PASSWORD` / `CJ_USER_DEFAULT_PASSWORD`）
   **不在 `.env` 里也照样能启动**，属于"静默生效"—— 请显式写进 `.env` 并确认非 `123456`。

### 5.1 P5 已通过端到端验收（2026-09-21）

**PASS=46 / FAIL=0 / SKIP=0**（`scripts/verify-p5.py`，A–M 段）。复现序列：

```bash
docker-compose up -d                        # mysql / redis / postgres / rocketmq 4 容器
# ⚠️ 旧数据卷必须重放一次 init.sql，补 ai_review.embedding 列（全程 IF NOT EXISTS，可重复执行）
docker exec -i codejudge-pg psql -U postgres -d judge_ai -v ON_ERROR_STOP=1 < deploy/pgvector/init.sql
python scripts/dev-start-backend.py --wait  # 8 个服务 9080–9087
python scripts/verify-p5.py                 # A–M 段，降级模式约 30s
```

⚠️ 未配置 LLM（`.env` 里 `CJ_LLM_ENABLED=false`）时点评走**结构化降级**，脚本自动断言对应分支，
**不把"未配置 LLM"判为失败**。配置 `CJ_LLM_API_KEY` 后应复跑（G 段会切到「非降级」分支）。

⚠️ **验收脚本要用两套头**（本次踩过，务必理解）：judge-ai **不解析 JWT**，只信任网关注入的
`user-info` / `role-info`。故经网关的调用（`/submissions`、K 段）用 `Authorization: Bearer`，
**直连 9087 的调用必须自行还原那两个头**（脚本从 JWT 的 `userId` / `roleId` claim 解出，与网关同源）。
直连时不带 → 一律 401。**这是设计，不是缺陷。**

### 5.2 ⚠️ P5 验收捕获的核心缺陷（已修，全局性教训）

`AiReviewRepository.insert` 原用 `Statement.RETURN_GENERATED_KEYS` + `KeyHolder.getKey()` 取自增主键。
**PG 驱动在该模式下把整行所有列都作为 key 返回**，`getKey()` 直接抛：

```
InvalidDataAccessApiUsageException: The getKey method should only be used when a single key is returned.
The current key entry contains multiple keys: [{id=2, submission_id=..., ...}]
```

MySQL 驱动只返回主键列 —— 这是**典型的 MySQL→PG 迁移陷阱**。

后果是**静默数据损坏**：INSERT 成功但取不到 id → `persistPending` 返回 null → START 事件无 `reviewId`
→ `persistSuccess(null, ...)` 首行 `return` → **正文永不落库、记录永久停在 `status=0`**，
且非流式接口返回**空 body**。编译通过、静态审视无异常，**只有真实连 PG 执行才暴露**。

已改用 PG 惯用法 `INSERT ... RETURNING id` + `queryForObject(sql, Long.class, args...)`。

> **硬规则（P6 及以后）**：凡 PG 表插入后需要自增主键，**一律用 `RETURNING`**，
> 禁止 `RETURN_GENERATED_KEYS` + `KeyHolder`。

**另一条通用教训**：验收脚本里任何 `all(...)` / `not any(...)` 形式的断言，
**必须同时断言样本非空** —— 否则零事件时恒真，会把「全链路失效」装饰成「全绿」。本次即修了 3 处这类假绿。

### 5.3 P5 已收口，不要重复做

`judge-ai(9087)`：SSE 流式点评（`START→RETRIEVAL→DELTA×N→END`）、非流式点评、点评历史、
pgvector 双路召回（题目知识 + 历史点评）、断线重连（`Last-Event-ID` 回放）、
并发配额、取消落库、结构化降级、知识库 CRUD（限教师/管理员）、
`ai_review#REQUESTED` MQ 预生成（默认关闭）。
前端示例见 `docs/examples/ai-review-sse.html` + `aiReviewStream.js`，接入说明见 `docs/P5-前端SSE接入说明.md`。

### 5.4 P6 范围：judge-web(5174) + 可观测 + 压测 + 文档

- 前端（Vue/React 均可）：题目列表 / 详情 / 在线编辑提交 / 判题进度（WS）/ 竞赛榜（WS）/ AI 点评流式渲染。
- 可观测：Prometheus 指标（actuator 已开 `prometheus` 端点）、Grafana 面板、日志聚合。
- 压测：`D:\1\jmeter` + `zx-learn/perf-test` 资产；重点指标是**判题吞吐**与 **SSE 并发连接数**。
- 文档：`README.md` 收口、部署手册、API 汇总。
- P5 遗留**增强项**（不阻塞收口）：SSE 并发上限（默认 200）压测、LLM token 用量落库（`tokens_in/out` 目前恒 null）、点评质量评估回归集、**接真实 LLM 后复跑验收**。

**P6 注意**：前端跨域用网关的 `allowedOriginPatterns`（已同时覆盖 `localhost` 与 `127.0.0.1` 且端口通配，
**不要改成 `*`** —— 与 `allow-credentials: true` 组合会被浏览器拒绝）。

---

## 6. 记忆机制说明（**重要，决定你会不会"忘事"**）

| 场景 | 会不会记得 | 原因 |
|---|---|---|
| **同一工作区**（`D:\1\CodeJudge`）开新对话 | ✅ 记得 | 系统自动注入 `.workbuddy/memory/MEMORY.md` |
| 换工作区 / 换目录开对话 | ❌ 不记得 | 记忆按工作区隔离 |
| **换机器 / 重新 clone 仓库** | ❌ **不记得** | `.workbuddy/` 在 `.gitignore` 里（见 §7），不随 git 走 |
| 细节条款（沙箱 12 项、8 条验收标准、ZSet score 公式） | ⚠️ **可能丢** | `MEMORY.md` 有 3000 字符/会话上限，只注入蒸馏版 |

**因此**：`docs/` 目录下的文档（本文件、PLAN.md、PROMPT-ARCHIVE.md、P1-REPORT.md）**才是真正的持久化载体** —— 它们入 git、跟仓库走、不受字符上限约束。`.workbuddy/memory/` 只是加速索引。

> 想做到"完全换台机器也不丢"：把 `docs/` 提交到 git 即可（已实现）。如果连本地测试凭据/复盘也要跨机器同步，再把 `.gitignore` 第 82–83 行的 `.workbuddy/` 移除 —— 但**那会把本地环境信息一起带进仓库，需你确认后再做**。

---

## 7. 本机环境关键约束（踩过的坑，别重复踩）

- **Maven**：Git Bash 下必须用 `mvn.cmd`（`/d/1/apache-maven-3.9.6/bin/mvn.cmd`），`bin/mvn` 会报 classworlds 错误。
- **`SERVER__PORT` 注入（已实测确证）**：宿主向子 JVM 注入 `SERVER__PORT=56298` + `SERVER__HOST=127.0.0.1`，
  Spring 松散绑定后覆盖 `server.port` → 服务绑错端口启动失败。直接 `java -jar` 时必须
  `unset SERVER__PORT SERVER__HOST SERVER_PORT SERVER_HOST`，或显式加 `--server.port=<端口>`。
- **`.sh` 一律无法执行**：执行 `.sh` 会被路由到 `wsl.exe`，而 `wsl.exe` 在程序黑名单中，命令直接被中止（连单行 `echo` 都跑不起来）。脚本一律用 `.py`；`python` 也须用绝对路径（`python` / `python3` 是 WSL shim，同样撞黑名单）。
  ⚠️ **两个解释器装的包不同，选错会 `ModuleNotFoundError`（2026-09-22 实测）**：
  `C:\Users\20670\.workbuddy\binaries\python\envs\default\Scripts\python.exe` 有 `requests` + `websocket-client`（**验收/采集脚本用这个**）；
  `...\versions\3.13.12\python.exe` **缺 `websocket-client`** → `verify-p4.py` / `collect-metrics.py` 会直接崩在 import。
  判断法：`<python> -c "import websocket"`。
- **`taskkill`** 须写 `MSYS_NO_PATHCONV=1 taskkill /F /PID <pid>`（`//F` 写法报错）。
- **`java.io.tmpdir`**：必须传 Windows 路径（`pwd -W` → `D:/1/CodeJudge/logs/tmp`），MSYS 的 `/d/1/...` 会被 JVM 解析成 `\d\1\...`。
- **`docker compose`** 插件不可用 → 用独立 `docker-compose`。
- **⚠ 服务常驻启动用 `python scripts/dev-start-backend.py --wait`（P3 新增，当前唯一可靠方式）**：
  ① Git Bash 里 nohup 启动的子 JVM 会随工具调用结束被整体回收（Job Object kill-on-close，实测）；
  ② PowerShell 5.1 的 Start-Process 在本宿主因环境变量 Path/PATH 双写报「已添加项。字典中的关键字: Path」（实测）；
  ③ 无 BOM 的 UTF-8 .ps1 在 PS5.1 下按 GBK 解析中文注释直接语法错（已给 ps1 加 BOM 修复）。
  `--wait` 看护模式需用 run_in_background 挂起，父进程驻留期间子 JVM 存活。
- **MySQL PreparedStatement 不支持 `INTERVAL ? MILLISECOND`**（P3 实测）—— 时间运算一律在 Java 侧算好再传参。
- **Git Bash 手工调 docker run 时 MSYS 会改写 `-v` 路径与 `/bin/sh` 参数**（MSYS_NO_PATHCONV 也不完全可靠）—— 调试容器请直接看 worker 日志或用 python 测试。
- **RocketMQ broker store 卷（2026-09-30 已修复）**：当初镜像内无 `/home/rocketmq/store`，命名卷挂载点 root:root，uid=3000 无写权限 → 启动即静默退出（ExitCode=253、日志为空）。现由一次性 `rocketmq-store-init` 容器（user root，chown 3000:3000）+ `service_completed_successfully` 门控解决，消息已持久化。⚠️ 首次 `up` 若见 `Exited(0)` 的 store-init 容器属预期。
- **MySQL**：`.env` 里 `MYSQL_PASSWORD` 必须与 `MYSQL_ROOT_PASSWORD` 一致（底座约定应用直接用 root）。
- **judge-gateway**：底座缺 `spring-boot-starter-actuator`，已在 P1 补上。
- **MinIO 已入 app profile（2026-09-30）**：不再是独立 storage 常驻，`--profile app` 时随 submission/worker 的 depends_on(healthy) 拉起；storage profile 保留兼容调试。本机 registry 拉取仍可能被拒，需预拉镜像。
- **沙箱运行时**：本机 Docker Desktop 仅注册 `runc` 系列，**无 `runsc`（gVisor）** → 默认加固 runc + seccomp，gVisor 作可选 `SANDBOX_RUNTIME`。

---

## 8. 与本项目无关但需要知道的事

本机同时运行着 zx-learn 的 10 个容器（zx-learn-mysql/redis/pg/mq-*），端口已全部错开。**改基础设施配置前先 `docker ps` 确认别误伤 zx-learn。**
