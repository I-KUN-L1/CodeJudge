# CodeJudge 部署与配置

> 面向本机（Windows + Docker Desktop）的完整部署说明，以及生产部署时**必须改动**的项。
> 架构背景见 [`ARCHITECTURE.md`](./ARCHITECTURE.md)，接口见 [`API-REFERENCE.md`](./API-REFERENCE.md)。

---

## 1. 依赖清单

| 组件 | 版本 | 本机路径 / 获取方式 | 用途 |
|---|---|---|---|
| JDK | **21**（Temurin 21.0.7 已验证） | 需 `JAVA_HOME` | 全部后端服务 |
| Maven | **3.9.6** | `D:\1\apache-maven-3.9.6` | 构建。⚠ Git Bash 下必须用 `bin/mvn.cmd` |
| Docker Desktop | 29.7.2（overlayfs / cgroup v2） | 已安装 | 基础设施 + 判题沙箱 |
| Node.js | **22.x** | `C:\Users\20670\.workbuddy\binaries\node\versions\22.22.2-3` | 前端构建 |
| JMeter | **5.6.3** | `D:\1\jmeter` | 压测（可选） |
| Python | 3.11+ | 用于运行 `scripts/*.py` | 启动脚本 / 验收 / 压测 |

**Docker 运行时**：本机 Docker Desktop **仅有 runc，无 gVisor（runsc）**。
沙箱默认使用加固的 runc（seccomp + pids limit + 只读根 + 网络禁用）；
`CJ_SANDBOX_RUNTIME=runsc` 为可选项，需自行安装 gVisor 后启用。

**端口前置检查**：本机同时跑着 zx-learn（占用 3306 / 6379 / 5432 / 9876 / 10909 / 10911 / 10912 / 18080）。
CodeJudge 已整体换段（见 `ARCHITECTURE.md` §6），正常不冲突。启动前可自查：

```bash
netstat -ano | grep LISTENING | grep -E ":(3307|6380|5433|9877|10919|10921|10922|18081)\b"
```

---

## 2. 配置文件

### 2.1 `.env`（唯一配置源）

```bash
cp .env.example .env
```

`.env` 同时被两方读取：

- **Docker Compose** —— 做 `${VAR}` 变量替换；
- **Spring Boot** —— 各服务 `application.yml` 通过
  `spring.config.import: optional:file:./.env[.properties]` 读取。

> ⚠ `optional:` 前缀意味着**文件缺失不会报错**，服务会用 yml 里的默认值静默启动。
> 因此「配置没生效」时第一件事是确认 `.env` 真的在 **仓库根目录**（服务从
> `judge-*/` 启动，靠 `../.env` 兜底，但工作目录不对就会漏读）。

**必须修改的项**（其余可保持默认）：

| 变量 | 说明 |
|---|---|
| `MYSQL_ROOT_PASSWORD` | MySQL root 密码 |
| `REDIS_PASSWORD` | Redis 密码 |
| `POSTGRES_PASSWORD` | PG 密码 |
| `MINIO_ROOT_PASSWORD` | MinIO 密码 |
| `CJ_JWT_SECRET` | JWT 签名密钥，**≥ 32 字节**，建议 `openssl rand -base64 48` |
| `CJ_ADMIN_INIT_PASSWORD` | 首管理员初始密码（首登强制改密） |
| `CJ_USER_DEFAULT_PASSWORD` | 管理员重置用户密码后的统一初始密码 |
| `CJ_LLM_API_KEY` | LLM Key。**未配置时保持 `CJ_LLM_ENABLED=false`**，AI 点评降级为结构化提示而非报 500 |

### 2.2 LLM / 向量配置的三个坑

```properties
CJ_LLM_BASE_URL=https://open.bigmodel.cn/api/paas/v4
CJ_LLM_CHAT_PATH=chat/completions
CJ_LLM_EMBEDDING_PATH=embeddings          # ← 必须与 base-url 配套，不要再写 v1/embeddings
CJ_LLM_EMBEDDING_MODEL=embedding-3
CJ_LLM_EMBEDDING_DIMENSION=1024           # ← 必须等于 pgvector 中向量列维度
```

1. **路径拼接**：智谱的 `base-url` 已含 `/api/paas/v4`，`embedding-path` 再写
   `v1/embeddings` 会拼出 `/v4/v1/embeddings` → 恒定 404 → Embedding 失败后
   **静默降级为伪向量**，RAG 失去语义但接口不报错，极难发现。
2. **维度必须一致**：`CJ_LLM_EMBEDDING_DIMENSION` 要等于
   `knowledge_chunk.embedding` / `ai_review.embedding` 的实际维度（1024）。
   不一致时插入直接报维度错误；改维度需同步改表并重建 HNSW 索引。
3. **HNSW 上限 2000 维**：pgvector 的 HNSW 索引不支持超过 2000 维，选模型时要留意。

---

## 3. 启动流程

> **想跑起来只需要一条命令**：`python scripts/start-all.py`（见 §3.0）。
> 下面的 §3.1–§3.6 是**分解说明**，用于排障、或只想跑其中一部分的场景。

### 3.0 一条命令拉起全部（推荐）

```bash
cd D:/1/CodeJudge
python scripts/start-all.py                    # 基础设施 + 8 个后端服务
python scripts/start-all.py --all              # 再加 监控栈 + 前端 dev server
python scripts/start-all.py --with-web         # 基础设施 + 服务 + 前端
python scripts/start-all.py --no-infra         # 基础设施已在跑，只起服务
python scripts/start-all.py --wait             # 看护模式：本进程常驻，退出即回收全部子进程
python scripts/start-all.py --extra judge-worker=9185:9285 --wait    # 判题机多实例
```

脚本按**真实依赖顺序**逐个启动并等待 `/actuator/health`，任一环节不就绪就明确报错退出。
它复用 `dev-start-backend.py` 的派生逻辑（清 `SERVER__PORT`、`DETACHED_PROCESS`、日志分流），
不复制一份 —— 避免两处真相。

