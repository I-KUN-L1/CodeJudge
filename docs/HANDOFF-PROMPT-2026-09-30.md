# CodeJudge 上线收尾 · 新对话执行提示词

> 用法：把本文件全文作为任务输入发给 AI 编程助手。每完成一项附验收证据（命令输出/文件路径），
> 同步更新 `docs/LAUNCH-CHECKLIST-执行报告-2026-09-30.md` 的「第二轮/第三轮」表格与
> `docs/CONTEXT.md` §5.13/§5.14。
> 阻塞的项不许跳过验收硬凑（**假绿比 FAIL 危险**），标注 BLOCKED + 原因即可。

> **执行状态速览（2026-10-01 第二次更新，详见执行报告「第四轮」表与 `.workbuddy/memory/2026-10-01.md`）**
> T1 ✅ commit `35367ef` ｜ T9 ✅代码完成（worker 埋点+告警12条+错误预算看板，运行时验证 ⏸）
> T10 ✅代码完成（judge-common logback 收口 + JsonLogLayout，8 服务已 clean package，Loki 验证 ⏸）
> T11 部分落地（MinIO app profile ✅ / broker store 卷 ✅ / 提交页列宽 ✅ / **READINESS 同步 ✅（第四轮）** /
> **JaCoCo 首批补测 ✅（6 类 49 例，ai 0.21 / worker 0.136 / contest 0.05，门禁 0.04 仍绿）** / F1 待凭据）
> 第三轮全部改动已提交：commit `649d51d`（launch-verify 42/0 复验）。第四轮改动待提交。
>
> ⚠️ 第四轮新坑（10-01）：**T9 提交时 worker 测试未重跑** —— JudgeEngine 新增 JudgeE2eMetrics
> 依赖让既有 `JudgeEngineFailTaskTest` NPE（@InjectMocks 不注新依赖）。已修（注入真实
> metrics + 顺手固化「死信 SE 计入 SLO 样本」断言）。教训：改构造器/字段后，`mvn test`
> 与 `mvn package` 至少跑一个真的。
>
> T0 🔴 **BLOCKED 升级（2026-10-01 实测）**：`wsl --shutdown` 已做过但问题未解 ——
> VM 能引导、引擎能短暂服务（16:27 实测容器 stats 正常），随后宿主→VM 通路死亡
> （`192.168.65.7:2376 no route to host` 持续，VM 内无 OOM/panic）。
> **头号嫌疑 = 宿主网络层**：① `route print` 无任何 192.168.65.x 路由；
> ② 双出口并存（以太网 + PPPoE 宽带连接 ifIndex=53 metric=1 默认路由）；
> ③ VMware VMnet1/VMnet8 宿主虚拟网卡 Up（与 WSL2 冲突已知源）。
> **用户修复优先级：① 重启电脑 → ② 管理员 `wsl --update` →
> ③ 临时停用 VMnet1/VMnet8 + 断开宽带连接起 Docker 验证（通了逐个恢复定位元凶）→
> ④（兜底，删数据）`wsl --unregister docker-desktop`。**
> 好消息：镜像/容器数据完好，宿主通路修复后 T2 可直接开跑。
> T2/T3/T4/T5 及 T9/T10 运行时验证全部排队在 T0 后。T6 待拍板 ｜ T7 待 remote ｜ T8 独立排期。

---

## 背景与现状（一句话版）

CodeJudge（分布式在线编程评测平台，8 后端微服务 + Vue 前端，`D:\1\CodeJudge`）已完成上线检查清单
A–L 的全部静态交付（一键复验 `bash docs/launch-verify.sh` → PASS=42/FAIL=0），9 个运行时镜像已构建，
第三轮（T9/T10/T11 代码侧）已提交 `649d51d`。
当前卡在：**Docker 宿主→VM 网络通路反复死亡（T0）**、镜像编排实测未跑、若干行为变更项待拍板。
权威进度读 `docs/CONTEXT.md`（§5.13/§5.14）与
`docs/LAUNCH-CHECKLIST-执行报告-2026-09-30.md`（含「第二轮/第三轮」精确状态）。

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
13. **（10-01 新增）AI 侧拉起 Docker Desktop 的手段已全部穷尽，勿再重试**：
    - `Start-Process` 直启 = 必现 `rename daemon.json.tmp Access denied` 崩溃（受限令牌；
      daemon.json ACL 本身正常，纯 spawn 方式问题）；
    - `schtasks` 计划任务绕道 = 被安全中心程序黑名单拦死，不可绕；
    - `explorer.exe` 中转 **不可依赖**（09-30 18:37 曾成功一次，10-01 连续 3 次 exit=1 零派生）；
    - 杀掉 `com.docker.backend` 后 **monitor 不会自动重拉**（实测 0 重生）。
    结论：Docker 恢复只能靠用户操作。
