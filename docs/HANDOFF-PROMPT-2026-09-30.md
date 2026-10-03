# CodeJudge 上线收尾 · 新对话执行提示词（2026-10-03 更新）

> 用法：把本文件全文作为任务输入发给 AI 编程助手（文末另附精简指针版提示词）。
> 每完成一项附验收证据（命令输出/文件路径），同步更新
> `docs/LAUNCH-CHECKLIST-执行报告-2026-09-30.md`（现有「第二～第五轮」表，新工作开「第六轮」小节）
> 与 `docs/CONTEXT.md`（现有 §5.13–§5.16，新工作追加 §5.17+）。
> 阻塞的项不许跳过验收硬凑（**假绿比 FAIL 危险**），标注 BLOCKED + 原因即可。

> **执行状态速览（2026-10-03，权威进度 = 执行报告「第五轮」表 + `docs/CONTEXT.md` §5.16）**
>
> - **已完成**（commit 链 `35367ef` → `649d51d` → `d9c67b1` → `1032c17`，工作区干净）：
>   T0（根因=宿主内存压力，停 zx-learn 即恢复）｜ T1 ｜ T2（verify-p1 **43/0**）｜
>   T3（verify-p3 **21/0** + 容器化契约入 DEPLOYMENT §3.3.1）｜ T4.1（10min soak 全绿，
>   127 万样本 0 错误 2118.7 req/s）｜ T6（/v1 + verify-authz **73/0**）｜
>   T9（cj_judge_e2e 六 verdict 序列对齐）｜ T10（Loki 全链路 + requestId 命中）｜
>   T11 已落地五项（MinIO app profile / broker store 卷 / 提交页列宽 / READINESS 同步 / JaCoCo 首批 6 类 49 例）。
>   **容器化全栈（compose --profile app，8 服务 + web）已于 2026-10-01 实测跑通全链路。**
> - **未完成汇总**（详见下文「未完成任务」区）：
>   **T4.2 有效 1h soak**（30min 处被 access token TTL 截断，需续签改造，复盘见 PERF.md §3.8.1）·
>   **T5** trivy/ZAP（BLOCKED 待外网，命令已备）· **T7** remote+徽章（待用户）·
>   **T8** Playwright E2E（待外网+排期）· **F1** 告警通道凭据（待用户）·
>   **JaCoCo 0.04→0.30**（contest 0.05 短板，可立即做）·
>   **E4** 生产告警重标 · **M2** HTTPS · **M4** 独立压测机（生产化三件）。
> - ⚠️ **环境现状（2026-10-03）**：Docker daemon 再次故障（`docker ps` 返回
>   dockerDesktopLinuxEngine 500；vmmemWSL 占 4GB，宿主空闲仅 3.1/15.2GB）。
>   依赖 Docker 的任务开跑前先 `docker version` 预检；修复属用户动作（见 U0）。

---

## 背景与现状（一句话版）