> ⚠ `--wait` 必须挂在**受管后台任务**里。直接 `nohup ... &` 起的进程会随父 shell 退出
> 被宿主 Job Object 整体回收（见 §6）。

### 3.0.1 启动顺序与依赖关系

| 阶段 | 组件 | 端口 | 依赖（谁必须先起来） | 依赖的性质 |
|---|---|---|---|---|
| 0 | MySQL / Redis / PostgreSQL / RocketMQ | 3307 / 6380 / 5433 / 9877+10921 | — | **硬**：任何服务连不上库都起不来 |
| 1 | **judge-user** | 9082 | 仅 MySQL | **硬**：见下方「为什么 user 必须在 auth 之前」 |
| 2 | **judge-auth** | 9081 | MySQL · **judge-user**（Feign） | **硬**：启动期引导 + 登录校验都要调 user |
| 3 | judge-problem | 9083 | 仅 MySQL | 无服务依赖 |
| 4 | judge-submission | 9084 | MySQL · MQ · judge-problem（Feign） | 提交时要校验题目 |
| 5 | judge-contest | 9086 | MySQL · Redis · judge-submission / problem / user | 榜单聚合 |
| 5 | judge-worker | 9085（可多实例 9085/9185/9285） | MySQL · Redis · MQ · **沙箱镜像** | 与 3/4/5 并列，互不阻塞 |
| 5 | judge-ai | 9087 | MySQL · Redis · **PostgreSQL(pgvector)** | 唯一响应式（WebFlux） |
| 6 | **judge-gateway** | 9080 | 上面全部 | **硬**：最后一个起，否则路由指向未就绪的下游 |
| 7 | judge-web | 5174 | 后端 + 网关（所有请求经 9080） | 呈现层 |
| 7 | 监控栈 | 9090 / 9093 / 3001 | 各服务已暴露 `/actuator/prometheus` | 可观测性，可后置 |

**真正"硬"的顺序只有三条**：① 基础设施先于任何服务；② `judge-user` 先于 `judge-auth`；
③ 网关最后。其余（3–5 阶段）是并列的，分组只是为了输出好读。

> ⚠ **为什么 `judge-user` 必须在 `judge-auth` 之前**（踩过）：
> `judge-auth` 的首个管理员引导是 `ApplicationRunner`，一启动就经 Feign 调 `judge-user` 问
> 「有没有管理员」。顺序反了时这一步抛异常、被 `AdminBootstrapRunner` 的 catch 吞成一行 error，
> **症状是「8 个服务全绿、健康检查全过，但没有管理员账号也没有凭据文件」** ——
> 看起来像引导功能坏了。`start-all.py` 把它变成了启动期的一次明确失败。
> （历史缺陷：`dev-start-backend.py` 的 `SERVICES` 字典顺序曾是 auth → user。）

### 3.1 第一步：基础设施

```bash
cd D:/1/CodeJudge
docker compose up -d
docker compose ps            # 等到 mysql / redis / postgres / rocketmq 全部 healthy
```

可选组件：

```bash
docker compose --profile storage up -d minio     # MinIO 默认不启动，见 compose 内注释
```

> ⚠ **RocketMQ broker 数据与注册地址（2026-10-01 更新，勿"修"回去）**：
> 1. **store 卷**：早期「镜像内无 `/home/rocketmq/store`、命名卷挂载点 root:root → uid=3000
>    无写权限 → ExitCode=253」的坑，已由一次性 `rocketmq-store-init` 容器（user root，
>    chown 3000:3000）+ `service_completed_successfully` 门控解决，消息持久化到
>    `codejudge-mq-store` 命名卷。首次 `up` 见 `Exited(0)` 的 init 容器属预期。
> 2. **brokerIP1 双 conf**：broker 向 namesrv 注册的地址来自 `brokerIP1`，客户端直连该地址。
>    **容器形态必须注册服务名**（`deploy/rocketmq/broker-compose.conf`，`brokerIP1=rocketmq-broker`）——
>    若注册 `127.0.0.1`，同网络的 worker 会连到自己（closeChannel 死循环、判题全挂，实测）；
>    **宿主机直跑形态**用 `deploy/rocketmq/broker.conf`（`brokerIP1=127.0.0.1`）。
>    两份文件**除 brokerIP1 外必须一致**（判题相关参数如 messageDelayLevel 改动要同步两处）。
>    想两形态共存：Windows hosts 加一行 `127.0.0.1 rocketmq-broker` 即可（宿主进程把服务名
>    解析回本机，worker 容器则走 compose 内 DNS）。

### 3.2 第二步：构建

```bash
# Git Bash 下必须用 mvn.cmd（bin/mvn 会报 classworlds Launcher 错误）
unset SERVER__PORT SERVER__HOST SERVER_PORT SERVER_HOST
/d/1/apache-maven-3.9.6/bin/mvn.cmd clean install -DskipTests
```

**10 个模块**，构建顺序由依赖决定：`codejudge → judge-common → judge-api → judge-gateway →
judge-auth → judge-user → judge-problem → judge-submission → judge-worker → judge-contest → judge-ai`。

### 3.3 第三步：判题沙箱镜像（P3 起必需）

```bash
python scripts/build-sandbox-images.py        # 构建 4 个镜像
docker images | grep codejudge/
```

| 镜像 | 语言 |
|---|---|
| `codejudge/judge-java21` | Java 21 |
| `codejudge/judge-python312` | Python 3.12 |
| `codejudge/judge-gcc13` | GCC 13 (C++17) |
| `codejudge/judge-go122` | Go 1.22 |

#### 3.3.1 worker 容器化派发的挂载与权限契约（T3，2026-10-01 实测落地）

容器化形态（worker 跑在 compose 里、经 `/var/run/docker.sock` 派发沙箱）必须满足三条，
缺一即出现「挂载落空 / 假 TLE / permission denied」：