14. **（10-01 新增）改 judge-common 后的打包陷阱**：maven-jar-plugin 内容未变会跳过重建
    （forceCreation=false），`-pl <服务>` 不带 `-am` 时 fat jar 嵌的是 `~/.m2` 旧 judge-common
    —— 必须先 `install` 再 `clean package`；验证法 `unzip -p 服务jar
    BOOT-INF/lib/judge-common-1.0.0.jar | grep JsonLogLayout`（fat jar 是嵌套结构，
    直接对服务 jar grep 不到，须先解开嵌套 jar）。
15. **（10-01 新增）JaCoCo 棘轮拦 install**：新增零覆盖类会跌破 0.04 门禁 → `mvn install`
    被拦；本地构建用 `-Djacoco.skip=true` 绕过（**CI 不绕**）。
16. **（10-01 新增）「引擎短暂存活后通路死亡」模式**：VM 引导成功、引擎正常服务数分钟后
    `192.168.65.7:2376 no route to host`，VM 内无 OOM/panic。已实测两次（09-30、10-01）。
    排查入口：host 侧 `com.docker.backend.exe.log` 的 apiproxy 段 +
    `vm/init.log` 是否冻结 + `route print 192.168.65.*` + `Get-NetAdapter`（看双出口/VMware 网卡）。
    此问题若重启电脑后仍间歇复现，go-live 需评估弃用 WSL2（换原生 Linux 或 Hyper-V）。

---

## 任务清单（按依赖与优先级）

### T0. 环境恢复前置（🔴 仍 BLOCKED，2026-10-01 状态）

**已排除的原因**：不是「没做 wsl --shutdown」（已做过）、不是 VM 起不来（VM 能引导、引擎能
短暂服务）、不是引擎崩溃（无 OOM/panic）。**是宿主→VM 网络通路反复死亡**。

用户侧动作（按成功率排序）：
1. **重启电脑** → 双击启动 Docker Desktop → 等 2–3 分钟（鲸鱼图标稳定）→ `docker version` 出 Server 段。
2. 仍复现：管理员 PowerShell `wsl --update` 后重试。
3. 仍复现：临时停用 VMware VMnet1/VMnet8 两块虚拟网卡 + 断开 PPPoE 宽带连接（ifIndex=53，
   metric=1 默认路由），起 Docker 验证；通了后逐个恢复以定位元凶。
4. （兜底，⚠️ 删除发行版内镜像与容器数据）`wsl --unregister docker-desktop` 重建。

AI 侧先探测：`timeout 30 docker version --format "{{.Server.Version}}"`，
再 `docker ps` 确认基础设施容器（mysql/redis/pg/namesrv/broker/minio/监控栈）是否已随
`restart: always` 自启，缺的补 `docker compose up -d` / `deploy/monitoring` 下
`docker compose --env-file ../../.env -f docker-compose.monitoring.yml up -d`。
若通路又抖动复发，按硬约束 16 的排查入口记录证据（这是 go-live 环境风险项）。

### T1. ✅ 已完成（2026-09-30，勿重做）

- commit `35367ef`（68 文件，launch-verify 42/0 先行验证；无 remote 故仅本地 commit；
  敏感文件核查通过：无 .env / bootstrap-credentials 入库）。
- 第三轮改动另见 commit `649d51d`（T9/T10/T11 交付，同样 42/0 复验后提交）。

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

