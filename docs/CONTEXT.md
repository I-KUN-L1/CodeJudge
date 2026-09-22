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

> ⚠️ **不要**去 `conversation_search` 找总提示词原文 —— 已验证检索不到（该提示词属于当前对话，检索工具对当前会话零可见）。

---

## 3. 绝不违反的硬约束（13 条速查）

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
13. **宿主会向子进程注入 `SERVER__PORT` / `SERVER__HOST`（已实测确证）** —— WorkBuddy 终端下 JVM 环境含 `SERVER__PORT=56298`（= 宿主自身监听端口），Spring 松散绑定将其解析为 `server.port`，**优先级高于 `application.yml`**，导致服务绑错端口启动失败。绕开启动脚本直接 `java -jar` 时二选一：① `unset SERVER__PORT SERVER__HOST SERVER_PORT SERVER_HOST`；② 显式传 `--server.port=<端口>`（优先级最高，最稳）。`scripts/dev-start-backend.{sh,ps1}` 已内置清变量逻辑。
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
- **`python scripts/verify-p1-login.py` → 8 组 41 项断言全 PASS**（登录双 Token / 刷新 / 网关白名单 / 教师注册加固 / 改密 fail-closed；需 9080+9081+9082 已启动）

### P1 遗留的 3 个待确认项 → ✅ **已全部闭环**（2026-09-20，详见 `docs/P1-遗留项处置.md`）
1. **【行为变更】教师注册收紧为 STAFF-only —— 保留收紧，不回退。** 理由：教师是特权角色（可建题、可见隐藏用例），
   且管理员本就有两条开号路径（`POST /teachers/register`、`POST /users`），不存在产品缺口。
   "教师申请—审核"流程**不做**（无前端/审核台，属 P6 独立需求）。已端到端验证 7 项断言。
2. **judge-common 保持移除 Redisson。** P3/P4 的并发场景（提交幂等 / 任务分配 / 排行榜）分别由
   **MySQL 唯一索引 / MQ 消费组 / Redis ZSET 原子操作**覆盖，均不需要分布式锁。
   重新引入的触发条件已写明（需可重入+看门狗续期的复合临界区、或 Redisson 特有数据结构）。
   静态扫描：全仓仅剩 1 处注释性提及，无依赖与代码引用。
3. **`POST /accounts/login` 双 Token 下发 —— 已实测通过。** 改用脚本 `scripts/verify-p1-login.py`
   （凭据走环境变量，命令行不出现明文），**8 组 41 项断言全绿**，覆盖登录/负例/刷新/网关端到端/白名单/改密 fail-closed。
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

#### 上线前 6 项 —— 处置状态（更新于 2026-09-21）

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
> 3. **两处「代码内兜底口令」必须被环境变量覆盖**（2026-09-22 静态扫描发现，属**静默失效**家族）：
>    - `CJ_ADMIN_BOOTSTRAP_INIT_PASSWORD` —— `AdminBootstrapService` 的 `@Value` 兜底为 **`123456`**：
>      **未覆盖时首个管理员的口令就是 123456，不报任何错**。
>    - `CJ_USER_DEFAULT_PASSWORD` —— `UserService` 用常量 `FALLBACK_DEFAULT_PASSWORD` 兜底。
>    已固化为 `scripts/check-hardcoded-defaults.py`（只读源码，不碰 `.env`；FAIL=0 / WARN=2）。
> 4. **`scripts/rotate-credentials.py` 与 `.env` 在本机安全策略下无法被 AI 读取**
>    —— 授权提示超时（`SENSITIVE_APPROVAL=TIMED_OUT`）。**第 4 项须由你本人执行**，命令见 §5.6。


### 5.5 P6 复现序列（可直接复制）

```bash
docker-compose up -d                                   # 基础设施 9 容器
python scripts/build-sandbox-images.py                 # 4 个沙箱镜像（首次必需）
python scripts/dev-start-backend.py --wait             # 8 服务 9080–9087
cd deploy/monitoring && docker-compose -f docker-compose.monitoring.yml up -d && cd ../..
python scripts/verify-p6.py                            # A–H 段，约 40s
```

⚠️ **压测前必须先放宽登录限流**，否则登录吞吐恒被钳在 2 req/s（详见 `docs/DEPLOYMENT.md` §4.1）：

```bash
GW_LOGIN_RATE_REPLENISH=500 GW_LOGIN_RATE_BURST=1000 \
    python scripts/dev-start-backend.py judge-gateway --wait
```

⚠️ **只重启单个服务时，按端口 kill 对应 JVM，不要杀 `dev-start-backend.py --wait` 看护父进程** ——
子进程由宿主 Job Object 托管，父进程一死全部服务被回收（2026-09-21 实测）。

### 5.6 🔴 第 4 项「凭据轮换」—— 必须由你本人执行的命令（AI 无法代跑）

本机安全策略会拦截对 `.env` 与 `rotate-credentials.py` 的读取（授权提示超时），
**AI 助手无法代你执行这一步**。请自己按下面三步做。

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
- **`.sh` 一律无法执行**：执行 `.sh` 会被路由到 `wsl.exe`，而 `wsl.exe` 在程序黑名单中，命令直接被中止（连单行 `echo` 都跑不起来）。脚本一律用 `.py`；`python` 也须用绝对路径 `C:\Users\20670\.workbuddy\binaries\python\versions\3.13.12\python.exe`（`python` / `python3` 是 WSL shim，同样撞黑名单）。
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
- **RocketMQ broker 不能挂 store 卷**：镜像内无 `/home/rocketmq/store`，Docker 建成的挂载点是 `root:root`，进程 uid=3000 无写权限 → 启动即静默退出（日志为空）。生产需先 `chown 3000:3000`。**当前未挂载 → 数据不持久化**。
- **MySQL**：`.env` 里 `MYSQL_PASSWORD` 必须与 `MYSQL_ROOT_PASSWORD` 一致（底座约定应用直接用 root）。
- **judge-gateway**：底座缺 `spring-boot-starter-actuator`，已在 P1 补上。
- **MinIO** 目前在 `storage` profile（本机 registry 拉取被拒），上线前需移出。
- **沙箱运行时**：本机 Docker Desktop 仅注册 `runc` 系列，**无 `runsc`（gVisor）** → 默认加固 runc + seccomp，gVisor 作可选 `SANDBOX_RUNTIME`。

---

## 8. 与本项目无关但需要知道的事

本机同时运行着 zx-learn 的 10 个容器（zx-learn-mysql/redis/pg/mq-*），端口已全部错开。**改基础设施配置前先 `docker ps` 确认别误伤 zx-learn。**