1. **路径对齐**：DockerSandbox 把 workDir/artifactDir/seccomp 的**绝对路径**直接传给宿主
   daemon 做 `-v` 挂载源 → worker 容器内的私有路径在 VM 侧不存在，挂载即落空。
   对策：worker 与沙箱统一走 VM 侧同路径 `/cj-sandbox`
   （compose: `CJ_WORKER_WORK_ROOT=/cj-sandbox`、`CJ_SANDBOX_SECCOMP=/cj-sandbox/judge-seccomp.json`，
   worker bind 挂载 `/cj-sandbox:/cj-sandbox`）。
2. **属主初始化**：一次性 `sandbox-work-init` 容器（root）建 `/cj-sandbox/artifacts`、
   拷贝 seccomp profile、`chown -R 1001:1001` —— 容器化形态下 bind 挂载走**真实 Linux 权限**，
   worker(uid 1001) 必须对 artifactDir 有写权（Windows 宿主挂载的 777 时代不再适用）。
   因此 `DockerSandbox` 派发沙箱固定 `--user 1001:1001`（与 worker 进程 uid 一致；
   曾用 1000:1000 → 编译沙箱 `cannot create /work/stdout: Permission denied`，实测踩坑）。
3. **docker.sock 访问**：VM 内 sock 为 `root:root 0660`，worker（uid 1001, gid 999 judge）
   不在 root 组 → compose 用 `group_add: ["0"]` 补 gid 0 组成员身份走组位读写，
   不改 sock 本身权限。

验收：容器化栈上 `python scripts/verify-p3.py` 全绿（21/0，2026-10-01）。

### 3.4 第四步：启动后端

```bash
python scripts/dev-start-backend.py --wait      # 全部 8 个服务
python scripts/dev-start-backend.py judge-problem   # 只起指定服务
```

启动顺序（脚本内固化为依赖顺序，理由见 §3.0.1）：
`judge-user → judge-auth → judge-problem → judge-submission → judge-contest → judge-worker → judge-ai → judge-gateway`

日志：`logs/<module>.log`。

> ⚠ 服务数量多的场景直接用 §3.0 的 `start-all.py`：它会在每个服务启动后**逐个等待就绪**，
> 而不是只把进程拉起来就返回。

> ⚠ **为什么必须用这个脚本而不是 `java -jar`**（三条都是实测踩出来的）：
> 1. **Git Bash 下 `nohup ... &` 启动的子 JVM 会随 shell 退出被整体回收**（Job Object 语义）；
>    该脚本用 `subprocess.Popen + DETACHED_PROCESS` 规避。
> 2. **PowerShell 5.1 的 `Start-Process` 在本机会崩溃**：宿主环境同时存在 `Path` 与 `PATH`
>    两个大小写变体，构建环境字典时报「已添加项。字典中的关键字: Path / PATH」。
> 3. **宿主注入了 `SERVER__PORT`**（实测值 `56298`）与 `SERVER__HOST=127.0.0.1`：
>    Spring 的松散绑定会把 `SERVER__PORT` 映射到 `server.port`，**覆盖 yml 配置**，
>    导致 8 个服务全部去抢宿主自己的端口并启动失败。
>    直接 `java -jar` 时必须先 `unset SERVER__PORT SERVER__HOST SERVER_PORT SERVER_HOST`，
>    或显式加 `--server.port=<端口>`。脚本已内置清变量。

### 3.5 第五步：前端

```bash
cd judge-web
npm install
npm run dev          # http://127.0.0.1:5174（已配 /api 代理到 9080）
npm run build        # 产物 dist/
```

前端通过 Vite 代理把 `/api/**` 转发到 `http://127.0.0.1:9080`，去前缀后与网关路由对齐。
生产部署设 `VITE_API_BASE`（见 `judge-web/.env.example`）。

### 3.6 第六步：监控栈（可选，独立部署）

```bash
cd deploy/monitoring
# ⚠ 必须带 --env-file：该 compose 的 Grafana 口令为 fail-closed 形式，漏带会直接拒绝启动。
#   （本机 docker-compose 不做 shell 环境变量插值，export 无效；且它只自动读 cwd 下的 .env，
#    而 .env 在项目根，故必须显式指定相对路径。）
docker-compose --env-file ../../.env -f docker-compose.monitoring.yml up -d
```

| 组件 | 地址 |
|---|---|
| Prometheus | http://localhost:9090 |
| Alertmanager | http://localhost:9093 |
| Grafana | http://localhost:3001（默认匿名只读；账号 `admin` / 密码取 `GRAFANA_ADMIN_PASSWORD`，默认 `codejudge`） |

> ⚠ **为什么 Grafana 是 3001 而不是 3000**：3000 是 Grafana 的公共默认端口，本机多个项目并存时极易撞车。
> ⚠ **抓取地址用 `host.docker.internal` 而不是 `localhost`**：judge-* 服务跑在**宿主机**上，
> 不在监控栈的 Docker 网络内。写 `localhost` 会指向容器自身 → 全部 target 显示 down。
> Linux 宿主靠 compose 里的 `extra_hosts: host.docker.internal:host-gateway` 兜底。

---

## 4. 配置速查（按使用方）

### 4.1 网关路由（`judge-gateway/src/main/resources/application.yml`）

| 路由 id | 前缀 | 目标 | 特殊配置 |
|---|---|---|---|
| `login-rate-limit` | `POST /accounts/login` | 9081 | 令牌桶 2 req/s，突发 5（`GW_LOGIN_RATE_REPLENISH` / `GW_LOGIN_RATE_BURST` 可覆盖） |
| `judge-auth` | `/accounts/**` `/menus/**` `/roles/**` `/privileges/**` `/jwks/**` | 9081 | |
| `judge-user` | `/users/**` `/students/**` `/teachers/**` `/staffs/**` | 9082 | |
| `judge-problem` | `/problems/**` `/tags/**` `/test-cases/**` | 9083 | |
| `judge-submission` | `/submissions/**` `/workers/**` | 9084 | |
| `judge-ws-submission` | `/ws/submissions/**` | 9084 | `response-timeout: 900000` |
| `judge-ws-contest` | `/ws/contests/**` | 9086 | `response-timeout: 900000` |
| `judge-contest` | `/contests/**` | 9086 | |
| `judge-ai` | `/ai/**` | 9087 | `response-timeout: 900000`（SSE 长连接） |

