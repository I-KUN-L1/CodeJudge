# P6 阶段验收报告

> 阶段目标：**judge-web 前端 + Prometheus/Grafana 可观测 + JMeter 压测 + 文档收口**。
> 完成日期：2026-09-21 ｜ 验收脚本：`scripts/verify-p6.py` ｜ 结果：**PASS=88 / FAIL=0**
> 本阶段完成后，六个阶段全部交付。

---

## 一、交付清单

| # | 交付项 | 位置 | 状态 |
|---|---|---|---|
| 1 | 前端应用 | `judge-web/`（Vue 3 + Vite + Element Plus + Pinia） | ✅ |
| 2 | 指标埋点 | 8 个服务的 `application.yml` + `JudgeMetricsConfig` | ✅ |
| 3 | 监控栈 | `deploy/monitoring/`（Prometheus / Alertmanager / Grafana） | ✅ 真实起容器 |
| 4 | 压测资产 | `perf-test/`（jmx × 2 + 播种 + 运行器 + 门槛判定） | ✅ 真实跑通 |
| 5 | 文档 | `README.md` + `docs/{ARCHITECTURE,API-REFERENCE,DEPLOYMENT,PERF}.md` | ✅ |

---

## 二、前端（judge-web）

### 2.1 技术选型与结构

```
judge-web/src/
├── api/          base.js / http.js（axios 封装，按 body.code===200 判成功）/ index.js
├── components/   CodeEditor / AiReviewPanel / EChartPanel / MarkdownView / VerdictTag
├── composables/  useSubmissionProgress（判题进度 WS）/ useContestRank（榜单 WS）
├── layouts/      AppLayout.vue（响应式外壳）
├── router/       index.js（路由守卫 + meta.public / meta.roles）
├── stores/       user.js（Pinia，含 loadProfile 幂等）/ theme.js
├── utils/        ws.js / sse.js / prometheus.js / markdown.js / highlight.js / format.js
└── views/        学员 9 个 + teacher/ 5 个 + admin/ 4 个
```

### 2.2 覆盖的核心流程

| 侧 | 页面 | 关键能力 |
|---|---|---|
| 公共 | 登录 / 注册 | 双 Token 落库、redirect 回跳 |
| 学员 | 题库列表 / 题目详情 | 分页、标签筛选、Markdown 题面渲染 |
| 学员 | 题目详情内嵌编辑器 | 多语言选择、在线编写与提交 |
| 学员 | 提交记录 / 判题详情 | **判题进度 WebSocket** + **AI 点评 SSE 流式渲染** |
| 学员 | 竞赛列表 / 竞赛详情 | **榜单 WebSocket** 实时刷新 |
| 学员 | 个人中心 | 资料、改密 |
| 教师 | 题目管理 / 新建 / 编辑 / 创建竞赛 / AI 知识库 | 隐藏用例管理 |
| 管理 | 用户管理 / 标签管理 / 判题集群 / 系统监控 | 运营与运维视图 |

### 2.3 三个设计决策

**① 除登录注册外，全部路由要求登录。**
网关 `JwtProperties.excludePaths` 只放行 `/accounts/login`、`/accounts/admin/login`、
`/accounts/refresh`、`/accounts/password/first-change`、`/students/register`、`/jwks`、
`/v3/api-docs`、`/doc.html` —— **`/problems/**` 不在白名单**，匿名访问题目列表会被网关 401。
前端一律按"需登录"处理，避免出现「页面打开了但列表永远为空」的假象。

**② 路由守卫不把"后端不可达"伪装成"登录过期"。**
`loadProfile()` 失败时**保留 token 与当前页面**，由页面自身报错，而不是 `catch` 后跳登录页。
把服务故障伪装成登录失效，是最难排查的一类问题。

**③ axios 拦截器按业务码判定，不看 HTTP 状态。**
后端统一响应 `R<T>`：**成功是 `code=200`（不是 1），消息字段是 `msg`（不是 `message`）**。
HTTP 200 但 `code=403/1001` 的情况（权限拒绝、幂等冲突）必须由业务码捕获。

---

## 三、可观测性

### 3.1 指标埋点（8 个服务）

