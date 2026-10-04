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
>   **U2 已完成（2026-10-03 第六轮）**：contest 覆盖率 0.047→**0.748**（6 类 66 例新测试，
>   contest 84 例全绿），双棘轮落 pom（全局 0.04→0.10 + contest 专属 0.70），
>   全 reactor `mvn verify` BUILD SUCCESS（11 模块 321 例，CI 同命令）。
>   **U1 已完成（2026-10-04 第八轮，T4.2 结案）**：宿主重启后有效 1h soak——**8,879,545 样本
>   0 错误 / 2466.6 req/s / 四 GET P95 8–12ms**；soak.jmx 定时重登实测两轮（T+25min/T+50min）
>   如期触发无 401，§3.8.1 风暴根因闭环；§3.2 四项观测 + verify-p1-login **43/0** 全过。
>   数字与观测全文落 PERF.md §3.8.2。
>   **U3 已完成（2026-10-04 第九轮）**：trivy 镜像 4 张 + fs 逐模块 9 模块；3 个真实
>   CRITICAL 升级落位（**tomcat 10.1.55 / netty 4.1.137.Final / bcprov 1.85**，fastjson 判误报，
>   tomcat 残留 CVE-2026-65182 系上游 10.1.58 未发布→接受+跟进）；ZAP baseline **H0/M0/L0**；
>   8 服务镜像重建重部署 8/8 healthy，verify-p1-login **43/0** 回归全过（dbb32e7）。
>   **U4 第一批已完成（6c85b04）**：Playwright E2E 冒烟两条链路 **2/2 通过**。
> - **未完成汇总**（详见下文「未完成任务」区）：
>   **U4** Playwright E2E 剩余链路（选题→提交→判题→榜单→AI 点评，渐进补）·
>   **U5** remote+徽章（待用户）· **U6** 告警通道凭据（待用户）·
>   **U7** 生产告警重标 · **U8** HTTPS · **U9** 独立压测机（生产化三件）。
> - ✅ **环境现状（2026-10-04 晚）**：宿主已重启，Docker daemon 恢复后**全程无复发**
>   （重启前病灶链见执行报告第七轮/CONTEXT §5.20）；CodeJudge 全栈 20 容器 healthy
>   （第九轮已用升级后依赖重建 8 服务镜像并重部署）。

---

## 背景与现状（一句话版）

CodeJudge（分布式在线编程评测平台，8 后端微服务 + Vue 前端，`D:\1\CodeJudge`）静态交付全绿
（`bash docs/launch-verify.sh` → 42/0），容器化全栈已于 2026-10-01 实测跑通
（health / 登录 / 判题 / 日志→Loki / 压测全链路），五个收尾 commit 已落库。
**2026-10-04 晚现状：宿主已重启，全栈 20 容器 healthy（8 服务镜像已含升级后依赖重部署）。**
剩余工作集中在：E2E 剩余链路（渐进）、CI/remote（需用户）、生产化三件（E4/M2/M4）。
权威进度读 `docs/CONTEXT.md`（§5.13–§5.22）与执行报告（第二～第九轮表）。

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
| U2 contest 补测 | LINE 0.047→**0.748**（6 类 66 例，84 例全绿）；双棘轮 0.10 + contest 0.70；全 reactor verify SUCCESS | 执行报告「第六轮」/ CONTEXT §5.17 |
| **U1 有效 1h soak** | **8,879,545 样本 0 错误 / 2466.6 req/s**；四 GET P95 8–12ms；续签两轮（T+25/T+50min）无 401；§3.2 四项观测过；verify-p1-login **43/0** | PERF.md §3.8.2 / 执行报告「第八轮」/ CONTEXT §5.21 |

### 未完成任务（本轮焦点，按解锁条件分组）

#### U0. 环境预检与 Docker daemon 修复（用户动作；其余任务的前置）

- 现状（10-04 第七轮复检）：**Docker Desktop 本体未启动**（无 docker 进程 / vmmemWSL 不存在 /
  npipe 管道缺失）；宿主空闲 6.36/15.2GB（已达标 6GB 预检线）。