> ⚠ **路由顺序即匹配优先级**。`/ws/contests/**` 必须排在 `/ws/**` 之前，
> 否则竞赛榜单连接会被通配路由抢到 judge-submission（无此端点），
> 表现为浏览器侧「连接被立刻关闭」且服务端日志无任何订阅记录。

#### 登录限流与压测的冲突（必读）

`login-rate-limit` 的 key 由 `LoginRateLimitKeyResolver` 生成：`rate:login:{ip}:{userId|anon}`。
登录请求通常未认证 → **key 退化为 `rate:login:{ip}:anon`，全局单一令牌桶**。后果：

| 场景 | 表现 | 处置 |
|---|---|---|
| 连续播种账号（`seed-users.py`） | 连续登录撞 `HTTP 429`；429 响应体**不是业务 JSON**，易被误判为「账号不存在」 | `seed-users.py` 内置退避重试，可自愈；想加速就放宽限流 |
| JMeter 登录线程组 | **与线程数无关**，吞吐恒被压在 2 req/s，P95 与整体吞吐门槛必然不达标 | 压测前必须放宽限流 |

放宽方式（**仅限本地/压测环境，生产严禁**）：

```bash
GW_LOGIN_RATE_REPLENISH=500 GW_LOGIN_RATE_BURST=1000 python scripts/dev-start-backend.py judge-gateway --wait
```

> 默认值刻意保持生产安全水位（2 req/s、突发 5），**不放宽就等于自动获得防爆破能力**。
> 实测：默认配置下 12 次瞬时登录 = 5×200 + 7×429；放宽后 20 次瞬时登录全部 200。

### 4.2 CORS（易踩）

```yaml
allowedOriginPatterns: ${CORS_ALLOWED_ORIGINS:http://localhost:[*],http://127.0.0.1:[*]}
allow-credentials: true
```

> ⚠ **必须同时覆盖 `localhost` 与 `127.0.0.1`，且端口用 `[*]` 通配**，否则会出现
> 「页面打得开、一操作就失败」的隐蔽故障：浏览器对**同源 GET 不发 `Origin`**，
> 但同源 POST/PUT/DELETE **一律携带 `Origin`**。若白名单只有 `http://localhost:5174`，
> 用户以 `http://127.0.0.1:5174` 打开时，浏览（GET）正常，而登录/提交/报名/点评
> 全部被判定为非法来源返回 403（空响应体 + `Vary: Origin`），前端只能显示「连接中断」。
> 不使用 `"*"`：与 `allow-credentials: true` 组合会被浏览器直接拒绝。

### 4.3 监控配置（`deploy/monitoring/prometheus/prometheus.yml`）

8 个服务各一个 target，标签 `application`（服务名）+ `tier`（edge/core/ai）。
指标端点 `/actuator/prometheus`，15s 抓取。

**告警规则**（`rules/codejudge-alerts.yml`，11 条）：

| 分组 | 规则 | 阈值 |
|---|---|---|
| 可用性 | `CodeJudgeServiceDown` | `up == 0` 持续 1m |
| 可用性 | `CodeJudgeMetricsMissing` | 8 个服务全部抓不到，持续 3m |
| HTTP | `CodeJudgeHighErrorRate` | 5xx 占比 > 5%，持续 5m |
| HTTP | `CodeJudgeHighLatencyP95` | P95 > 1s，持续 5m |
| HTTP | `CodeJudgeSubmitFailureBurst` | 提交接口 5xx > 0.5/s，持续 3m |
| 判题 | `JudgeQueueBacklogHigh` | 队列积压 > 200，持续 5m |
| 判题 | `JudgeDeadTasksPresent` | 死信 > 0，持续 2m |
| 判题 | `JudgeWorkersAllOffline` | 在线判题机为 0 或指标消失，持续 2m |
| 资源 | `JvmHeapHigh` | 堆占用 > 85%，持续 10m |
| 资源 | `HikariPoolSaturation` | 等待连接线程 > 5，持续 3m |
| 资源 | `ProcessCpuSustainedHigh` | 进程 CPU > 90%，持续 10m |

> 阈值取自本地实测基线（见 [`PERF.md`](./PERF.md)），**上线前需按真实容量重标**。

**Grafana 仪表盘**（4 个，provisioning 自动装载）：

| 仪表盘 | 回答的问题 |
|---|---|
| CodeJudge 服务总览 | 整体活着吗？慢不慢？判题通不通？ |
| CodeJudge HTTP 性能 | 哪个接口慢、哪个接口在报错 |
| CodeJudge 判题链路 | HTTP 全绿但判题卡死 —— 唯一能暴露的地方 |
| CodeJudge AI 点评 | SSE 长连接的请求量、流式耗时、失败率 |

> 仪表盘 JSON 由 `deploy/monitoring/grafana/gen_dashboards.py` 生成。
> 改阈值请改脚本再重新执行，或直接改 JSON —— 但注意 `allowUiUpdates: false`，
> **在 Grafana UI 上的修改重启后会被仓库 JSON 覆盖**。

---

## 5. 验证

```bash
# 逐阶段回归（按需）
python scripts/verify-p1-login.py     # 登录链路 43 项断言
python scripts/verify-p2.py           # 题目
python scripts/verify-p3.py           # 提交/判题/沙箱 20 项
python scripts/verify-p4.py           # 竞赛/榜单 60 项（约 6 分钟）
python scripts/verify-p5.py           # AI 点评 46 项

# 统一登录 + 能力码（按钮级权限）契约：56 项断言，只读、无副作用
python scripts/verify-authz.py

# P6 全量验收（含前端产物、监控、压测）
python scripts/verify-p6.py

# 上线前自检（二者互不重叠，都建议跑）
python scripts/preflight-check.py            # 环境/暴露面/通知（18 项自动检查，不读凭据明文）
python scripts/check-hardcoded-defaults.py   # 「零硬编码」静态扫描（只读源码，不碰 .env）
```

