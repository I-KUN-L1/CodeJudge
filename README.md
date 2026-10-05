# CodeJudge

[![CI](https://github.com/I-KUN-L1/CodeJudge/actions/workflows/ci.yml/badge.svg)](.github/workflows/ci.yml)
![Java](https://img.shields.io/badge/Java-21-orange?logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.5-brightgreen?logo=springboot)
![Spring Cloud](https://img.shields.io/badge/Spring%20Cloud-2023.0.3-blue)
![Node](https://img.shields.io/badge/Node-22-green?logo=node.js)
![Coverage](https://img.shields.io/badge/coverage-JaCoCo%2030%25%20gate-yellow)

分布式在线编程评测平台 —— 多语言判题 / 判题机集群 / 安全沙箱 / 竞赛排行榜 / AI 代码点评。

面向编程教学、校招笔试与在线竞赛场景，基于 Java 21 + Spring Cloud 微服务架构，
底座复用自 [zx-learn](https://github.com/I-KUN-L1/zx-learn)（**不改动原仓库**），
按判题业务重新裁剪与改造。

> 当前进度：**P6 已完成（前端 + Prometheus/Grafana 监控 + JMeter 压测 + 文档收口）**，
> 六个阶段全部交付。阶段验收结果见 [docs/CONTEXT.md](docs/CONTEXT.md)。
> **8 个可运行服务**（9080–9087）+ 前端 5174 + 监控栈 9090/9093/3001。
>
> 另完成一轮**鉴权收敛**：登录收敛为单一入口（角色由 `user.type` 决定，不选角色）、
> 按钮渲染改由后端下发的**能力码**驱动（无权限不渲染）、前端**零鉴权推导**、
> 登录页不再外显默认账密。契约见 §5.2.1，验收 `python scripts/verify-authz.py`（56 项）。

---

## 一、技术栈

| 类别 | 选型 |
|---|---|
| 语言 / 运行时 | Java 21（虚拟线程已开启） |
| 框架 | Spring Boot 3.3.5 / Spring Cloud 2023.0.3 / Spring Cloud Alibaba 2023.0.3.2 |
| 网关 | Spring Cloud Gateway（JWT 双 Token 全局过滤 + user-info 透传 + 登录防爆破限流） |
| 持久化 | MyBatis-Plus 3.5.9 / MySQL 8（业务库）/ PostgreSQL 16 + pgvector（AI 向量库） |
| 缓存 / 队列 | Redis 7（判题队列、幂等锁、竞赛 ZSet 榜）/ RocketMQ 4.9.7（判题异步链路） |
| 对象存储 | MinIO（超长判题代码与附件） |
| 接口文档 | Knife4j 4.5.0（`/doc.html`） |
| 前端 | Vue 3 + Vite + Element Plus + Pinia + ECharts |
| 可观测性 | Micrometer → Prometheus 2.54 / Alertmanager 0.27 / Grafana 11.3 |
| 压测 | JMeter 5.6.3（无 GUI 运行 + HTML 报告 + 门槛判定） |

## 二、模块与端口

| 模块 | 端口 | 职责 | 阶段 |
|---|---|---|---|
| judge-gateway | 9080 | 路由、鉴权、限流、CORS、SSE 超时放宽 | P1 ✅ |
| judge-auth | 9081 | 统一登录（按 `user.type` 自动判定角色）、双 Token、**能力码下发**、首个管理员安全引导 | P1 ✅ |
| judge-user | 9082 | 学员/教师/管理员基础信息 | P1 ✅ |
| judge-problem | 9083 | 题目、题面版本、测试用例、标签 | P2 ✅ |
| judge-submission | 9084 | 提交落库 + 幂等 + MQ 投递 + 结果查询 + 判题进度 WS | P3 ✅ |
| judge-worker | 9085（可多实例 9085/9185/9285） | 判题机：MQ 消费 + 沙箱执行 + 心跳 + 故障转移 | P3 ✅ |
| judge-contest | 9086 | 竞赛、报名、Redis ZSet 实时榜、封榜/终榜重建、榜单 WS | P4 ✅ |
| judge-ai | 9087 | LLM 代码点评 + RAG + SSE 流式（WebFlux） | P5 ✅ |
| judge-web | 5174 | 前端（Vue 3 + Vite） | P6 ✅ |
| 监控栈 | 9090 / 9093 / 3001 | Prometheus / Alertmanager / Grafana | P6 ✅ |

**基础设施端口（已避让 zx-learn）**

| 组件 | 端口 | 说明 |
|---|---|---|
| MySQL | 3307 | zx-learn 占 3306 |
| Redis | 6380 | zx-learn 占 6379 |
| PostgreSQL + pgvector | 5433 | zx-learn 占 5432 |
| RocketMQ NameServer | 9877 | zx-learn 占 9876 |
| RocketMQ Broker | 10919 / 10921 / 10922 | fast / main / HA；zx-learn 占 10909/10911/10912 |
| RocketMQ Dashboard | 18081 | http://localhost:18081 |
| MinIO | 9000 / 9001 | API / 控制台（profile `storage`） |

## 三、目录结构

```
codejudge/
├── judge-common        公共底座：R<T> / 异常体系 / PageDTO / requestId 链路 / 雪花ID
│                       JWT / Redis 工具 / MQ 封装 / 归属与内部调用守卫
├── judge-api           Feign 契约层 + 跨服务 DTO（含 Language 枚举 / 判题消息体）
├── judge-gateway       网关（/ws/** 拆分路由 + WebSocket 握手 ?token= 鉴权）
├── judge-auth          认证
├── judge-user          用户
├── judge-problem       题目（题面版本 / 测试用例 / 标签）
├── judge-submission    提交（落库 / 幂等 / MQ 投递 / 判题进度 WS / 队列对账）
├── judge-worker        判题机（MQ 消费 / 沙箱执行 / 心跳 / 故障转移）
├── judge-contest       竞赛（报名 / Redis ZSet 实时榜 / 封榜快照 / 终榜重建 / 榜单 WS）
├── judge-ai            AI 点评（WebFlux + SSE + pgvector RAG）
├── judge-web           前端（Vue 3 + Vite + Element Plus + Pinia）        ← P6
├── perf-test           JMeter 压测（jmx / 账号池播种 / 无 GUI 运行器）    ← P6
├── sandbox/            沙箱镜像构建上下文（Dockerfile × 4 语言）
├── deploy/
│   ├── mysql|redis|rocketmq|pgvector   中间件配置
│   └── monitoring/     Prometheus / Alertmanager / Grafana 独立可部署栈  ← P6
├── sql/                init.sql（全量 schema）+ seed.sql（幂等种子数据）
├── scripts/            start-all.py（一键启动）+ dev-start-backend.py
│                       + verify-p{1..6}.py + verify-authz.py + build-sandbox-images.py
├── docs/               PLAN / CONTEXT / ARCHITECTURE / API-REFERENCE / DEPLOYMENT
│                       PERF / P1~P6-REPORT.md
└── docker-compose.yml
```

## 四、快速开始

### 1. 环境要求

| 依赖 | 版本 | 备注 |
|---|---|---|
| JDK | 21（Temurin 21.0.7 已验证） | 需 `JAVA_HOME` |
| Maven | 3.9.6 | 本机路径 `D:\1\apache-maven-3.9.6`；⚠ Git Bash 下用 `bin/mvn.cmd` |
| Docker Desktop | 29.7.2+ | 基础设施 + 判题沙箱 |
| Node.js | 22.x | 前端构建 |
| JMeter | 5.6.3（可选） | `D:\1\jmeter` |

空闲端口：9080–9087 / 5174 / 9090 / 9093 / 3001 / 3307 / 6380 / 5433 / 9877 / 10919 / 10921 / 10922 / 18081

### 2. 启动基础设施

```bash
cp .env.example .env
# 编辑 .env：至少填 MYSQL_ROOT_PASSWORD / REDIS_PASSWORD / POSTGRES_PASSWORD
#             MINIO_ROOT_PASSWORD / CJ_JWT_SECRET
docker compose up -d
```

MySQL 容器首次启动会自动执行 `sql/init.sql`（建 5 个库 + 全量表）与 `sql/seed.sql`（种子账号与标签）。

> MinIO 在 `storage` profile 下，默认不随 `up -d` 启动（P1 链路不需要它，且部分受限网络无法拉取该镜像）：
> `docker compose --profile storage up -d minio`

### 3. 构建

```bash
unset SERVER__PORT SERVER__HOST SERVER_PORT SERVER_HOST
/d/1/apache-maven-3.9.6/bin/mvn.cmd clean install -DskipTests    # 10 个模块

cd judge-web && npm install && npm run build                     # 前端
```

### 4. 构建判题沙箱镜像（P3 起必需）

```bash
python scripts/build-sandbox-images.py     # 4 个镜像：java21 / python312 / gcc13 / go122
```

### 5. 启动服务

**推荐：一条命令拉起全部**

```bash
python scripts/start-all.py            # 基础设施 + 8 个后端服务（逐个等待 /actuator/health）
python scripts/start-all.py --all      # 再加 监控栈 + 前端 dev server
python scripts/start-all.py --no-infra # 基础设施已在跑，只起服务
python scripts/start-all.py --wait     # 看护模式，常驻并回收子进程
```

启动顺序与依赖关系（详见 [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) §3.0.1）：

| 阶段 | 组件 | 端口 | 为什么在这个位置 |
|---|---|---|---|
| 0 | MySQL / Redis / PostgreSQL / RocketMQ | 3307 / 6380 / 5433 / 9877+10921 | 任何服务连不上库都起不来 |
| 1 | **judge-user** | 9082 | 只连 MySQL；**必须早于 judge-auth**（见下） |
| 2 | **judge-auth** | 9081 | 启动期管理员引导 + 登录校验都要 Feign 调 user |
| 3–5 | judge-problem / judge-submission / judge-contest / judge-worker / judge-ai | 9083 / 9084 / 9086 / 9085 / 9087 | 这些之间**并列**，分组只为输出好读 |
| 6 | **judge-gateway** | 9080 | 路由指向上面全部 → 必须最后 |
| 7 | judge-web · 监控栈 | 5174 · 9090/9093/3001 | 呈现层与可观测性，可后置 |

> ⚠ **硬顺序只有三条**：基础设施先于任何服务；`judge-user` 先于 `judge-auth`；网关最后。
> `judge-user` 排在前面的原因是实测踩出来的：`judge-auth` 的首个管理员引导一启动就经 Feign
> 问 `judge-user`「有没有管理员」，顺序反了会被 catch 吞成一行 error ——
> **症状是「服务全绿、健康检查全过，但没有管理员账号也没有凭据文件」**，
> 像极了引导功能坏掉。`start-all.py` 把它变成启动期的一次明确失败。

**也可只起后端（看护模式）**

```bash
python scripts/dev-start-backend.py --wait            # 全部 8 个服务，按依赖顺序
python scripts/dev-start-backend.py judge-problem     # 只起指定模块
```

> `--wait` 为看护模式，父进程驻留期间子 JVM 存活；**必须**在支持后台常驻的终端（或受管后台任务）中运行
> —— 不加 `--wait` 时子进程会随父 shell 退出被 Job Object 回收。
> ⚠️ 本机 **`.sh` 一律无法执行**（被路由到黑名单 wsl.exe），`python` 须用绝对路径（见 [docs/CONTEXT.md](docs/CONTEXT.md) §7）。

**前端（开发态）**

```bash
cd judge-web && npm run dev        # http://127.0.0.1:5174，/api 代理到 9080
```

**监控栈（独立部署，可选）**

```bash
cd deploy/monitoring
# ⚠ 必须带 --env-file：该 compose 的 Grafana 口令为 fail-closed 形式，漏带会直接拒绝启动
#   （本机的 docker-compose 不读 shell 环境变量做插值，故不能靠 export）
docker-compose --env-file ../../.env -f docker-compose.monitoring.yml up -d
# Grafana http://localhost:3001   Prometheus http://localhost:9090
```

## 五、验证核心链路（可直接复制运行）

### 5.1 健康检查（8 个服务 + 指标端点）

```bash
for p in 9080 9081 9082 9083 9084 9085 9086 9087; do
  echo -n "$p  health="; curl -s -o /dev/null -w '%{http_code}' http://localhost:$p/actuator/health
  echo -n "  prometheus="; curl -s -o /dev/null -w '%{http_code}\n' http://localhost:$p/actuator/prometheus
done
```

### 5.2 登录（统一入口，无需选角色）

```bash
curl -s -X POST http://localhost:9080/accounts/login \
  -H 'Content-Type: application/json' \
  -d '{"cellPhone":"13900000001","password":"123456"}'
```

**只有一个登录入口**，前端不需要也不允许指定「我是学员还是管理员」——角色由账号自身的
`user.type`（1 员工 / 2 学员 / 3 教师）决定，后端据此签发对应的 token 与 refresh cookie：

| 响应字段 | 说明 |
|---|---|
| `accessToken` / `refreshToken` / `userId` / `expireTime`(1800s) | 双 Token；`refreshToken` 只以 HttpOnly Cookie 下发，**不进响应体** |
| `role` / `roleLabel` | `user.type` 与其中文名（管理员/学员/教师），由后端下发，前端不维护映射表 |
| `mustChangePassword` | 引导期初始凭据文件是否仍在 —— 前端据此提示改密，**不阻断登录** |

Cookie 名按角色区分：学员/教师 `judge-refresh-token`，员工 `judge-admin-refresh-token`
（登录时写一枚并**清掉另一枚**，否则续签会读到过期身份导致「刚登录就被踢下线」）。

种子账号：学员 `13900000001`~`13900000005`、教师 `13900000011`~`13900000012`，密码均为 `123456`。
> 旧端点 `POST /accounts/admin/login` 保留为**兼容别名**（额外要求账号为员工，已标 `@Deprecated`），
> 新代码请一律用 `/accounts/login`。

> ⚠ 登录接口有令牌桶限流（2 req/s，突发 5，防爆破）。批量压测请用独立账号池，见 §5.8；
> 压测前需按 `perf-test/README.md` §3.3 放宽限流，否则登录吞吐恒被钳在 2 req/s。

### 5.2.1 当前账号的能力码（前端的全部鉴权依据）

```bash
curl -s http://localhost:9080/accounts/me/capabilities -H "Authorization: Bearer $TOKEN"
```

返回 `{role, roleAlias, roleLabel, home, menus[], perms[]}`：

| 角色 | `perms` 数量 | `home` | `menus` |
|---|---|---|---|
| 学员 | 6 | `/problems` | 3（题库/竞赛/提交记录） |
| 教师 | 16 | `/teacher/problems` | 6（含题目管理/创建竞赛/AI 知识库） |
| 员工 | 20 | `/admin/users` | 10（含用户/标签/判题集群/系统监控） |

**前端不参与任何鉴权推导**：它只把 `perms` 展平成 Set 供 `v-perm` 指令与 `user.can()` 查表，
把 `menus` 按 `group` 分栏渲染。无权限的按钮**直接不渲染**（不是隐藏）；路由 `meta.perm`
同样写能力码。能力集层次是严格的 学员 ⊂ 教师 ⊂ 员工，未知 `user.type` 一律给**空集**（fail-closed）。
契约由 `python scripts/verify-authz.py`（56 项）守。

### 5.3 携带 Token 访问当前用户

```bash
TOKEN=$(curl -s -X POST http://localhost:9080/accounts/login \
  -H 'Content-Type: application/json' \
  -d '{"cellPhone":"13900000001","password":"123456"}' \
  | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')

curl -s http://localhost:9080/users/me -H "Authorization: Bearer $TOKEN"
```

### 5.4 提交判题（完整闭环）

```bash
# 分页参数是 pageNo / pageSize，不是 page / size（写错会静默用默认值）
curl -s "http://localhost:9080/problems/page?pageNo=1&pageSize=5" -H "Authorization: Bearer $TOKEN"

curl -s -X POST http://localhost:9080/submissions \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"problemId":4001,"language":"PYTHON","code":"import sys\nd=sys.stdin.read().split()\nprint(sum(int(x) for x in d))"}'

# 轮询判题结果（异步，通常 1-10s 内出终态）
curl -s http://localhost:9080/submissions/<返回的 id> -H "Authorization: Bearer $TOKEN"
```

### 5.5 监控指标

```bash
# 业务指标：待判队列积压 / 死信任务 / 在线判题机
curl -s http://localhost:9084/actuator/prometheus | grep -E '^judge_(queue_backlog|dead_tasks|workers_online)'

# HTTP 延迟（P95）
curl -s http://localhost:9080/actuator/prometheus | grep '^http_server_requests_seconds_bucket' | head -3
```

Grafana 打开 <http://localhost:3001>（匿名只读；账号 `admin` / 密码见 `GRAFANA_ADMIN_PASSWORD`，默认 `codejudge`），
左侧 **CodeJudge** 目录下有 4 个看板：服务总览 / HTTP 性能 / 判题链路 / AI 点评。

### 5.6 管理员登录

首个管理员由 judge-auth 启动时**安全引导生成**（不在 SQL 中硬编码，`sql/seed.sql` 里也确实没有管理员）：
无管理员且凭据文件不存在时创建，凭据写入**仓库根目录** `.bootstrap-credentials`，首次改密后自动删除。

```bash
cat .bootstrap-credentials                     # 查看初始凭据（改密成功即消失）
curl -s -X POST http://localhost:9080/accounts/login \
  -H 'Content-Type: application/json' \
  -d '{"cellPhone":"13800000000","password":"<从凭据文件读取>"}'
```

> ⚠ 凭据文件默认是**相对路径，按进程工作目录解析**。上面两条受支持的启动方式都把 cwd
> 固定为仓库根，故落点就是 `<仓库根>/.bootstrap-credentials`；`judge-auth` 启动时会**无条件打印
> 解析后的绝对路径**，找不到文件时以那行日志为准。
> 手工 `java -jar` 时请自行保证 cwd，否则凭据会落到别处（并且登录响应里的改密提示会静默消失）。

> 🔴 **风险提示（I3）**：`.bootstrap-credentials` 里是**明文初始口令**。只要该文件还在磁盘上：
> ① 任何拿到仓库/宿主机读权限的人都能登录管理员；② 任何 STAFF 账号登录都会收到「请改密」提醒。
> **首次登录后立即改密**（登录页提示或个人中心 → 修改密码，走 `/accounts/password/first-change`），
> 改密成功文件自动删除。长期不改密 = 等于把管理员密码写在仓库根目录。
> 该文件已入 `.gitignore`（36 行），不会进 git；但仍可能残留在备份/聊天记录里。

### 5.7 接口文档

浏览器打开 <http://localhost:9080/doc.html>

### 5.8 压测

```bash
python perf-test/run-perf.py --plan smoke     # 冒烟：全链路正确性，约 1 分钟
python perf-test/run-perf.py --plan load      # 混合负载：100 并发 / 6200 请求
python perf-test/run-perf.py --plan load -J tgSubmit.threads=40 -J tgBrowse.threads=60
```

会**自动播种账号池** → 跑 JMeter → 解析 `.jtl` → 按门槛判定 PASS/FAIL，并生成 HTML 报告。
完整负载模型与预期指标见 [perf-test/README.md](perf-test/README.md)，
实测结果见 [docs/PERF.md](docs/PERF.md)。

### 5.9 WebSocket 端点（P4）

经网关连接，握手时用查询参数 `?token=<accessToken>` 鉴权（浏览器 WS API 无法自定义 Header）：

| 端点 | 用途 | 可选参数 |
|---|---|---|
| `ws://localhost:9080/ws/submissions/{submissionId}` | 判题进度推送（状态流转 / 结果） | — |
| `ws://localhost:9080/ws/contests/{contestId}/rank` | 竞赛榜单变动推送 | `full=true` 订阅全量视图（含封榜期内部名次） |

### 5.10 各阶段验收脚本（需基础设施 + 对应服务已启动）

```bash
python scripts/verify-p1-login.py     # P1 登录链路，43 项断言
python scripts/verify-authz.py        # 统一登录 + 能力码（按钮级权限）契约，56 项断言（只读）
python scripts/verify-p2.py           # P2 题目域与可见性隔离，20 项断言
python scripts/verify-p3.py           # P3 判题全链路（需 4 个沙箱镜像），20 项断言
python scripts/verify-p3-failover.py  # P3 故障转移混沌测试
python scripts/verify-p4.py           # P4 竞赛/榜单/封榜/WS，60 项断言（约 6 分钟）
# ⚠️ P5 前置：旧 PG 数据卷须先重放 init.sql 补 ai_review.embedding（initdb 只在建卷时执行一次）
#   docker exec -i codejudge-pg psql -U postgres -d judge_ai -v ON_ERROR_STOP=1 < deploy/pgvector/init.sql
python scripts/verify-p5.py           # P5 AI 点评 RAG + SSE，46 项断言（降级模式约 30s）
python scripts/verify-p6.py           # P6 前端产物/可观测性/监控栈/压测资产/文档（含真实判题）
python scripts/verify-p6.py --no-monitor --no-judge   # 快速子集
```

## 六、常见问题（FAQ）

**Q1. `mvn` 报 `找不到或无法加载主类 org.codehaus.plexus.classworlds.launcher.Launcher`**
Git Bash 下必须用 `mvn.cmd`，不要用 `bin/mvn`（bash 包装脚本在 MSYS 路径下会解析失败）。

**Q2. 服务启动报 `Could not build classpath: \d\1\...\spring-boot-xxx.argfile`**
`java.io.tmpdir` 收到了 MSYS 风格路径（`/d/1/...`），JVM 解析成 `\d\1\...`。脚本已用 `pwd -W`
转换为 `D:/1/...`。手工启动时请传 Windows 风格路径。

**Q3. 所有服务都抢同一个端口，报 `Identify and stop the process that's listening on port xxxxx`**
宿主/IDE 向子进程注入了 `SERVER__PORT`，Spring 松散绑定后覆盖 `application.yml`。
本机实测（2026-09-20，转储子 JVM 环境）：`SERVER__PORT=56298`、`SERVER__HOST=127.0.0.1`，
56298 正是宿主自身的监听端口 —— 不处理时 Tomcat 会绑到该端口而非 yml 里的 908x。

```bash
unset SERVER__PORT SERVER__HOST SERVER_PORT SERVER_HOST   # ① 清变量
java -jar target/judge-user.jar --server.port=9082        # ② 或显式传参（优先级最高，最稳）
```

**Q4. `docker compose up -d` 报 `error from registry: denied`（minio）**
受限网络无法拉取 `minio/minio`。MinIO 已归入 `storage` profile，不影响其余基础设施。

**Q5. RocketMQ broker 不断重启（ExitCode 253），日志为空**
不要给 `apache/rocketmq` 镜像挂载 `/home/rocketmq/store` 卷：镜像内该目录不存在，
Docker 创建的挂载点是 root 属主，而 broker 以 uid=3000 运行，无写权限即静默退出。

**Q6. 教师注册接口 403**
这是**有意为之**：底座白名单曾放行 `/teachers/register`，任何人可不登录注册成教师
（教师可建题、可看隐藏用例）。CodeJudge 已将其移出白名单并要求 `@RequireRole(STAFF)`。
学员自助注册不受影响。

**Q7. `judge-ai` 启动日志显示成功，但端口没人监听**
`judge-ai` 是全项目唯一的响应式服务（WebFlux + Netty）。成品 jar 中 `spring-webmvc` 存在而
`jakarta.servlet` 完全缺失，类路径推断会得出 `WebApplicationType.NONE` →
**服务"启动成功"却不监听端口，零报错**。因此 `application.yml` 必须显式写
`spring.main.web-application-type: reactive`，不能依赖推断。

**Q8. Prometheus 面板全是 `down`，提示 connection refused**
judge-* 服务跑在**宿主机**上，不在监控栈的 Docker 网络内。抓取地址必须用
`host.docker.internal:908x`，写 `localhost` 会指向容器自身。

**Q9. Grafana 里改完面板，重启后改动消失**
`allowUiUpdates: false`，仪表盘以仓库里的 JSON 为准。改法：
改 `deploy/monitoring/grafana/gen_dashboards.py` 后重新生成，或直接改 `dashboards/*.json`。

**Q10. `judge_queue_backlog` 一直不归零**
已修复：转死信的提交此前不会被从待判队列摘除（DEAD 走 DLQ topic，不触发结果消费端的 ZREM），
导致积压指标只增不减。现由 `JudgeCompensationService#queueReconcileScan`（每 60s）对账摘除，
并在 worker 与本类两处死信分支补上了 ZREM。

## 七、安全约定

- 仓库**零硬编码**：所有密码、JWT 密钥、LLM Key 一律走环境变量（`.env`，已 gitignore）。
- `.bootstrap-credentials` 承载首个管理员初始凭据，改密后自动删除，不入库。
- 网关白名单只放行必须匿名的端点，写操作一律不放行，由后端 `@RequireRole` fail-closed 兜底。
- **鉴权信息只从后端来**：角色与能力码由 `judge-auth` 计算下发，前端只做渲染，不做任何
  「角色 → 能做什么」的推导（`scripts/verify-authz.py` 有静态断言守着）。
  能力码以 `user.type` 为唯一权威，**不启用 DB 里那六张 RBAC 表**（它们无种子数据，
  启用会引入双源，一旦漂移就表现为「按钮画了但接口 403」）。
- `/actuator/**` **不经网关路由**（路由表不含该前缀，业务流量不会转发到各服务管理端点）；
  网关自身的 `/actuator/**` 由 `ActuatorGuardFilter` 防护：默认仅回环/内网来源可达
  （公网一律 404，不暴露端点存在性）；设置 `CJ_ACTUATOR_TOKEN` 进入严格模式——
  任何来源（含内网）必须持令牌（Prometheus 在 scrape_config 配 `authorization` 凭据）。
- AI 点评的内部契约 `review-context` 要求学员侧 `maskHidden=true`，防止套出隐藏用例期望输出。

## 八、路线图

| 阶段 | 内容 | 状态 |
|---|---|---|
| P1 | 底座 + 骨架（common / api / gateway / auth / user + 基础设施 + SQL + 脚本） | ✅ |
| P2 | 用户 + 题目管理（judge-problem + 隐藏用例隔离） | ✅ |
| P3 | 提交 + MQ + judge-worker + Docker 沙箱 | ✅ 已完成（2026-09-20，verify-p3.py 20/20） |
| P4 | WebSocket 推送 + 竞赛 + Redis ZSet 排行榜 | ✅ 已完成（2026-09-20，verify-p4.py 60/60） |
| P5 | AI 代码点评 + RAG + SSE | ✅ 已完成（2026-09-21，verify-p5.py 46/46） |
| P6 | 前端 + Prometheus/Grafana + JMeter 压测 + 文档 | ✅ 已完成（2026-09-21，verify-p6.py） |

> **P5 起实际可运行服务为 8 个**（9080–9087）；固定链路还需 4 个沙箱镜像
> `codejudge/judge-{java21,python312,gcc13,go122}:latest`（`python scripts/build-sandbox-images.py`）。

### 文档索引

| 文档 | 内容 |
|---|---|
| [docs/PLAN.md](docs/PLAN.md) | 规划书 v1.0（目录/表结构/MQ 与 Redis 键/接口清单/沙箱设计） |
| [docs/CONTEXT.md](docs/CONTEXT.md) | 阶段进度、硬约束、下一步 |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 系统全景、模块职责、关键链路、设计取舍 |
| [docs/API-REFERENCE.md](docs/API-REFERENCE.md) | 全量接口清单、请求/响应契约、SSE 事件协议 |
| [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) | 依赖、配置细节、启动流程、环境陷阱、生产检查清单 |
| [docs/PERF.md](docs/PERF.md) | 压测实测数据与瓶颈分析 |
| [docs/P1-REPORT.md](docs/P1-REPORT.md) ~ [P6-REPORT.md](docs/P6-REPORT.md) | 各阶段验收报告 |

## License

MIT