```yaml
management:
  endpoints.web.exposure.include: health,info,metrics,prometheus
  metrics.tags.application: ${spring.application.name}      # 关键：所有指标带 application 标签
  metrics.distribution.percentiles-histogram.http.server.requests: true   # 支撑 histogram_quantile
```

自定义业务指标（`JudgeMetricsConfig`，3 个 Gauge，全部经 `safeGauge` 兜底异常返回 NaN）：

| 指标 | 含义 | 为什么必须单独埋 |
|---|---|---|
| `judge_queue_backlog` | 待判队列积压 | 积压时用户看到的是「排队中」**不是失败**，HTTP 指标全绿 —— 只能靠它发现 |
| `judge_dead_tasks` | 重试耗尽的死信任务 | 死信 = 提交永久不出结果，没有「轻量」档，直接 critical |
| `judge_workers_online` | 在线判题机数 | 为 0 时平台实质不可用（全部提交永久排队） |

> 取数走 `WorkerViewService` 的**无鉴权原始版本**（`queueBacklogRaw` 等）：
> Prometheus 抓取没有登录态，若复用带归属校验的方法会全部 403。

### 3.2 监控栈（`deploy/monitoring/`，可独立部署）

| 组件 | 端口 | 版本 | 配置要点 |
|---|---|---|---|
| Prometheus | 9090 | 2.54.1 | 单 job `codejudge`，8 个 target，15s 抓取 |
| Alertmanager | 9093 | 0.27.0 | null receiver（本地不外发）+ `inhibit_rules` 抑制派生告警 |
| Grafana | 3001 | 11.3.0 | provisioning 自动装配数据源与看板，`allowUiUpdates: false` |

**11 条告警规则，4 组**（`rules/codejudge-alerts.yml`）：

| 组 | 规则 | 阈值依据 |
|---|---|---|
| 可用性 | ServiceDown / MetricsMissing | 抓取失败 1m / 指标整体缺失 3m |
| HTTP 质量 | HighErrorRate / HighLatencyP95 / SubmitFailureBurst | 5xx>5% / P95>1s / 提交 5xx>0.5次每秒 |
| 判题链路 | QueueBacklogHigh / DeadTasksPresent / WorkersAllOffline | 积压>200 / 死信>0 / 在线机=0 |
| 资源 | JvmHeapHigh / HikariPoolSaturation / ProcessCpuSustainedHigh | 堆>85% / pending>5 / CPU>90% |

每条规则的 `annotations` 都写明「**用户侧看到什么 + 先查哪里**」——
否则半夜收到告警只会让人去翻 20 个面板。

**两个易踩点**：
- `workers_online` 用 `absent(...) or ... == 0` 两种都抓：只写 `== 0` 会漏掉「指标彻底消失」
  （judge-submission 自己挂了）——而那种更严重。
- `HighErrorRate` 加了 `and rate(...) > 0` 避开 0/0：无流量时 NaN 会让比较静默失效。

### 3.3 Grafana 看板

`CodeJudge` 目录下 4 个看板（由 `gen_dashboards.py` 生成，入库 JSON 为准）：
服务总览 / HTTP 性能 / 判题链路 / AI 点评。

---

## 四、压测

详见 **[docs/PERF.md](PERF.md)**（实测数据与瓶颈分析）。这里只记方法与结论要点。

| 资产 | 说明 |
|---|---|
| `perf-test/jmx/smoke.jmx` | 正确性冒烟：1 线程组 / 8 个 sampler |
| `perf-test/jmx/load.jmx` | 混合负载：6 线程组 / 14 个 sampler（AI 点评默认关） |
| `perf-test/seed-users.py` | 账号池播种（幂等，内置 429 退避重试） |
| `perf-test/run-perf.py` | 无 GUI 运行器 + `.jtl` 解析 + 门槛判定（退出码可用于 CI） |

**四个设计要点**（详见 `perf-test/README.md` §3）：

1. **登录放 Once Only Controller** —— 否则压的是 BCrypt（≈100ms/次）而非业务接口；
2. **每次提交代码必须唯一**（插 `${__UUID()}`）—— 否则命中幂等快路径，
   压测变成「压幂等查询」，判题链路一次都没走到（**最容易发生的假压测**）；