CodeJudge（分布式在线编程评测平台，8 后端微服务 + Vue 前端，`D:\1\CodeJudge`）静态交付全绿
（`bash docs/launch-verify.sh` → 42/0），容器化全栈已于 2026-10-01 实测跑通
（health / 登录 / 判题 / 日志→Loki / 压测全链路），五个收尾 commit 已落库。
**当前无 CodeJudge 服务在跑，Docker daemon 故障中（500）。**
剩余工作集中在：soak 长稳收口、扫描类（需外网）、CI/remote（需用户）、测试覆盖率、生产化三件（E4/M2/M4）。
权威进度读 `docs/CONTEXT.md`（§5.13–§5.16）与执行报告（第二～第五轮表）。

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
   daemon.json.tmp Access is denied`）。`explorer.exe` 中转仅偶发成功，不可依赖。
   `~/.docker/daemon.json.off` 是原 registry-mirrors 配置备份。
7. 9 容器（8 JVM + web）**同启会压垮 daemon**（已发生一次崩溃事故）：分批 `up -d`，或先升
   Docker Desktop 资源配额再全量。
8. `mvn clean` / `npm run build` 前必须先停对应服务/预览进程（Windows 锁 `target/*.jar`、
   预览占 `dist/`）。`docker-compose --profile app`（容器化）与宿主机直跑
   `python scripts/start-all.py` **互斥**，混用端口冲突。
9. start-all 跑的是 `target/*.jar`：改了 yml/Java 必须 `mvn package` 重打包（无需 clean，但先停服务）。
10. 两处限流压测前必放宽、测后必恢复并验证：登录令牌桶（2 req/s 突发 5）、提交限流
    （30 次/分钟/账号）。验收录 `python scripts/verify-p*.py` / `verify-authz.py`（现为 73 项）。
11. 长跑命令用受管后台任务（`&` 起的子进程会被 Job Object 连带回收）；
    ⚠️ **受管后台任务也会随会话结束被整树回收**（1h soak 55:40 被杀的根因）——
    跨会话长跑要么请用户手动跑，要么按「.jtl 落盘完整、事后可解析」做被杀预案。
12. 性能数字必须真实运行得出，禁止估算；验收脚本断言必须同时校验样本非空。
13. **AI 侧拉起 Docker Desktop 的手段已全部穷尽，勿再重试**：`Start-Process` 必崩（受限令牌）、
    `schtasks` 被程序黑名单拦死、`explorer.exe` 中转不可依赖、杀 `com.docker.backend` 后
    monitor 不会自动重拉。Docker 恢复只能靠用户操作。
14. **改 judge-common 后的打包陷阱**：maven-jar-plugin 内容未变会跳过重建（forceCreation=false），
    `-pl <服务>` 不带 `-am` 时 fat jar 嵌的是 `~/.m2` 旧 judge-common —— 必须先 `install` 再
    `clean package`；验证法 `unzip -p 服务jar BOOT-INF/lib/judge-common-1.0.0.jar | grep JsonLogLayout`
    （fat jar 是嵌套结构，直接对服务 jar grep 不到）。
15. **JaCoCo 棘轮拦 install**：新增零覆盖类会跌破 0.04 门禁 → `mvn install` 被拦；本地构建用
    `-Djacoco.skip=true` 绕过（**CI 不绕**）。contest 仍贴线（0.05）——动 contest 且新增未覆盖代码时照样会被拦。
16. ~~「引擎短暂存活后通路死亡」~~ 后证为宿主内存压力（见 18d）。排查入口保留：
    host 侧 `com.docker.backend.exe.log` apiproxy 段 + `vm/init.log` 是否冻结 + 内存余量。
17. **给 Bean 加构造依赖后必须真跑一次 `mvn test`** —— package 跳过 test（或沿用旧产物）时会
    掩盖既有测试的 `@InjectMocks` NPE（实例：T9 加 `JudgeE2eMetrics` 后 `JudgeEngineFailTaskTest`
    静默破坏）。
18. **（10-01 第五轮）容器化形态（compose --profile app）专用契约**（全文 DEPLOYMENT.md §3.3.1）：
    a) healthcheck CMD-SHELL 引用 `$$VAR` 必须**双引号**（单引号 → 字面量 → ActuatorGuardFilter
    404 → 假 unhealthy）；
    b) broker 容器形态 `brokerIP1` 必须注册**服务名**（`deploy/rocketmq/broker-compose.conf`）；
    两份 conf 除 brokerIP1 外必须一致；
    c) `/cj-sandbox` 沙箱 `--user` 必须 = worker uid（1001）；docker.sock 用 `group_add: ["0"]`；
    d) **Docker 通路反复死亡先查内存**：zx-learn 全栈与 CodeJudge 全栈不能共存（10-01 根因；
    10-03 daemon 500 再次佐证——vmmemWSL 4GB + 宿主仅余 3.1GB）。
19. **（10-01 第五轮）日志→Loki 链路**：promtail 优雅停机会把内存 positions **落盘回写**——
    `rm positions.yaml` + `docker restart` 等于没删，**新 pipeline 生效 = 重启业务进程产新行**；
    文件名正则按容器内**正斜杠**路径写；gateway（WebFlux，无 servlet 拦截器）JSON 行无 requestId
    属设计；Loki 数据随监控栈卷重建丢失。
20. **（10-01 第五轮）密钥不物化进 shell**：把 .env 的 `CJ_ACTUATOR_TOKEN`/`CJ_JWT_SECRET`
    提取进 shell 环境会被安全策略拒绝。prod one-off 验证走免凭据方案：启动行 +
    匿名 `GET /problems/page`（public-read）即可产证。
21. **（10-01 第五轮）脱离进程与打包纪律**：detached 服务进程（`dev-start-backend.py` /
    one-off `java -jar`）必须**关沙箱**启动（沙箱 Job Object 会在命令退出时杀子进程 →
    「启动成功数秒后 gateway 静默死亡」假象）；`mvn package` 前先按 CommandLine 精确 PID
    停服务（Windows 锁 jar）。
22. **（10-03 复盘）soak 计划无 token 续签**：`ACCESS_TOKEN_TTL=30min`
    （`judge-auth AccountService` 常量），soak 每线程只登录一次。**长稳超 30 分钟必然出现
    「鉴权接口全 401 + `/problems/page`（public-read）100% 成功」的 401 风暴——这是 token
    过期特征，不是系统故障**（旁证：网关 401 拒绝路径 6900 req/s avg 2ms 不倒）。有效 1h soak
    必须先做 U1 的续签改造。

---

## 任务清单

### 已完成区（勿重做，结论与证据一览）

| 任务 | 结案结论 | 证据 |
|---|---|---|
| T0 Docker daemon | 根因=宿主内存压力（zx-learn 10 容器与 CodeJudge 全栈不能共存）；停 zx-learn 即刻恢复 | 执行报告「第五轮」/ CONTEXT §5.16 |
| T1 commit 链 | `35367ef` → `649d51d` → `d9c67b1` → `1032c17`（第五轮 15 文件 +525/−75） | git log |
| T2 compose 编排实测 | 分批拉起 8 服务 + web；health 全 UP；verify-p1-login **43/0** | 执行报告「第五轮」 |
| T3 沙箱容器化 | verify-p3 **21/0**；uid 1001 + `group_add:["0"]` + broker 双 conf；契约 DEPLOYMENT §3.3.1 | 同上 |
| T4.1 soak 10min | 1,270,883 样本 **0 错误** / 2118.7 req/s；四 GET P95 ≤15ms；限流恢复 2/5 + 429 复验 {200:6, 429:6} | PERF.md §3.8 |
| T6 /v1 版本化 | `/v1/**` 别名与裸路径行为一致；verify-authz **73/0** | DEPLOYMENT §8.3 |
| T9 SLO e2e 指标 | `cj_judge_e2e_seconds_*` 六 verdict 序列与实测 12 终态对齐；看板 9 出数 | 执行报告「第五轮」 |
| T10 日志→Loki | prod JSON 生效（gateway 本地 logback 覆盖）；Loki `{application="judge-*"} \| json` 全链路命中 | 同上 |
| T11 已落地五项 | MinIO app profile / broker store 卷（init 容器）/ 提交页列宽 / LAUNCH-READINESS 同步（§E/§F/§H）/ JaCoCo 首批（6 类 49 例：ai 0.21 / worker 0.136 / contest 0.05） | LAUNCH-READINESS |

### 未完成任务（本轮焦点，按解锁条件分组）

#### U0. 环境预检与 Docker daemon 修复（用户动作；其余任务的前置）

- 现状（10-03）：daemon 500（dockerDesktopLinuxEngine 内部错误）、vmmemWSL 4GB、宿主空闲 3.1/15.2GB。
- 用户动作顺序：① 托盘退出 Docker Desktop → `explorer.exe "C:\Program Files\Docker\Docker\Docker Desktop.exe"`
  中转重启 → `docker version` 出 Server 段；② 仍 500 → **重启电脑**（先确认 zx-learn 等
  大内存负载不在跑，见硬约束 18d）。
- AI 侧只做预检与记录（`docker version` / 内存余量 / 容器自启情况），不重复拉起手段（硬约束 13）。

#### U1. T4.2 有效 1h soak（U0 恢复后第一个跑；先做续签改造）

背景：1h 首跑已复盘（**PERF.md §3.8.1**）——前 30 分钟 4,465,338 样本 **0 错误**（干净）；
第 30:10 分起 access token 同时过期 → 鉴权接口全 401（~82%/区间），`/problems/page`
（public-read）全程 100% 成功。**根因 = 负载模型无续签，不是系统缺陷**。

1. 续签改造三选一（推荐 a，不动生产代码）：
   a) soak.jmx 增加定时重登（如独立线程组每 25 分钟重登，或 JSR223 定时刷新共享 token）；
   b) perf 专用账号放长 TTL（需把 TTL 做成可配置——动生产代码，谨慎）；
   c) 每线程定时走 refresh 换发。
2. 跑前预检：全栈 8 端口 health + 宿主空闲内存 ≥6GB + `GW_LOGIN_RATE_*` 保持 2/5 默认
   （18 登录/30s ramp ≈ 0.6/s，无需放宽）。
3. 跑法：`python perf-test/run-perf.py --plan soak --no-html`。
   ⚠️ 受管后台任务随会话结束被整树回收（首跑 55:40 被杀根因，硬约束 11）——
   要么请用户手动跑，要么接受被杀风险并事后按 §3.8.1 方法复盘（summariser 每 30s 一条 +
   .jtl 分阶段抽样解析，数据不丢）。
4. 跑后：RUNBOOK §3.2 观测项 + `verify-p1-login.py` 43/0 + 数字补 PERF.md §3.8.2 与执行报告。

#### U2. JaCoCo 棘轮 0.04 → 0.30（不依赖环境，可立即做）

- 现状：LINE ai 0.21 / worker 0.136 / contest 0.05（短板），门禁 0.04 仍绿。
- 下批优先 contest（体量大且难测）：评估只对 service 包设 per-class 棘轮，或补
  lifecycle / result handler 的 mockito 测试。
- 纪律：本地构建 `-Djacoco.skip=true`（**CI 不绕**，硬约束 15）；动 judge-common 先 `install`
  （硬约束 14）；给既有 Bean 加构造依赖必须真跑 `mvn test`（硬约束 17）。

#### U3. T5 扫描类（需外网；命令已备，BLOCKED 不许硬凑）

1. trivy（10-01 实测：ghcr.io ~7KB/s ETA 5h、mirror.gcr.io 拒连——待有网环境）：
   ```bash
   for img in java21 python312 gcc13 go122; do
     docker run --rm -v /var/run/docker.sock:/var/run/docker.sock aquasec/trivy:latest \
       image --severity HIGH,CRITICAL --quiet codejudge/judge-$img:latest
   done
   docker run --rm -v "D:/1/CodeJudge:/repo:ro" aquasec/trivy:latest fs --scanners vuln \
     --severity HIGH,CRITICAL --quiet /repo
   ```
   （Windows 下 docker run 加 `MSYS_NO_PATHCONV=1`。）
   HIGH/CRITICAL 有命中逐条处置（升级 base / 白名单说明），写进 DEPLOYMENT.md §8.4 旁。
2. ZAP baseline（zap 镜像本地不存在、docker.io 拉不动）：`zap-baseline.py -t http://host.docker.internal:9080`，高危清零。

#### U4. T8 Playwright E2E（需外网装 chromium；工作量独立可排期）

- 覆盖：登录 → 选题 → 编辑 → 提交 → 看判题结果 → 查榜单 → AI 点评；
  先做登录+题库两条冒烟链路跑通框架，其余渐进补。
- 基础设施：`judge-web` 内 `npx playwright install chromium`（下载浏览器需网）。
- 注意登录限流（2 req/s 突发 5）：E2E 复用会话为主，避免循环反复登录。

#### U5. T7 remote / 徽章 / 首次 Actions（需用户建 remote）

1. 用户建 remote 后：`git remote add origin <url>` 并推送。
2. 替换 README 徽章占位符 `YOUR_GITHUB_ORG/CodeJudge`（共 5 处：CI/Java/Spring Boot/License/Coverage）。
3. 首推后确认 GitHub Actions 真跑一遍 `.github/workflows/ci.yml`（本地从未跑过 Actions）。

#### U6. F1 告警通道（需用户提供凭据）

- Slack/钉钉/邮件 webhook 凭据向用户要；配置进 Alertmanager（`deploy/monitoring/`），
  配一条测试告警验证触达。

#### U7. E4 生产告警重标（上线动作，依赖生产环境与真实流量）

- 全部告警阈值仅适用本地基线（PERF.md §四），上线后按真实容量重标。
- 工具 `scripts/recalibrate-alerts.py` 已备两道守卫：样本稀疏的 URI 不计入延迟基线、
  无提交流量时不改提交阈值——没有数据就不改。

#### U8. M2 HTTPS（生产化，需证书/域名）

- 网关 TLS 终结：证书与域名由用户/运维提供；compose 侧加 443 映射与证书挂载。

#### U9. M4 独立压测机（生产化，生产容量标定前置）

- 本机全部性能数字都是**容量下界**（压测工具与服务同机）。生产标定流程见 `perf-test/RUNBOOK.md`。

---

## 汇报要求

- 按 A/B/C/D 分类输出：已做（附证据）/ 受阻（附精确卡点与解法）/ 待用户（明确要什么）/
  不做（说明理由）。
- 每完成一个任务同步勾掉本文件对应项并更新执行报告（新开「第六轮」小节）；
  全部结束后更新 `docs/CONTEXT.md`（追加 §5.17+）与 `.workbuddy/memory/`。
- 结尾附上线提醒总结（行为变更清单、需要的用户配合、go-live 前剩余项）。

---

## 新对话提示词（2026-10-03，基于未完成任务生成）

**用法一（推荐，信息完整）**：把本文件全文作为第一条消息发给 AI。

**用法二（精简指针版）**：复制以下代码块——

```text
请先完整阅读 docs/HANDOFF-PROMPT-2026-09-30.md（CodeJudge 上线收尾执行提示词，2026-10-03 版），
理解其中的硬约束与未完成任务清单（U0–U9），然后按以下顺序执行：

1. U0 环境预检：docker version + 宿主内存余量，报告现状。Docker daemon 当前故障属用户动作，
   依赖 Docker 的任务（U1 soak / U3 扫描）在恢复前标注 BLOCKED，不得硬凑。
2. 不依赖环境的任务立即做：U2 JaCoCo contest 补测（棘轮 0.04→0.30）。
   遵守硬约束 14/15/17：动 judge-common 先 install；jacoco.skip 仅限本地构建、CI 不绕；
   给既有 Bean 加构造依赖必须真跑 mvn test。
3. 需要外网或用户配合的任务（U4 Playwright / U5 remote+徽章 / U6 告警凭据 /
   U8 HTTPS / U9 压测机）：逐项输出精确卡点与所需配合，不得跳过验收硬凑。
4. U0 恢复后执行 U1：先做 soak token 续签改造（三选一，推荐 soak.jmx 定时重登，不动生产代码），
   再跑 1h 全量；注意后台任务会随会话结束被杀（硬约束 11），做好 .jtl 事后复盘预案；
   跑完 RUNBOOK §3.2 观测项 + verify-p1-login 43/0 + 数字补 PERF.md §3.8.2。

汇报按 A/B/C/D 分类（已做附证据 / 受阻附卡点 / 待用户 / 不做附理由），
同步更新执行报告（新开「第六轮」）与 CONTEXT.md（追加 §5.17+），结束后给上线提醒总结。
```
