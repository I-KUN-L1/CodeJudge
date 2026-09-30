# CodeJudge 上线收尾 · 新对话执行提示词

> 用法：把本文件全文作为任务输入发给 AI 编程助手。每完成一项附验收证据（命令输出/文件路径），
> 同步更新 `docs/LAUNCH-CHECKLIST-执行报告-2026-09-30.md` 的「第二轮」表格与 `docs/CONTEXT.md` §5.13。
> 阻塞的项不许跳过验收硬凑（**假绿比 FAIL 危险**），标注 BLOCKED + 原因即可。

---

## 背景与现状（一句话版）

CodeJudge（分布式在线编程评测平台，8 后端微服务 + Vue 前端，`D:\1\CodeJudge`）已完成上线检查清单
A–L 的全部静态交付（一键复验 `bash docs/launch-verify.sh` → PASS=42/FAIL=0），9 个运行时镜像已构建。
当前卡在：**Docker daemon 崩溃后待用户恢复**、镜像编排实测未跑、若干行为变更项待拍板。
权威进度读 `docs/CONTEXT.md`（§5.13 = 第九轮 checklist 全记录）与
`docs/LAUNCH-CHECKLIST-执行报告-2026-09-30.md`（含「第二轮：阻塞项推进」精确状态）。

## 硬约束（先读，都是踩过的坑）

1. 包 `com.codejudge.*`；端口：服务 9080–9087（worker 可 9185/9285）· 前端 5174 · MySQL 3307 ·
   Redis 6380 · PG 5433 · namesrv 9877 · 监控 9090/9093/3001 · Loki 3100。
2. Maven 用 `/d/1/apache-maven-3.9.6/bin/mvn.cmd`；python 用
   `C:/Users/20670/.workbuddy/binaries/python/versions/3.13.12/python.exe`。
3. **Git Bash 内联 heredoc 会把 `\n`/`\r` 反斜杠序列改写成 `/n`/`/r`** —— 含转义序列的 Python
   脚本一律用 Write 工具落盘再执行，不要 heredoc 内联。
4. `grep -c` 命中 0 时退出码 1，会静默截断 `&&` 链；`taskkill //F` 报错 → 用 `Stop-Process`；
   `curl 127.0.0.1:<端口>` 加 `--noproxy '*'`；`find`/`sort` 被 Windows 抢占。
5. **wsl.exe 在安全中心程序黑名单**：AI 不能 `wsl --shutdown`，也不能绕过。VM 级 Docker 故障
   只能请用户手动修。
6. **从 AI 工具环境 Start-Process 拉起 Docker Desktop 会崩**（受限令牌 → `rename
   daemon.json.tmp Access is denied`）。必须经 `explorer.exe "C:\Program Files\Docker\Docker\Docker Desktop.exe"`
   中转启动（2026-09-30 实测有效）。`~/.docker/daemon.json.off` 是原 registry-mirrors 配置备份。
7. 9 容器（8 JVM + web）**同启会压垮 daemon**（已发生一次崩溃事故）：分批 `up -d`，或先升
   Docker Desktop 资源配额再全量。
8. `mvn clean` / `npm run build` 前必须先停对应服务/预览进程（Windows 锁 `target/*.jar`、
   预览占 `dist/`）。`docker-compose --profile app`（容器化）与宿主机直跑
   `python scripts/start-all.py` **互斥**，混用端口冲突。
9. start-all 跑的是 `target/*.jar`：改了 yml/Java 必须 `mvn package` 重打包（无需 clean，但先停服务）。
10. 两处限流压测前必放宽、测后必恢复并验证：登录令牌桶（2 req/s 突发 5）、提交限流
    （30 次/分钟/账号）。验收录 `python scripts/verify-p*.py` / `verify-authz.py`（56 项）。
11. 长跑命令用受管后台任务（`&` 起的子进程会被 Job Object 连带回收）。
12. 性能数字必须真实运行得出，禁止估算；验收脚本断言必须同时校验样本非空。

---

## 任务清单（按依赖与优先级）

### T0. 环境恢复前置（需用户手动，先与用户确认是否已完成）

用户侧动作（AI 只能提示，不能代做）：
- 管理员 PowerShell：`wsl --shutdown` → 正常双击启动 Docker Desktop → 确认 `docker version` 出 Server 版本。
- （可选）把 `~/.docker/daemon.json.off` 改名回 `daemon.json` 恢复镜像加速（不恢复也能用，拉取变慢）。