`verify-authz.py` 覆盖的是**跨阶段契约**，被任一阶段改动打破都会红：
登录只有一个入口且角色由 `user.type` 决定、`GET /accounts/me/capabilities` 的能力码集合
（学员 6 / 教师 16 / 员工 20 且严格嵌套）、未登录与伪造 token 的 fail-closed、
旧端点 `/accounts/admin/login` 的兼容门槛，以及**前端静态断言**——源码中不得残留角色判定代码
（`isStaff` / `canManage` / `USER_TYPES` / `meta.roles`）、不得再外显默认账密。
需要前端 `dist/` 才能跑最后一项；`dist/` 缺失时该子项会**显式标 SKIP 并计入跳过数**，
不会被算成通过。

管理员凭据通过环境变量传入（开发库已有 `p3admin` / `13900000099`）：

```bash
export CJ_P3_ADMIN_PHONE=13900000099
export CJ_P3_ADMIN_PASS=<密码>
```

---

## 6. 本机环境陷阱清单（务必先读）

| # | 现象 | 根因 | 规避 |
|---|---|---|---|
| 1 | 服务"启动成功"却**不监听端口**（`judge-ai`） | 成品 jar 中 `spring-webmvc` 在、`jakarta.servlet` 缺失 → 类路径推断得 `WebApplicationType.NONE` | `application.yml` 必须显式写 `spring.main.web-application-type: reactive` |
| 2 | 服务绑定到错误端口后启动失败 | 宿主注入 `SERVER__PORT`（实测 56298），Spring 松散绑定覆盖 yml | 启动前 `unset SERVER__PORT SERVER__HOST SERVER_PORT SERVER_HOST` |
| 3 | `.sh` 脚本一律无法执行 | 本机 `.sh` 被路由到黑名单 `wsl.exe` | 一律用 `.py` 脚本，python 用绝对路径 |
| 4 | Git Bash 下 Maven 报 classworlds Launcher 错误 | `bin/mvn` 是 shell 脚本 | 用 `bin/mvn.cmd` |
| 5 | `find` 命令行为异常 | 被 Windows `FIND.EXE` 抢占 | 改用 Glob/Grep 工具或 `ls -R` |
| 6 | 传 `java.io.tmpdir` 报 "Could not build classpath" | MSYS 的 `/d/1/...` 被 JVM 解析成 `\d\1\...` | 传 Windows 路径（`pwd -W` 得 `D:/1/...`） |
| 7 | RocketMQ broker 启动即退出，日志为空 | 镜像内不存在 `/home/rocketmq/store`，挂载点为 root:root，进程 uid=3000 无写权限 | 不挂载该目录（见 §3.1） |
| 8 | 经 heredoc 写入含 `\n` 的配置后 YAML 语法错误 | Git Bash/MSYS 把 `\n` 转成字面量 `/n` | 含转义的写入改用脚本文件，不要用 heredoc |
| 9 | `docker run --entrypoint /bin/xxx` 报路径不存在 | MSYS 把容器内绝对路径转成 Windows 路径 | 去掉前导斜杠（`--entrypoint=xxx`）或改用 PowerShell 调用 docker |
| 10 | PG 插入拿不到自增主键，正文永不落库 | PG 驱动在 `RETURN_GENERATED_KEYS` 下返回整行，`KeyHolder.getKey()` 抛异常 | 用 `INSERT ... RETURNING id` + `queryForObject` |
| 11 | 杀掉启动器后**全部服务一起消失** | 子 JVM 由宿主 Job Object 托管，父进程退出即回收 | 只重启单个服务时**按端口 kill 对应 JVM**，不要杀 `dev-start-backend.py --wait` 父进程 |
| 12 | `docker --format '{{.X}}'` 输出错乱（`.Repository:.Tagt.Size`），`grep` 得空 → **误判镜像不存在** | Git Bash 破坏 Go 模板中的 `{{}}` 与 `\t` | 用 `docker images` / `docker ps` 原始输出再过滤 |
| 13 | `wmic` 调用失败（`WinError 5`，或被安全策略拦截） | `wmic.exe` 在程序黑名单 | 用 PowerShell `Get-CimInstance Win32_Process` |
| 14 | PowerShell 命令 exit 0 但看不到任何输出 | 本环境 PowerShell 工具不回显 stdout | 需要输出就**写文件再读** |
| 15 | Python 调 `taskkill` 抛 `UnicodeDecodeError` | `taskkill` 输出是 GBK，`text=True` 默认按 utf-8 解码 | 显式 `encoding="gbk", errors="replace"` |
| 16 | 压测/播种登录大量 `HTTP 429`，或登录吞吐恒为 2 req/s | 网关 `login-rate-limit` 令牌桶 key = `rate:login:{ip}:anon`，单机共用一桶 | 压测前 `GW_LOGIN_RATE_REPLENISH/BURST` 放宽；`seed-users.py` 已内置退避重试。**压测后必须恢复默认值**并用 `preflight-check.py` 的 C1 验证 |
| 17 | `docker exec … <容器内绝对路径>` 报"路径不存在"，且路径被改写成 `D:/git,.../Git/etc/...` | Git Bash / MSYS 把以 `/` 开头的参数当成本机路径转换 | **最稳的是双斜杠**：`docker exec <ctr> ls //etc/...`、`docker exec -w //etc/xxx <ctr> sh -c 'ls relative'`。`MSYS2_ARG_CONV_EXCL='*'` 在**内联前缀**形式下有效，但**本机实测 `export MSYS2_ARG_CONV_EXCL='*'` 后仍被转换**（2026-09-22），别只靠它；`MSYS_NO_PATHCONV=1` 实测无效。`taskkill /F /T /PID` 同理 —— 写 `//F` 会报"无效参数" |
| 18 | `docker network ls --format '{{.Name}}'` / `docker images --format …` 输出错乱、`grep` 得空 | 同 #12，Git Bash 破坏 Go 模板 `{{}}` | 用 `docker inspect <容器> --format …`（实测可用）或原始输出再过滤 |
| 19 | `curl http://127.0.0.1:<自定义端口>` 返回 **502** 或连不上，但服务其实是活的 | 宿主 `HTTP_PROXY`（实测 `http://127.0.0.1:62423`）劫持了本机请求 | 查本地端口一律加 `--noproxy '*'`；否则会误判"服务没起" |
| 20 | 容器**连不到宿主**上的临时监听端口（`host.docker.internal` Connection refused），但宿主同机的 Java 服务却可达 | Windows 防火墙按**程序**放行，python.exe 未被放行 | 做"容器 → 接收器"这类探针测试时，把接收器跑在**容器里**（同一个 docker network），最省事且不受宿主防火墙影响 |
| 21 | JMeter 计划配了 `duration` 却**永不停止**（实测跑 13 分钟 / 226 万样本 / `.jtl` 400MB） | `<boolProp name="ThreadGroup.scheduler">${__P(...)}</boolProp>` —— JMeter 的 **boolProp 不做函数替换**，整串被 `Boolean.parseBoolean()` 判成 `false`；`loops=-1` + 调度器关闭 = 无限运行 | `scheduler` 写**字面量** `true`（`duration` 仍可 `-J` 覆盖）。`run-perf.py` 已加墙钟看门狗；手工 kill 时务必带 `/T`，否则只杀启动脚本、**留下继续压测的孤儿 java** |
| 22 | 检查 Alertmanager 配置，读到的却是**镜像自带原文件**，据此得出错误结论 | compose 只把 `.tpl` 挂到 `/etc/alertmanager/`，**渲染产物写在 `/alertmanager/` 卷里**，进程以 `--config.file=/alertmanager/rendered-alertmanager.yml` 启动（`/etc/alertmanager/alertmanager.yml` 是镜像自带的 2024 年那份，**根本没被使用**） | 校验要看**生效文件**：`docker exec codejudge-alertmanager sh -c 'cat //proc/1/cmdline \| tr "\0" " "'` 拿到真实 `--config.file`，再 `amtool check-config <它>`。判据是"命令行指向哪个文件"，不是"哪个文件存在" |
| 23 | 改了源码却看不到效果 / 提交里混进构建产物 | `.gitignore` 是按需增补的，新工具（vitest 覆盖率、压测 `.jtl`）会漏 | 每次新增工具链后跑 `git add -A --dry-run \| wc -l` 并对**目录分布**做一次人眼复核；`judge-web/coverage/` 就是这么在首提交预演时被发现的（485 → 430） |