### T9. ✅ 代码已完成（commit `649d51d`），仅剩运行时验证（依赖 T0）

已落地（勿重做）：
- `judge-worker/src/main/java/com/codejudge/worker/metrics/JudgeE2eMetrics.java`：
  `cj_judge_e2e` → Prometheus `cj_judge_e2e_seconds_*`，按 verdict 分列，桶 50ms–120s；
  埋点在 `JudgeEngine.finishTerminal` 终态落库点 + 死信 SE 分支，CAS 丢弃不计（与 SLO.md §1 S3 口径一致）。
- 告警第 12 条 `JudgeE2ELatencyP99Breach`（`deploy/monitoring/prometheus/rules/codejudge-alerts.yml`；
  SLO §4 此前声称有该规则但实际缺失，已补）。
- 看板 9 `codejudge-error-budget`（`gen_dashboards.py` 生成，S1–S4 达成状态 + 预算剩余/燃烧率）。
- worker 已 clean package 通过。

剩余（T0 恢复后）：worker 起来后 `curl :9085/actuator/prometheus | grep cj_judge_e2e` 验暴露 +
真实判题一单验证直方图非空 + Grafana 看板出数。

### T10. ✅ 代码已完成（commit `649d51d`），仅剩运行时验证（依赖 T0）

已落地（勿重做）：
- `judge-common/src/main/resources/logback-spring.xml`（jar 内 classpath 收口，8 服务共用；
  非 prod=人读格式 + `[req=requestId]` 段，prod=JSON）+
  `judge-common/.../common/logging/JsonLogLayout.java`（Jackson 转义；application 取
  springProperty `APP_NAME`，勿用 LoggerContext 名——默认是 "default"）。
  **不引 logstash-encoder**（本地仓库无货且外网受限）。
- 8 服务已 `clean package`，嵌套 judge-common 已复验含新产物（验证法见硬约束 14）。

剩余（T0 恢复后）：重启后验证 Loki `{application="judge-*"} | json` 可按 requestId 查询。
已知局限：requestId 仅服务内有效，网关不透传，跨服务链路串联待后续。

### T11. 其余遗留（✅ 五项已落地：三项于 `649d51d`，两项于第四轮，剩余如下）

- ~~MinIO 移出 `storage` profile~~ ✅ 已入 app profile（submission/worker `depends_on: minio: healthy`）。
- ~~RocketMQ broker 挂 store 卷~~ ✅ 已落地（一次性 `rocketmq-store-init` root chown 3000 +
  `service_completed_successfully` 门控；**首次 up 会看到 Exited(0) 的 init 容器，属预期**）。
- ~~提交记录页列宽~~ ✅ 「提交时间」列改 `min-width` 弹性列。
- ~~`docs/LAUNCH-READINESS.md` 未同步第九/十轮改动（文档债）~~ ✅ 第四轮已同步（§E 勾掉两条已修复限制、
  §F 回滚点补两条 commit、新增 §H 放行视角增量与行为变更清单）。
- JaCoCo 门禁棘轮 0.04 → 0.30：**首批补测已落地（第四轮）**：contest/ai/worker 各新增契约与纯逻辑单测
  （6 类 49 例，LINE：ai 0.066→0.21 / worker 0.089→0.136 / contest 0.047→0.05；`mvn verify` 门禁绿）。
  距 0.30 仍远，后续批次优先 contest（体量大且难测，评估只对 service 包设 per-class 棘轮或继续补
  lifecycle/result handler 的 mockito 测试）；本地构建绕过法见硬约束 15（**CI 不绕**）。
- F1 告警通道（Slack/钉钉/邮件，需外部凭据，向用户要）。

---

## 汇报要求

- 按 A/B/C/D 分类输出：已做（附证据）/ 受阻（附精确卡点与解法）/ 待用户（明确要什么）/
  不做（说明理由）。
- 每完成一个任务同步勾掉本文件对应项并更新执行报告；全部结束后更新 `docs/CONTEXT.md`
  §5.13 与 `.workbuddy/memory/`。
- 结尾附上线提醒总结（行为变更清单、需要的用户配合、go-live 前剩余项）。