AI 侧先探测：`timeout 30 docker version --format "{{.Server.Version}}"`，
再 `docker ps` 确认基础设施容器（mysql/redis/pg/namesrv/broker/minio/监控栈）是否已随
`restart: always` 自启，缺的补 `docker compose up -d` / `deploy/monitoring` 下
`docker compose --env-file ../../.env -f docker-compose.monitoring.yml up -d`。

### T1. 提交当前改动（先做，锁住基线）

- `git status` 现有 **67 个未提交文件**（第九轮 A–L 全部交付物 + 第二轮 soak/镜像脚本）。
- 仓库无 remote（`git remote -v` 为空）→ 本地 commit 即可；commit 前跑
  `bash docs/launch-verify.sh` 确认 42/0 不回退。
- 提交后顺手做 T7 的徽章部分若有 remote 再说（见 T7）。

### T2. compose app profile 编排实测（依赖 T0；阻塞项1 后半）

1. 分批起：`docker compose --profile app up -d judge-user judge-problem judge-contest judge-ai`，
   等健康后再 `judge-auth judge-gateway judge-submission`，最后 `judge-worker judge-web`。
   （judge-worker 的 prebuilt 镜像**不含 docker CLI**，只验证 HTTP 服务能起；派发沙箱属 T3。）
2. 验收：8 端口 `/actuator/health` 全 200 + `http://localhost:5174` 200：
   ```bash
   for p in 9080 9081 9082 9083 9084 9085 9086 9087; do
     echo -n "$p "; curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:$p/actuator/health
   done
   curl -s --noproxy '*' -o /dev/null -w 'web=%{http_code}\n' http://localhost:5174
   ```
3. 端到端冒烟：`python scripts/verify-p1-login.py`（走网关 9080，注意登录限流）。
4. 已知风险：worker 容器内没有 docker CLI → 判题派发失败属预期，记录后转 T3。
5. 注意 Flyway 行为变更：每个库首启会建 `flyway_schema_history` 打 baseline=1 —— 属预期不是异常。

### T3. worker 容器化沙箱路径核验（依赖 T2；阻塞项7）

1. 用多阶段 Dockerfile 重建 worker 镜像（含 docker.io CLI）：
   `docker build -f judge-worker/Dockerfile -t codejudge/judge-worker:latest .`
   （若 maven 构建器镜像拉不动，可给 `scripts/build-app-images-prebuilt.py` 加 worker 的
   docker.io 安装分支，但 apt 装 docker.io 体积大，优先多阶段。）
2. 核心验证点：worker 容器经 `/var/run/docker.sock` 派发沙箱容器时，**容器内临时目录与宿主
   路径对齐**（compose 注释里标过：路径不对齐时挂载落空，代码判题假 TLE/假失败）。
   另外 worker 以非 root（uid 1001）运行，docker.sock 的访问权限要给 judge 用户（group_add 或
   chmod 666 方案，二选一记录进 DEPLOYMENT.md）。
3. 验收：容器化栈上 `python scripts/verify-p3.py` 全绿（判题链路 21 项，含安全用例）。
4. 结论写进 DEPLOYMENT.md（worker 容器化的挂载契约与权限要求）。

### T4. soak 首跑（依赖 T2 或宿主机启动均可；阻塞项6 收尾）

1. 先 10 分钟验链路：`python perf-test/run-perf.py --plan soak -J tgBrowse.duration=600 --no-html`。
2. 通过后放全量 1h：`python perf-test/run-perf.py --plan soak --no-html`（受管后台跑）。
3. 观测（RUNBOOK §3.2）：jvm_memory_used_bytes 锯齿回水位、hikaricp active/pending 不涨、
   judge_queue_backlog 结束归零、跑完立刻跑 verify 套件确认无静默损坏。
4. 实测数字补进 `docs/PERF.md` 与执行报告。

### T5. 扫描类（有网环境即可做；阻塞项2 + E5/E6/E7）