---

## 7. 生产部署检查清单

> **怎么用**：先跑一次自检，再按结果勾选。
>
> ```bash
> python scripts/preflight-check.py          # 全量（需服务与监控栈在跑）
> python scripts/preflight-check.py --json   # 供 CI 消费
> ```
>
> 自检**不读任何凭据明文**（全部走行为验证），可以放心在任意机器上执行。
> 下表「校验」列写着 `自动 B4` 之类的，就是自检脚本里对应的检查项编号 ——
> 勾选时以脚本输出为准，不要凭印象打勾。
>
> 图例：`[x]` 已在当前环境实测通过 ｜ `[ ]` 未通过或需人工处置
> ｜ ⚙ = 脚本可自动判定 ｜ ✋ = 必须人工（脚本给了具体做法）
>
> **最近一次自检：2026-09-21（终版），FAIL 2 / MANUAL 6 / PASS 12 / WARN 5**
> （两条 FAIL：**B4** Grafana 默认密码未更换、**F1** Alertmanager 路由全指向 `null`）
>
> **2026-09-22 追加复验（可复现，与 preflight 互补）**：
> `promtool check rules` → **11 条规则 SUCCESS**；`promtool check config` → **SUCCESS**；
> `amtool check-config`（校验的是**生效中**的 `rendered-alertmanager.yml`，不是镜像自带那份）
> → **SUCCESS，4 个 receiver 已定义、全部路由指向 `null`** —— 确认第 3 项处于「机制就绪、缺真实凭据」。
> 另：`scripts/check-hardcoded-defaults.py` → **FAIL 0 / WARN 2**（与下方 E3 同源，见 §7.1 第 2 条）。
>
> ⚠️ preflight 的原始输出落在本机 `logs/`，而 **`logs/` 已 gitignore、不入库** ——
> 换台机器或重新 clone 后没有这份记录，按上方命令自行复跑即可（自检本身不读凭据明文）。
> 本次 preflight **未复跑**：后端 8 个服务当时处于停止状态，跑出来的 FAIL 无参考价值，
> 故沿用 09-21 终版读数；下次启动服务后请重跑并更新本行。

### 7.1 凭据与密钥

- [ ] ✋ `.env` 中所有 `必须修改` 项已改；`CJ_JWT_SECRET` 用强随机值
      —— `python scripts/rotate-credentials.py --rotate`（预演）→ `--rotate --write`（落盘）
- [x] ✅ **两处"兜底弱口令"已从源码移除**（2026-09-22）
      ｜ 校验：自动 **E3** + `python scripts/check-hardcoded-defaults.py`（**FAIL=0 / WARN=0**）
      - `CJ_ADMIN_INIT_PASSWORD` —— 首个管理员引导口令。原先 **yml 与 `AdminBootstrapService`
        双份**兜底 `123456`，现全部移除：未配置时生成 24 位随机强口令，**只写入
        `.bootstrap-credentials`，不进日志**（避免口令长期留在日志文件里）。
      - `CJ_USER_DEFAULT_PASSWORD` —— 重置用户口令。原先 `UserService` 用常量
        `FALLBACK_DEFAULT_PASSWORD = "123456"` 兜底，现移除：未配置时该接口 fail-closed 返回 400。
      > 静默失效的根源是"不覆盖也能正常启动、不报任何错"。现在未配置时**要么随机生成、
      > 要么明确拒绝** —— 两种情况都不会再产生一个可预测的口令。