3. **提交场景 ramp-up 拉长到 60s** —— 判题是重资源操作，瞬时打满只能测出「队列崩了」；
4. **三元断言**：HTTP 状态码 + **业务码 `"code":200`** + 时长硬阈值。
   只断 HTTP 状态码会漏掉「HTTP 200 但 `R.fail`」。

---

## 五、验收实证（`scripts/verify-p6.py`）

**PASS=88 / FAIL=0**，耗时 40.1s。八段：

| 段 | 覆盖 | 结果 |
|---|---|---|
| A | 前端构建产物（`dist/`）+ 后端 8 个 jar | ✅ |
| B | 8 服务 `/actuator/health` 与 `/actuator/prometheus` 均 200 | ✅ |
| C | 指标契约（`application` 标签、histogram 桶、JVM/Hikari/GC） | ✅ |
| D | 队列对账（积压归零、死信为已知历史值） | ✅ |
| E | 网关端到端（登录/题库/竞赛/提交/判题终态 `status=SUCCESS`+`verdict=AC`/幂等） | ✅ |
| F | 监控栈真实起容器（8 target 全 up、11 条规则、5 看板、数据源自动装配） | ✅ |
| G | 压测资产（jmx 结构、断言、账号池播种、JMeter 可执行） | ✅ |
| H | 文档完整性（README + 4 篇 docs 关键小节） | ✅ |

可用 `--no-monitor --no-judge` 跳过起容器与真实判题快速跑子集。

---

## 六、本轮发现并修复的缺陷

### 6.1 🔴 判题队列积压永久虚增（真缺陷）

**现象**：`judge_queue_backlog` 只增不减。

**根因**：转死信的提交此前**不会被从待判 ZSet 摘除** —— DEAD 走 DLQ topic，
不触发结果消费端的 ZREM，于是僵尸成员永久留在 `judge:judge:queue:zset` 里。

**修复三处**：
1. `JudgeEngine#failTask` 转死信分支补 `redis.opsForZSet().remove(JUDGE_QUEUE_ZSET, ...)`；
2. `JudgeCompensationService#deadLetter` 补 ZREM；
3. 新增 **`queueReconcileScan()`（每 60s）对账**，摘除历史遗留僵尸成员。

**实证**：积压 2.0 → 0.0；日志确认摘除 `[2101616222834601986, 2101616402053017602]`。
修复后再次观察到「瞬时 2.0 → 60s 内归零」，证明对账扫描在真实工作。

### 6.2 🔴 统一响应契约写错（影响面最广）

全项目文档、JMeter 断言、验收脚本、播种脚本一度按 **`code=1`** 判成功。
实际 `R.ok()` 是 **`code=200`**，消息字段是 **`msg`** 而非 `message`。
已全量修正（`docs/API-REFERENCE.md`、`*.jmx`、`verify-p6.py`、`seed-users.py`）。
前端与既有 `verify-p1..p5.py` 本来就正确。

> 教训：契约错误**不会让任何东西报错**，只会让断言恒真或恒假 —— 属于「假绿」家族。

### 6.3 🟡 登录限流与压测/播种的冲突

`login-rate-limit` 路由的 key = `rate:login:{ip}:anon`，**压测机只有一个出口 IP**，
100 个登录线程共用同一个令牌桶，吞吐被钳死在 2 req/s。

**修复**：
1. 阈值改为可配置 `${GW_LOGIN_RATE_REPLENISH:2}` / `${GW_LOGIN_RATE_BURST:5}`
   —— **默认值保持生产安全水位不变**；
2. `seed-users.py` 把 HTTP 429 单独识别并**退避重试**（播种是低频批量运维操作，
   不该被防爆破误伤；此前 429 的非 JSON 响应体被误报成「账号有问题」）。

**实证**：默认配置 12 次瞬时登录 = **5×200 + 7×429**（严格符合突发 5 / 2 req/s）；
放宽后 20 次瞬时登录 = **20×200**。播种 120 个账号：30 复用 + 90 新建 + **0 失败**。

### 6.4 🟡 验收脚本自身的 6 处缺陷