- 用户动作顺序：① 直接启动 Docker Desktop → `docker version` 出 Server 段（栈随 restart 策略
  自动拉起，无需手工起栈）；② 若 VM 病态依旧（daemon 500 / 探活异常）→ **重启电脑**
  （先确认 zx-learn 等大内存负载不在跑，见硬约束 18d）。
- AI 侧只做预检与记录（`docker version` / 内存余量 / 容器自启情况），不重复拉起手段（硬约束 13）。

#### U1. T4.2 有效 1h soak（✅ 已完成 2026-10-04 第八轮，结案）

- ✅ **结案**：宿主重启后有效 1h soak 全绿（8,879,545 样本 0 错误 / 2466.6 req/s / 四 GET
  P95 8–12ms / 门槛 5 PASS 0 FAIL）；soak.jmx 定时重登实测两轮（T+25min、T+50min）无 401；
  RUNBOOK §3.2 四项观测全过（无泄漏/连接池零累积/队列归零/无静默损坏）；verify-p1-login **43/0**。
  证据与全文数字见 **PERF.md §3.8.2**、执行报告「第八轮」、CONTEXT §5.21。

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

#### U2. JaCoCo 棘轮 0.04 → 0.30（✅ 已完成 2026-10-03 第六轮，超出目标）

- ✅ **contest 补测 6 类 66 例（18→84 例）**：lifecycle / result handler / rank service
  （Lua 参数逐位断言 + 三段解码渲染）/ pusher / service 建赛与 context / WS 特权门。
  `mvn -pl judge-contest test` **84/0**。
- ✅ **覆盖率**：contest LINE **0.047 → 0.748**（jacoco.csv 702/237）。
- ✅ **双棘轮**：根 pom 全局下限 0.04 → **0.10**（新最低 common 0.120 留余量）；
  judge-contest 模块专属 **check-contest 0.70** 锁高位。
- ✅ **CI 同命令实证**：全 reactor `mvn -B -ntp verify` **BUILD SUCCESS**（11 模块 321 例全绿）。
- 纪律遵守：未改 judge-common（14 N/A）；全程未用 `-Djacoco.skip=true`（15）；
  contest 与全 reactor `mvn test` 真跑（17）。证据与坑见执行报告「第六轮」/ CONTEXT §5.17。
- 下一批覆盖率短板：judge-common 0.120 / problem 0.124 / gateway 0.260（抬全局到 0.30 的路径）。

#### U3. T5 扫描类（✅ 已完成 2026-10-04 第九轮，处置全文见 DEPLOYMENT.md §8.4.1）

**结果**：镜像 4 张（java21 净 / python312 51H / gcc13 481H+32C / go122 1144H+26C，OS 包项接受）
+ fs 逐模块 9 模块（并发扫有 POM 分析器死线 bug，须逐模块）；3 真实 CRITICAL 钉版清零
（tomcat 10.1.55 / netty 4.1.137.Final / bcprov 1.85），fastjson 误报（dependency:tree 零命中）；
ZAP baseline H0/M0/L0；镜像重建（`build-app-images-prebuilt.py --worker-docker-cli`，被墙环境
唯一构建路径）+ 重部署 + verify-p1-login 43/0。证据 `docs/security-evidence/2026-10-04-{trivy,zap}/`。
遗留跟进：tomcat CVE-2026-65182（上游 10.1.58 发布后升版复扫）；trivy DB 拉取用
`--db-repository ghcr.io/aquasecurity/trivy-db`（mirror.gcr.io 拒连）。

#### U4. T8 Playwright E2E（✅ 第一批已完成 2026-10-04：冒烟 2/2，6c85b04；剩余链路渐进补）

- **已完成**：登录 → 落地 /problems；题库列表有数据。两条冒烟链路 2/2 通过
  （serial + storageState 复用避登录限流；`judge-web/e2e/smoke.spec.js`，
  凭据 13900000001/123456 与 verify-p1-login 同源；跑法 `cd judge-web && npm run test:e2e`）。
- **剩余（渐进补）**：选题 → 编辑 → 提交 → 看判题结果 → 查榜单 → AI 点评。

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