- [x] ⚙ 首个管理员凭据文件的**落点**已明确（2026-09-23）
      ｜ 校验：`python scripts/verify-authz.py`（`mustChangePassword` 字段存在性）
      —— 默认值 `.bootstrap-credentials` 是**相对路径，按进程工作目录解析**；
      两种受支持的启动方式（`start-all.py` / `dev-start-backend.py`）都把 cwd 固定为仓库根，
      故**规范落点是 `<仓库根>/.bootstrap-credentials`**。
      `AdminBootstrapService` 启动时**无条件打印解析后的绝对路径**（只打路径、不打口令）——
      只报"生成过凭据"却不报位置，等于把口令藏起来。
      ⚠️ 手工 `java -jar` 必须自己保证 cwd，否则凭据会落到别处（历史踩坑：`judge-auth/` 下
      留下一份含旧口令的孤儿文件），并且 `isBootstrapPending()` 会因查不到文件而**静默为 false**，
      登录响应里的改密提示随之消失。
- [x] ⚙ 数据库密码不使用默认值；`.env` **未提交到仓库** ｜ 校验：自动 E1 / E2
- [x] ⚙ `GRAFANA_ADMIN_PASSWORD` 已覆盖默认值 ｜ 校验：自动 B4 **PASS**（2026-09-22）
      —— 监控 compose 已改为 **fail-closed**（缺 `--env-file` 直接拒绝启动，不再静默退回默认值），
      容器内环境变量与 `.env` 一致，旧默认凭据 `admin/codejudge` 登录返回 **401**
- [x] ⚙ 生产环境**关闭** `GF_AUTH_ANONYMOUS_ENABLED` ｜ 校验：自动 B3（当前为 WARN，见第 8 项）
- [ ] ✋ 轮换后**同步更新**三处持有旧凭据的地方（`rotate-credentials.py` 会打印具体命令）：
      MySQL 应用账号、MinIO 的 `MINIO_ROOT_PASSWORD`、Grafana 的 admin 口令


### 7.2 暴露面与网络

- [x] ⚙ 确认 `/actuator/**` **未**经网关暴露 ｜ 校验：自动 B1 / B1b
      （下游管理端点直连仍可达，生产请用网络策略限制为内网/管理网）
- [x] ⚙ CORS 不回显任意来源 ｜ 校验：自动 B2
- [x] ⚙ 确认网关登录限流为**生产默认值**（未带 `GW_LOGIN_RATE_*`）｜ 校验：自动 C1
      —— 09-21 终版实测 PASS：12 次瞬时登录 → **5×200 + 7×429**（即默认令牌桶 2 req/s / 突发 5 已恢复）。
      ⚠️ 每次压测放宽后都必须走这一步恢复，别只看 `C1` 曾经绿过。
- [ ] ⚙ 生产 `.env` 置 **`CJ_DOC_WHITELIST_ENABLED=false`**（网关文档白名单开关，2026-09-28 新增；
      `.env.example` 已默认 false，开发 `.env` 显式 true 才能匿名看文档）
      ｜ 校验：网关启动日志应为「API 文档白名单：已关闭」，且
      `curl -i http://<网关>:9080/v3/api-docs` 与 `/doc.html` 返回 **401**
      （开着 = 全部 API 清单/参数结构匿名公开）
- [ ] ✋ 开启 HTTPS；`judge-ai` 的 SSE 需确认反向代理不缓冲（`proxy_buffering off`）

### 7.3 通知与容量

- [ ] ⚙ Alertmanager 接入真实通知渠道（**当前未通过**）｜ 校验：自动 F1
      —— 当前默认/critical 路由都指向 `null` receiver，**告警不会发到任何地方**。
      机制已就绪（改 `ALERTMANAGER_*` 环境变量即可，无需改配置模板），
      见 `deploy/monitoring/alertmanager/README.md`
- [ ] ✋ `judge-worker` 起多实例（9085/9185/9285）并确认 MQ 消费组分流正常
      ｜ 校验：自动 C2（当前 1 个实例）
      ⚠️ **不要照搬旧结论「加实例即线性扩容」** —— 2026-09-22 实测**推翻**：
      单实例 0.95 题/s、3 实例 0.96 题/s（瓶颈是每题 5 个容器的启停），
      且未限并发时正确解会被判**假 TLE**（抽样 18/25）。
      多实例必须同时设 `CJ_MQ_CONSUME_THREADS`（实例数 × 该值 ≤ CPU 核数），
      且**跨宿主机**部署才可能接近线性。详见 `docs/PERF.md` §3.7 与 `CONTEXT.md` §3 第 17 条
- [ ] ⚙ 按真实容量重标 `codejudge-alerts.yml` 阈值 ｜ 校验：自动 E4
      —— 在独立压测机跑完容量标定后执行 `python scripts/recalibrate-alerts.py --write`
      （流程见 `perf-test/RUNBOOK.md`）

### 7.4 运行时与数据

- [ ] ✋ 判题沙箱：评估 `CJ_SANDBOX_RUNTIME=runsc`（gVisor），生产不建议仅用 runc
      ｜ 校验：自动 D2
- [x] ✋ 数据卷备份与回滚预案（MySQL / PG / Redis / MinIO）
      ｜ 本地已于 **2026-09-22 演练通过**：`python scripts/backup-volumes.py --backup --verify`
      （MySQL `user` 生产 133 = 演练 133、PG 1 = 1、RDB `redis-check-rdb` 可加载、临时库无残留）
      ⚠️ **生产仍需补齐**：① 备份异地存放与保留周期；② **定期**重跑演练（不是一次性）；
      ③ MinIO 对象存储（当前 `storage` profile，容器未运行，脚本会明确跳过而非静默放过）；
      ④ 注意 RocketMQ broker **未挂 store 卷**，消息不持久化，不在这份备份覆盖范围内
- [ ] ⚙ **服务发现：维持 `NACOS_ENABLED=false`**，同步维护 `GW_*_URI` / `SVC_*_URI`
      ｜ 校验：自动 A2（网关对外可达即说明路由表生效）
      —— 选型论证与推翻条件见 `docs/ADR-001-服务发现选型.md`
      ⚠️ **生产不要置 `NACOS_ENABLED=true`**：`docker-compose.yml` 内
      **没有 Nacos 容器**，且该切换路径**从未被实测验证**（ADR §6.2 技术债 D2）。
      真要启用须先补部署 Nacos，并显式清空 `simple.instances`（防双 DiscoveryClient 共存）。