| 缺陷 | 后果 | 修复 |
|---|---|---|
| 账号池手机号 `f"{p}{seq:06d}"[-11:]` 拼接 | 前缀稍长就**静默截掉开头的 "1"**，产出非法号码，表现为「30 个账号全失败」 | 改 `f"{p}{seq:0{width}d}"` + 前置位数断言 |
| 播种脚本残留 `code==1` 判定 | 注册其实成功，却被判失败 | 改 `code==200` |
| 取列表首题（4005）却用 A+B 解法断言 `verdict==AC` | 得 WA，误判为系统缺陷 | 固定 `ab_pid=4001` + nonce 防幂等 |
| jar 命名误判 | `judge-common/api` 产物名不同 | 按实际命名判定 |
| Hikari 指标查网关 | 网关无 DB，永远查不到 | 改查 `judge-user:9082` |
| `run-perf.py` 对 smoke 套用 500 req/s 吞吐门槛 | smoke 是 5 线程正确性计划，吞吐天然个位数 → **每次必然「未达标」** | 吞吐门槛只对 `load` 生效，smoke 报 SKIP |

> 最后一条是典型**门槛误用**：假 FAIL 会训练人忽略结论，比没有门槛更糟。

---

## 七、本机环境新踩的坑（已写入 CONTEXT.md §7 / README FAQ）

| 坑 | 现象 | 处置 |
|---|---|---|
| **杀看护父进程会连带回收全部子服务** | `taskkill` 掉 `dev-start-backend.py --wait` 后，7 个服务全部消失 | **只重启单个服务时按端口 kill 对应 JVM，勿杀看护父进程** |
| `docker --format '{{.X}}'` 被 Git Bash 破坏 | 输出变成 `.Repository:.Tagt.Size`，`grep` 得空 → **误判「沙箱镜像不存在」** | 用 `docker images` / `docker ps` 原始输出再过滤 |
| `wmic.exe` 在程序黑名单 | `PermissionError: [WinError 5]`，且被安全策略拦截 | 改用 PowerShell `Get-CimInstance Win32_Process` |
| PowerShell 工具不回显 stdout | 命令返回 exit 0 但看不到任何输出 | 需要输出就**写文件再 Read** |
| `taskkill` 输出是 GBK | Python `text=True` 默认 utf-8 解码直接抛 `UnicodeDecodeError` | 显式 `encoding="gbk", errors="replace"` |
| heredoc 把 `\n` 变字面量 `/n` | 写入 8 个 `application.yml` 后 YAML 全部失效 | 用 Python 脚本写文件，不用 heredoc 生成 YAML |
| Prometheus 抓不到宿主服务 | 全部 target `down` | 抓取地址用 `host.docker.internal`，容器内写 `localhost` 指向容器自己 |

---

## 八、未完成 / 已知限制

| 项 | 说明 |
|---|---|
| 生产环境压测 | 本机为**压测工具与业务服务同机争抢 CPU**，数据只能作为**容量下界**，生产压测须独立压测机 |
| 告警阈值标定 | 11 条规则阈值取自本地实测基线，**上线前必须按真实容量重标** |
| Alertmanager 外发通道 | 本地为 null receiver（不外发），生产需接入邮件/企业微信 |
| AI 点评场景压测 | `load.jmx` 中默认关闭（消耗真实 LLM 额度 + 长连接污染延迟统计），需显式 `-J tgAi.enabled=true` |
| 前端自动化测试 | 未引入单元/E2E 测试框架，当前依赖构建通过 + 人工走查 |
| 教师"申请—审核"流程 | 明确不做（管理员有 `POST /teachers/register`、`POST /users` 两条开号路径，无产品缺口） |

---

## 九、结论

**P6 通过验收。** `verify-p6.py` 88 项断言全绿，含真实判题闭环、真实监控容器与真实压测资产。
随之**六个阶段全部交付**：8 个可运行服务（9080–9087）+ 前端（5174）+ 监控栈（9090/9093/3001）
+ 4 个沙箱镜像 + 压测体系 + 完整文档。

上线前仍须完成的事项见 `docs/DEPLOYMENT.md` 的「生产检查清单」
与本文 §8。
