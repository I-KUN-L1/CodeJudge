# CodeJudge · 分布式在线编程评测平台

<!--
  Badges 说明：
  1. CI 徽章指向 github.com/I-KUN-L1/CodeJudge，若仓库迁移请同步更新。
  2. Coverage 为静态数值徽章。CI 每次运行会上传各模块 JaCoCo 报告 artifact
     （jacoco-report-java-21），下载后可查看 HTML 报告；门禁按棘轮式逐版本抬升，
     将最新门禁值更新到下方 badge 数字即可（或接入 Codecov 后替换为动态徽章）。
-->
[![CI](https://github.com/I-KUN-L1/CodeJudge/actions/workflows/ci.yml/badge.svg)](https://github.com/I-KUN-L1/CodeJudge/actions/workflows/ci.yml)
![Java](https://img.shields.io/badge/Java-21-orange?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.5-brightgreen?logo=springboot&logoColor=white)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
![Coverage](https://img.shields.io/badge/coverage-30%25-yellow)

> **多语言判题 / 判题机集群 / 安全沙箱 / 竞赛排行榜 / AI 代码点评** · Distributed Online Judge Platform
>
> Java 21 · Spring Boot 3.3.5 · Spring Cloud Alibaba · MyBatis-Plus · Redis · RocketMQ · Docker 沙箱 · OpenAI 兼容 LLM

<p align="center">
  <b>题库 · 判题 · 竞赛 · 排行榜 · AI 点评</b><br/>
  8 个可运行服务（9080–9087）· 4 语言安全沙箱 · 实时榜单 WebSocket · Prometheus/Grafana 可观测
</p>

## 📸 项目预览

<details>
<summary>🎬 预览素材录制方法（素材就绪后在此展示截图 / GIF）</summary>

- **页面截图**：启动前端（`judge-web`，5174 端口）后截取题库列表 / 题目详情 / 竞赛排行榜页，
  保存为 `docs/images/preview-problems.png`、`preview-contest.png`（建议统一宽度 1200px）
- **判题动图**：提交一道题后录屏展示「判题中 → 逐用例通过 → AC」的状态流转，
  或录 `asciinema` 终端会话后用 `agg` 转 GIF 放到 `docs/images/`
- **监控看板**：Grafana（3001 端口）判题链路 / 竞赛榜单看板截图

</details>

---

## ✨ 项目亮点

| 亮点 | 说明 |
|---|---|
| ⚖️ **多语言安全判题** | Java / Python / C++ / Go 四语言沙箱镜像，非 root + 只读根 + 禁网 + cgroups + seccomp 隔离执行 |
| 🏭 **判题机水平扩展** | RocketMQ 异步链路 + 心跳 + 故障转移，worker 可多实例（9085/9185/9285），队列积压定时对账自愈 |
| 🏆 **竞赛实时榜单** | Redis ZSet 实时榜 + ACM 封榜快照 + 终榜重建，榜单变动经 WebSocket 秒级推送 |
| 🧠 **AI 代码点评** | pgvector RAG 检索增强 + SSE 流式输出，OpenAI 兼容协议可插拔，LLM 不可用时自动降级为结构化模板 |
| 🔐 **统一鉴权 + 能力码** | 单一登录入口，角色由账号 `user.type` 决定；按钮级权限由后端下发能力码驱动，前端零鉴权推导 |
| 📈 **可观测内建** | Micrometer → Prometheus / Alertmanager / Grafana，判题队列积压、死信、判题机集群心跳开箱即看 |
| 🔒 **凭据外部化** | 数据库/Redis/MinIO 密码、JWT 密钥、LLM Key 全部环境变量注入（`.env.example` 模板），仓库零硬编码 |

## 🏁 已完成里程碑

> 每一项均可在 [版本记录](CHANGELOG.md) 中找到对应条目（日期 / 变更 / 影响面）。

- **P1 / P2** 🏗️ 微服务底座与题目域 — common/api/gateway/auth/user 公共底座 + 题目、题面版本、隐藏用例隔离
- **P3** ⚖️ 判题全链路（2026-09-20）— 提交幂等 + MQ 异步 + 判题机集群 + 4 语言 Docker 沙箱，验证 20/20
- **P4** 🏆 竞赛与实时榜（2026-09-20）— WebSocket 推送 + Redis ZSet 实时榜 + 封榜/终榜重建，验证 60/60
- **P5** 🧠 AI 代码点评（2026-09-21）— RAG 检索增强 + SSE 流式输出，验证 46/46
- **P6** 📊 前端与可观测（2026-09-21）— Vue 3 前端 + Prometheus/Grafana 监控栈 + JMeter 压测体系
- **v1.0.0** 🔐 上线前收口（2026-09-25）— 鉴权收敛（单登录入口 + 能力码按钮权限）+ 全仓安全审查
- **📦 Docker 化交付**（2026-10-01）— 8 服务 + 前端全部镜像化，compose `app` profile 一键起
- **🤖 真实 LLM 接入**（2026-10-06）— DeepSeek 流式点评全链路回归，RAG 降级策略验证
- **👥 演示账户体系**（2026-10-07）— 学生待提交草稿 / 教师待发布题目与竞赛演示种子

## 📊 成果与指标

> 所有数值均来自真实压测实测（负载模型与门槛见 [perf-test/README.md](perf-test/README.md)，明细见 [docs/PERF.md](docs/PERF.md)）；压测工具与业务服务同机，结果为**容量下界**而非生产预期。

| 指标 | 结果 | 验证方式 |
|---|---|---|
| 1h 长稳压测 | **8,879,545 请求 · 0 错误 · 2,466.6 req/s** | JMeter soak 计划，证据包 [docs/perf-evidence/2026-10-04-soak-1h/](docs/perf-evidence/2026-10-04-soak-1h/) |
| 浏览类接口 P95（长稳） | **8–12ms**（18 线程 × 60min 全程） | 同上 |
| 混合负载（100 并发） | **5,890 请求 · 0 错误**；登录 P95 146ms / 题单 P95 22ms / 提交 P95 91ms / 榜单 P95 60ms | JMeter load 计划，[docs/PERF.md](docs/PERF.md) §三 |
| 冒烟正确性 | 8/8 接口门槛全过 · 0 错误 | JMeter smoke 计划，[docs/PERF.md](docs/PERF.md) §二 |
| JVM 长稳表现 | 70min 窗口堆锯齿平稳，无泄漏趋势 | Prometheus JVM 指标（证据包） |
| 单元测试行覆盖率 | 30% 门禁（JaCoCo 棘轮式抬升，随 CI 更新） | GitHub Actions artifact |

## 📖 文档导航

| 文档 | 内容 |
|---|---|
| [📐 架构设计](docs/ARCHITECTURE.md) | 系统全景、模块职责、关键链路、设计取舍 |
| [🔌 接口参考](docs/API-REFERENCE.md) | 全量接口清单、请求/响应契约、SSE 事件协议 |
| [🚀 部署指南](docs/DEPLOYMENT.md) | 依赖、配置细节、启动流程、环境陷阱、生产检查清单 |
| [📈 压测实测](docs/PERF.md) | 负载模型、实测数据与瓶颈分析 |
| [🎯 SLO 与错误预算](docs/SLO.md) | 6 项 SLO 定义与错误预算策略 |
| [🗺️ 演进路线](docs/ROADMAP.md) | 防抄袭检测、special judge、K8s 迁移等规划 |
| [📝 版本记录](CHANGELOG.md) | 里程碑、问题修复、变更明细 |
| [🤝 贡献指南](CONTRIBUTING.md) | 分支命名、提交规范、PR 流程 |
| [🛡️ 安全策略](SECURITY.md) | 漏洞报告渠道、处置时限、安全基线 |

---

## 🏛️ 架构一览

```
客户端 (浏览器 / API 调用方)
        │
        ▼
┌─────────────────────────────────────────────────────┐
│ judge-gateway (9080)  统一入口 · JWT 双 Token · 限流    │
└───────────────────────────┬─────────────────────────┘
                            │ 路由分发
                            ▼
   ┌─────────────────────────────────────────────┐
   │                核心业务服务组                  │
   │  judge-auth (9081)      judge-user (9082)   │
   │  登录 / 能力码           用户 / 学员 / 教师      │
   │ ─────────────────────────────────────────── │
   │  judge-problem (9083)   judge-contest (9086)│
   │  题目 / 用例 / 标签       竞赛 / ZSet 实时榜     │
   │ ─────────────────────────────────────────── │
   │  judge-submission (9084)  judge-ai (9087)   │
   │  提交 / 幂等 / 进度 WS     AI 点评 / RAG / SSE │
   └─────────────────────────────────────────────┘
        │                        │
        ▼                        ▼
┌────────────────────┐   ┌──────────────────────────────┐
│  judge-worker 集群  │   │  MySQL(业务库) · Redis(队列/榜) │
│  MQ 消费 + 心跳      │◄─►│  RocketMQ(判题异步链路)        │
│  + 故障转移          │   │  PostgreSQL+pgvector(向量库)  │
│  沙箱执行           │   │  MinIO(长代码/附件, 可选)      │
└─────────┬──────────┘   └──────────────────────────────┘
          ▼
┌─────────────────────────────────────────┐
│ sandbox: java21 / python312 / gcc13 / go122 │
│ 非 root · 只读根 · 禁网 · seccomp · cgroups │
└─────────────────────────────────────────┘
```

> 完整架构图与模块依赖见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 🧩 模块清单

| 模块 | 职责 | 端口 |
|---|---|---|
| judge-common | 统一响应 `R<T>` / 异常 / 分页 / requestId 链路 / JWT 与 Redis 工具 / MQ 封装（公共底座） | - |
| judge-api | Feign 契约层 + 跨服务 DTO（公共底座） | - |
| judge-gateway | 路由 / JWT 鉴权 / 登录限流 / CORS / WS 握手鉴权 | 9080 |
| judge-auth | 统一登录 / 双 Token / 能力码下发 / 首个管理员安全引导 | 9081 |
| judge-user | 学员 / 教师 / 管理员基础信息 | 9082 |
| judge-problem | 题目 / 题面版本 / 测试用例 / 标签 | 9083 |
| judge-submission | 提交落库 / 幂等 / MQ 投递 / 判题进度 WS / 队列对账 | 9084 |
| judge-worker | 判题机：MQ 消费 / 沙箱执行 / 心跳 / 故障转移（可多实例） | 9085 |
| judge-contest | 竞赛 / 报名 / Redis ZSet 实时榜 / 封榜快照 / 终榜重建 / 榜单 WS | 9086 |
| judge-ai | LLM 代码点评 / RAG / SSE 流式（WebFlux） | 9087 |
| judge-web | 前端 Web 应用（Vue 3 + Vite + Element Plus + Pinia + ECharts） | 5174 |
| 监控栈 | Prometheus / Alertmanager / Grafana（独立 compose） | 9090 / 9093 / 3001 |

<details>
<summary>📁 目录结构</summary>

```
codejudge/
├── judge-common        公共底座：R<T> / 异常体系 / 链路追踪 / JWT·Redis 工具 / MQ 封装
├── judge-api           Feign 契约层 + 跨服务 DTO（Language 枚举 / 判题消息体）
├── judge-gateway       网关（/ws/** 拆分路由 + WebSocket 握手 ?token= 鉴权）
├── judge-auth          认证
├── judge-user          用户
├── judge-problem       题目（题面版本 / 测试用例 / 标签）
├── judge-submission    提交（落库 / 幂等 / MQ 投递 / 判题进度 WS / 队列对账）
├── judge-worker        判题机（MQ 消费 / 沙箱执行 / 心跳 / 故障转移）
├── judge-contest       竞赛（报名 / 实时榜 / 封榜快照 / 终榜重建 / 榜单 WS）
├── judge-ai            AI 点评（WebFlux + SSE + pgvector RAG）
├── judge-web           前端（Vue 3 + Vite + Element Plus + Pinia）
├── perf-test           JMeter 压测（jmx / 账号池播种 / 无 GUI 运行器）
├── sandbox/            沙箱镜像构建上下文（Dockerfile × 4 语言 + seccomp 规则）
├── deploy/
│   ├── mysql|redis|rocketmq|pgvector   中间件配置
│   └── monitoring/     Prometheus / Alertmanager / Grafana 独立可部署栈
├── sql/                init.sql（全量 schema）+ seed.sql（幂等种子数据）
├── scripts/            一键启动 / 沙箱镜像构建 / 各链路验证脚本
├── docs/               设计文档与实测报告
└── docker-compose.yml  基础设施编排（app / storage / tls 多 profile）
```

</details>

> 基础设施端口已整体避让 [zx-learn](https://github.com/I-KUN-L1/zx-learn)：MySQL 3307 / Redis 6380 / PostgreSQL 5433 / RocketMQ 9877（Broker 10919/10921/10922）/ MinIO 9000/9001（`storage` profile）。

---

## 🚀 快速开始

### 1. 环境要求

- JDK 21+（Temurin 21.0.x 已验证，需配置 `JAVA_HOME`）、Maven 3.9+
- Docker Desktop（基础设施 + 判题沙箱）
- Node.js 22.x（前端构建）、Python 3.11+（启动 / 验证 / 压测脚本）
- JMeter 5.6.3（可选，跑压测计划时需要）

空闲端口：9080–9087 / 5174 / 9090 / 9093 / 3001 / 3307 / 6380 / 5433 / 9877 / 10919 / 10921 / 10922 / 18081

### 2. 配置环境变量

复制 `.env.example` 为 `.env` 并填写实际值（MySQL/Redis/PostgreSQL/MinIO 密码、`CJ_JWT_SECRET` 等）：

```bash
cp .env.example .env
```

> 仓库内不包含任何默认密码或硬编码密钥；LLM 点评功能未配置 `CJ_LLM_API_KEY` 时自动降级为结构化模板，不影响本地运行。

### 3. 初始化基础设施与数据库

```bash
docker compose up -d
```

MySQL 容器首次启动会自动执行 `sql/init.sql`（建库建表）与 `sql/seed.sql`（种子账号与标签），无需手动导入。

> MinIO 归入 `storage` profile，默认不随 `up -d` 启动：`docker compose --profile storage up -d minio`

### 4. 编译

```bash
mvn clean install -DskipTests          # 10 个 Maven 模块

cd judge-web && npm install && npm run build   # 前端（开发态可跳过，见第 7 步）
```

> 若终端向子进程注入 `SERVER__PORT` / `SERVER__HOST`（表现为所有服务抢同一个端口），先 `unset SERVER__PORT SERVER__HOST SERVER_PORT SERVER_HOST`，详见 [FAQ](#-faq)。

### 5. 构建判题沙箱镜像

```bash
python scripts/build-sandbox-images.py   # java21 / python312 / gcc13 / go122 四个镜像
```

### 6. 启动后端

**一键启动（推荐）**：

```bash
python scripts/start-all.py            # 基础设施 + 8 个后端服务（逐个等待 /actuator/health）
python scripts/start-all.py --all      # 再加监控栈 + 前端 dev server
python scripts/start-all.py --no-infra # 基础设施已在跑，只起服务
python scripts/start-all.py --wait     # 看护模式，常驻并回收子进程
```

> **硬顺序只有三条**：基础设施先于任何服务；`judge-user` 先于 `judge-auth`（认证的管理员引导要经 Feign 查用户服务）；网关最后。`start-all.py` 已内置该顺序。完整启动矩阵见 [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md)。

**只起后端（看护模式）**：

```bash
python scripts/dev-start-backend.py --wait            # 全部 8 个服务，按依赖顺序
python scripts/dev-start-backend.py judge-problem     # 只起指定模块
```

### 7. 启动前端

```bash
cd judge-web && npm install && npm run dev
# http://127.0.0.1:5174 ，/api 代理到网关 9080
```

### 8. 验证

```bash
# ① 健康检查（8 个服务）
for p in 9080 9081 9082 9083 9084 9085 9086 9087; do
  echo -n "$p  health="; curl -s -o /dev/null -w '%{http_code}' http://localhost:$p/actuator/health
  echo -n "  prometheus="; curl -s -o /dev/null -w '%{http_code}\n' http://localhost:$p/actuator/prometheus
done

# ② 登录（统一入口，角色由账号自身 user.type 决定）
curl -s -X POST http://localhost:9080/accounts/login \
  -H 'Content-Type: application/json' \
  -d '{"cellPhone":"13900000001","password":"123456"}'

# ③ 提交判题（异步，通常 1-10s 内出终态）
curl -s -X POST http://localhost:9080/submissions \
  -H "Authorization: Bearer <accessToken>" -H 'Content-Type: application/json' \
  -d '{"problemId":4001,"language":"PYTHON","code":"import sys\nd=sys.stdin.read().split()\nprint(sum(int(x) for x in d))"}'
curl -s http://localhost:9080/submissions/<返回的 id> -H "Authorization: Bearer <accessToken>"

# ④ 浏览器验证前端：访问 http://127.0.0.1:5174 并登录
```

种子账号：学员 `13900000001`~`13900000005`、教师 `13900000011`~`13900000012`，密码均为 `123456`。

> - 首个管理员由 judge-auth 启动时安全引导生成（不在 SQL 中硬编码），凭据写入仓库根目录 `.bootstrap-credentials`，首次改密成功后自动删除。**首次登录后请立即改密**。
> - 登录接口有令牌桶限流（2 req/s，突发 5）；批量压测请用独立账号池，见 [perf-test/README.md](perf-test/README.md) §3.3。
> - WebSocket 端点经网关连接，握手用查询参数 `?token=<accessToken>` 鉴权：`/ws/submissions/{id}`（判题进度）、`/ws/contests/{id}/rank`（榜单变动）。
> - 在线接口文档：<http://localhost:9080/doc.html>（Knife4j）；Grafana：<http://localhost:3001>。

## 🔄 判题全链路

```
POST /submissions（幂等检查 + 落库）
        │
        ▼
RocketMQ 判题队列 ──► judge-worker 集群（MQ 消费 · 心跳 · 故障转移）
        │                     │
        │                     ▼
        │            Docker 沙箱执行（4 语言镜像）
        │            非 root · 只读根 · 禁网 · seccomp · cgroups
        │                     │
        ▼                     ▼
judge-submission ◄──── 判题结果（状态流转 / 用例明细 / 队列对账）
        │
        ├──► WebSocket /ws/submissions/{id}     判题进度实时推送
        └──► judge-ai                           AI 代码点评（RAG + SSE 流式）
```

- **提交侧**：幂等键防重复提交，超长代码落 MinIO；死信任务由定时对账摘除，积压指标不虚高
- **判题侧**：判题机多实例水平扩展，心跳失联自动故障转移，任务重新入队
- **榜单侧**：竞赛榜单走 Redis ZSet 增量更新，封榜窗口内维护快照视图，赛后可全量重建终榜

## 🧠 AI 代码点评（项目差异化亮点）

```
提交 AC/非 AC ──► judge-submission ──► RocketMQ ──► judge-ai
                                                      │
                              RAG（pgvector 检索相似题目 / 知识片段）
                                                      │
                              LlmClient（OpenAI 兼容协议，可对接 DeepSeek 等）
                                                      │
                              SSE 流式输出 /ws/ai/review-stream（或轮询结果）
```

接入真实大模型（`.env` 配置）：

```bash
CJ_LLM_ENABLED=true
CJ_LLM_BASE_URL=https://api.deepseek.com
CJ_LLM_API_KEY=<your-api-key>
CJ_LLM_MODEL=deepseek-chat
```

未配置 API Key 时自动降级为结构化模板点评，保证本地可运行、可演示、可测试。

## 🧪 测试与验证

```bash
mvn -B verify                          # 后端：编译 + 单测 + JaCoCo 门禁
cd judge-web && npm test -- --run      # 前端单测（vitest）

# 各链路验证脚本（需基础设施 + 对应服务已启动）
python scripts/verify-p1-login.py     # 登录链路，43 项断言
python scripts/verify-authz.py        # 统一登录 + 能力码契约，56 项断言（只读）
python scripts/verify-p2.py           # 题目域与可见性隔离，20 项断言
python scripts/verify-p3.py           # 判题全链路（需 4 个沙箱镜像），20 项断言
python scripts/verify-p4.py           # 竞赛/榜单/封榜/WS，60 项断言（约 6 分钟）
python scripts/verify-p5.py           # AI 点评 RAG + SSE，46 项断言
python scripts/verify-p6.py           # 前端产物/可观测性/监控栈/压测资产/文档

# 压测（自动播种账号池 → JMeter → 解析 → 门槛判定 → HTML 报告）
python perf-test/run-perf.py --plan smoke     # 冒烟：全链路正确性
python perf-test/run-perf.py --plan load      # 混合负载：100 并发
```

## 📦 部署

```bash
docker compose --profile app up -d     # 8 后端服务 + 前端全部容器化
```

监控栈独立部署（须带 `--env-file`，Grafana 口令为 fail-closed 形式）：

```bash
cd deploy/monitoring
docker-compose --env-file ../../.env -f docker-compose.monitoring.yml up -d
```

详细部署（TLS 终结、JWT 轮换、API 版本化、生产检查清单）见 [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md)。

---

## 🗺️ 演进路线

近期规划（代码相似度检测、special judge、OpenTelemetry 全链路等）与中长期里程碑见 [docs/ROADMAP.md](docs/ROADMAP.md)。

---

## 📄 许可与说明

- 本项目基于 MIT License 开源，详见 [LICENSE](LICENSE)，仅供学习交流使用。
- 服务公共底座（common / api / gateway / auth / user）复用自 [zx-learn](https://github.com/I-KUN-L1/zx-learn)（不改动原仓库），按判题业务重新裁剪与改造。
- 生产环境请务必轮换 `.env` 全部密钥并设置 `CJ_ACTUATOR_TOKEN`，勿使用 `.env.example` 中的占位值。

---

## ❓ FAQ

| 问题 | 解决方案 |
|---|---|
| **`mvn` 报 `找不到或无法加载主类 ...Launcher`（Git Bash）** | Git Bash 下用 `bin/mvn.cmd`，不要用 `bin/mvn`（bash 包装脚本在 MSYS 路径下解析失败） |
| **服务启动报 `Could not build classpath: \d\1\...argfile`** | `java.io.tmpdir` 收到了 MSYS 风格路径。启动脚本已自动转换；手工启动请传 Windows 风格路径 |
| **所有服务都抢同一个端口** | 宿主/IDE 向子进程注入了 `SERVER__PORT`，Spring 松散绑定后覆盖 yml。启动前 `unset SERVER__PORT SERVER__HOST SERVER_PORT SERVER_HOST`，或显式传 `--server.port=<端口>` |
| **`docker compose up -d` 报 `error from registry: denied`（minio）** | 受限网络拉不到 `minio/minio`。MinIO 已归入 `storage` profile，不影响其余基础设施 |
| **RocketMQ broker 不断重启（ExitCode 253），日志为空** | 不要给 `apache/rocketmq` 镜像挂载 `/home/rocketmq/store` 卷：镜像内无该目录，Docker 创建的挂载点属 root，broker 以 uid=3000 运行无写权限即静默退出 |
| **教师注册接口 403** | 有意为之：教师可建题、可看隐藏用例，该端点已移出匿名白名单并要求管理员角色；学员自助注册不受影响 |
| **`judge-ai` 启动日志成功，但端口没人监听** | judge-ai 是唯一的响应式服务（WebFlux + Netty），成品 jar 缺 `jakarta.servlet` 时类型推断会得出 NONE。`application.yml` 已显式声明 `spring.main.web-application-type: reactive`，不要删除 |
| **Prometheus 面板全是 `down`** | 服务跑在宿主机、监控栈在 Docker 网络内，抓取地址必须用 `host.docker.internal:908x`，写 `localhost` 会指向容器自身 |
| **Grafana 里改完面板，重启后改动消失** | `allowUiUpdates: false`，仪表盘以仓库 JSON 为准。改 `deploy/monitoring/grafana/gen_dashboards.py` 后重新生成，或直接改 `dashboards/*.json` |
| **JDK 17 能运行吗？** | 不能。项目统一 Java 21（`maven.compiler.release=21`，启用虚拟线程），CI 同样以 JDK 21 构建验证 |