### 7.5 功能走查

- [ ] ✋ 前端关键路径在真实浏览器走一遍：
      登录 → 选题 → 提交 → 判题进度(WS) → AI 点评(SSE) → 竞赛榜单(WS)
      ｜ 校验：自动 M6 提示 + `judge-web` 单测（`npm test`，136 项）


## 8. 传输安全与密钥轮换（上线检查清单 E 区）

### 8.1 HTTPS / TLS（E1）

架构：**网关前置反代终止 TLS**，judge-* 服务保持内网 HTTP。

```
浏览器 ──HTTPS(443)──> Nginx/Caddy ──HTTP──> judge-gateway:9080 ──> 内网服务
```

推荐 Nginx 配置骨架（与 `judge-web/nginx.conf` 的 /api、/ws 规则合并使用）：

```nginx
server {
    listen 443 ssl;
    http2 on;
    server_name judge.example.com;

    ssl_certificate     /etc/letsencrypt/live/judge.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/judge.example.com/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:ECDHE-ECDSA-AES256-GCM-SHA384;
    ssl_prefer_server_ciphers off;
    ssl_session_cache shared:SSL:10m;

    # HSTS：确认全站 HTTPS 稳定后再加 preload（先不加 preload，回滚难）
    add_header Strict-Transport-Security "max-age=31536000" always;

    # …此处并入 judge-web/nginx.conf 的 location / 、/api/ 、/ws/ 三段…
    # 差异：proxy_pass 目标按部署拓扑改（http://judge-gateway:9080 或宿主网关地址）
}

server {
    listen 80;
    server_name judge.example.com;
    return 301 https://$host$request_uri;
}
```

- 证书自动续期：`certbot renew --deploy-hook "nginx -s reload"`（systemd timer 自带）。
- 内网中间件（MySQL/Redis/PG/MQ）**不暴露公网**；compose 已默认绑 `BIND_IP=127.0.0.1`。
- ⚠️ 终止 TLS 后，网关看到的都是 HTTP。`X-Forwarded-Proto` 已由反代设置，
  前端 cookie 的 `Secure` 属性判定见 `CookieBuilder`（2026-09-25 第八轮已修静默丢弃问题）。

### 8.2 JWT 密钥轮换（E4）

现状：单密钥 HMAC（`CJ_JWT_SECRET`），**不支持多密钥灰度**。轮换步骤（当前形态）：

1. 选低峰窗口。**换密钥后所有已签发 token 立即失效**（包括 refresh token），全员需重新登录。
2. 改 `.env` 的 `CJ_JWT_SECRET`（≥32 字节随机值）。
3. 重启 `judge-auth` 与 `judge-gateway`（两者共享该密钥，**必须同一窗口内一起换**，
   只换一个 = 全站 401）。
4. 复验：`python scripts/verify-p1-login.py`（登录链路 43 项断言）。

多密钥（kid）灰度轮换属**行为变更**，涉及 `JwtTool` 签发/验签双钥窗口 + 网关 kid 感知，
建议与「refresh token 轮换/吊销（Redis jti）」「RS256 化」合并立项（见 LAUNCH-READINESS §G 遗留项）。
**当前防线**：`/jwks` 端点已删除（密钥不再外泄），密钥仅经 `.env` 流转、不入 git、不进命令行。

### 8.3 API 版本化（E3）—— ✅ 已落地（2026-10-01）

> **落地记录（2026-10-01）**：用户拍板本轮执行。网关别名路由 `v1-alias`（RewritePath）已启用，
> 三处同步点全部核对完成：① 登录限流谓词覆盖 `/v1/accounts/login`；② `JwtProperties.excludePaths`
> 白名单已核对 `/v1/**` 形态；③ `ActuatorGuardFilter` 路径匹配不受影响。
> 运行时验收：`GET /v1/problems/page` 与不带 `/v1` 响应一致（code=200 同构）；
> `verify-authz.py` **73 PASS / 0 FAIL**（含 /v1 形态的越权矩阵）。

原方案备忘（未启用时代的决策依据）：网关加一条**别名路由**放在路由表最前：

```yaml
        - id: v1-alias
          uri: no://op
          predicates:
            - Path=/v1/**
          filters:
            - RewritePath=/v1/(?<segment>.*), /$\{segment}
```

**启用前必须同步修改三处**（否则产生安全缺口）：
1. 登录限流谓词补 `/v1/accounts/login`（否则新路径绕过限流 —— 与 2026-09-23 `/accounts/admin/login` 同型缺口）；
2. `JwtProperties.excludePaths` 白名单核对（`/v1/**` 形态是否命中）；
3. actuator 防护过滤器（`ActuatorGuardFilter`）的路径匹配核对。

验收：`curl http://<gw>/v1/problems/page` 与不带 `/v1` 行为一致；`verify-authz.py` 全绿。

### 8.4 沙箱基础镜像 digest 固定记录（H3）

构建脚本 `scripts/build-sandbox-images.py` 默认按 `name@sha256` 拉取 base（`CJ_SANDBOX_UNPINNED=1` 逃生）。当前登记：

| base | digest（2026-09-30 登记） |
|---|---|
| eclipse-temurin:21-jdk | sha256:92a2a4d7a928d057e7bd999c418d66c26a34eb9a0442f3ab67721c3f88110b2d |
| python:3.12-slim | sha256:2f17fc044b579bab302c2e8054d3a686e2cb9a83de48e70534b94cd8ebbe06a9 |
| gcc:13 | sha256:16ae525998c94df36a116c191524256b1d46e72d7a0e9aaf6c153455e40eb5b8 |
| golang:1.22 | sha256:1cf6c45ba39db9fd6db16922041d074a63c935556a05c5ccb62d181034df7f02 |

升级流程：换新 digest → 重跑 `python scripts/build-sandbox-images.py` → `python scripts/verify-p3.py` 回归。