1. trivy 漏洞库当前两个源都被拦（mirror.gcr.io 拒连、ghcr.io 挂起）。有网后：
   ```bash
   for img in java21 python312 gcc13 go122; do
     docker run --rm -v /var/run/docker.sock:/var/run/docker.sock aquasec/trivy:latest \
       image --severity HIGH,CRITICAL --quiet codejudge/judge-$img:latest
   done
   docker run --rm -v "D:/1/CodeJudge:/repo:ro" aquasec/trivy:latest fs --scanners vuln \
     --severity HIGH,CRITICAL --quiet /repo
   ```
   （Windows 下 docker run 加 `MSYS_NO_PATHCONV=1`。）
2. HIGH/CRITICAL 有命中则逐条处置（升级 base / 加白名单说明），写进 DEPLOYMENT.md §8.4 旁。
3. E5 ZAP baseline（`zap-baseline.py -t http://host.docker.internal:9080`）同样待有网，高危清零。

### T6. E3 `/v1` API 版本化（**需用户拍板后执行**；阻塞项3）

方案已写在 `docs/DEPLOYMENT.md` §8.3（网关别名路由 + RewritePath）。启用前**必须同步改三处**，
否则造出安全缺口：
1. 登录限流谓词补 `/v1/accounts/login`（否则新路径绕过限流）；
2. `JwtProperties.excludePaths` 白名单核对 `/v1/**` 形态；
3. actuator 防护过滤器（`ActuatorGuardFilter`）路径匹配核对。
验收：`curl http://localhost:9080/v1/problems/page` 与不带 `/v1` 行为一致 + `verify-authz.py` 全绿。

### T7. CI 徽章 / remote（依赖仓库有 remote 后；阻塞项8）

1. 用户建 remote 后：`git remote add origin <url>` 并推送。
2. 替换 README 徽章占位符 `YOUR_GITHUB_ORG/CodeJudge`（共 5 处：CI/Java/Spring Boot/License/Coverage）。
3. 首推后确认 GitHub Actions 真跑一遍 `.github/workflows/ci.yml`（本地从未跑过 Actions）。

### T8. G4 前端 E2E（Playwright；阻塞项5，工作量独立可排期）

- 覆盖：登录 → 选题 → 编辑 → 提交 → 看判题结果 → 查榜单 → AI 点评。
- 基础设施：`judge-web` 内 `npx playwright install chromium`（下载浏览器需网）。
- 可先做登录+题库两条冒烟链路跑通框架，其余渐进补。
- 注意登录限流（2 req/s 突发 5），E2E 反复登录要放宽或复用会话。

### T9. SLO 度量补全（阻塞项9）

- worker/submission 补 `cj_judge_e2e_seconds`（提交→终态）直方图埋点，暴露给 Prometheus。
- Grafana 加「错误预算」看板（按 docs/SLO.md §5 的口径：99.9% 可用性 / 提交 P99<200ms /
  判题端到端 P99<10s），看板经 `gen_dashboards.py` 生成、`allowUiUpdates: false`。

### T10. stdout JSON 化（阻塞项10）

- judge-common 统一 `logback-spring.xml` 收口：profile 切换（dev=人读格式 / prod=JSON 含
  requestId/traceId），Loki 侧验证 `{application="judge-*"} | json` 可按 requestId 全链路查。
- 行为变更：改完全部 8 服务日志形态变，需重打包 + 重启验证 Loki 查询。

### T11. 其余遗留（按 README/CONTEXT 既有待办）

- `docs/LAUNCH-READINESS.md` 未同步第九/十轮改动（文档债）。
- MinIO 移出 `storage` profile（变成 app 依赖而非常驻）。
- RocketMQ broker 挂 store 卷（当前消息不持久化，重启丢队列）。
- 提交记录页列总宽 926px < 面板内宽 1382px（跨页骨架不一致）。
- JaCoCo 门禁棘轮从 0.04 往 0.30 抬：先补 judge-contest / judge-ai / judge-worker 单测
  （实测基线注释在根 pom）。
- F1 告警通道（Slack/钉钉/邮件，需外部凭据，向用户要）。

---

## 汇报要求

- 按 A/B/C/D 分类输出：已做（附证据）/ 受阻（附精确卡点与解法）/ 待用户（明确要什么）/
  不做（说明理由）。
- 每完成一个任务同步勾掉本文件对应项并更新执行报告；全部结束后更新 `docs/CONTEXT.md`
  §5.13 与 `.workbuddy/memory/`。
- 结尾附上线提醒总结（行为变更清单、需要的用户配合、go-live 前剩余项）。
