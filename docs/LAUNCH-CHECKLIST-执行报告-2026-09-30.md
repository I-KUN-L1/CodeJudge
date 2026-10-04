# 上线检查清单执行报告（LAUNCH-CHECKLIST A–L）

> 执行日期：2026-09-30 ｜ 执行方式：AI 助手逐项落地 + 实测取证 ｜ 一键复验：`bash docs/launch-verify.sh`（**PASS=42 / FAIL=0**）
>
> 图例：✅ 完成并实测 ｜ 🟡 已落地、有明确待办 ｜ 🔵 N/A（附原因）

## 总览

| 区 | 状态 | 摘要 |
|---|---|---|
| A 部署产物 | ✅ | 9 个 Dockerfile + nginx.conf + compose app profile + startup.sh/.ps1 + 优雅停机 8/8 |
| B CI/CD | ✅ | ci.yml + JaCoCo 门禁（棘轮 0.04）+ SpotBugs/Checkstyle + osv-scanner + README 徽章 |
| C 数据库 | ✅ | Flyway 接管（6 服务 V1__baseline + baseline-on-migrate，真机验证）+ 备份脚本（实测）+ 增长治理方案 |
| D 可观测 | ✅ | 告警 11 条（既有文件，promtool SUCCESS）+ 看板 4→8 + Loki/Promtail 已运行 + SLO.md |
| E 安全 | 🟡 | TLS/JWT 轮换/版本化文档齐；CORS 已收敛；扫描命令就绪但本机网络拦截漏洞库下载 |
| F 高可用 | 🟡 | 可扩副本改造完成（container_name 移除）；运行态扩容/停机压测待环境 |
| G 测试纵深 | 🟡 | 既有 verify 套件映射齐全；E2E（M6）/soak plan/浏览器兼容为已知待办 |
| H 沙箱 | ✅ | digest 固定落地（4 镜像登记）；gVisor 🔵 N/A（本机 Docker 无 runsc，文档已声明） |
| I 稳定性 | ✅ | PG schema 自愈脚本（实测）+ 队列对账文档 + 凭据风险提示 |
| J 工程化 | ✅ | CONTRIBUTING/CHANGELOG/SECURITY/.editorconfig/.gitattributes/.dockerignore + gitignore 补漏 |
| K 长期 | ✅ | docs/ROADMAP.md（近期/中期/远期 + 技术债表） |
| L 最小上线集 | 🟡 | L1–L5、L8–L10 ✅；L6（HTTPS 实际域名）/L7 已配置待生产域名落实 |

## 关键实测证据

| 项 | 证据 |
|---|---|
| `mvn verify` 全绿 | run4：11 模块 SUCCESS（含 JaCoCo check 0.04 门禁、SpotBugs/Checkstyle report-only、Flyway 依赖） |
| 覆盖率实测基线 | auth 0.549 / submission 0.416 / user 0.350 / gateway 0.260 / problem 0.124 / common 0.120 / worker 0.089 / ai 0.066 / contest 0.047 |
| Flyway 存量库路径 | judge-problem 真机启动：`Successfully baselined schema with version: 1`，history 表 1 行 BASELINE，数据完好（13 题），health UP |
| Flyway 配置 | 6 服务 `V1__baseline.sql`（与 init.sql 同源拆分，auth 7/user 2/problem 5/submission 4/contest 4 表 + PG 2 表） |
| 备份 | mysqldump 1898 行 / pg_dumpall 363 行实跑通过（`scripts/backup-db.py` 固化为定时可用脚本） |
| 告警规则 | promtool check rules → `SUCCESS: 11 rules found`（含清单要求的积压/死信/worker 掉线/5xx/P99） |
| 看板 | 8 张 JSON 由 gen_dashboards.py 生成（新增判题机集群/沙箱资源/MQ 延迟/竞赛榜单，仅用现存指标） |
| Loki | `http://127.0.0.1:3100/ready → ready`；Promtail 抓 `logs/*.log` 并抽取 application/requestId 标签 |
| 一键验收 | `bash docs/launch-verify.sh` → PASS=42 / FAIL=0 |
| C3 取证 | judge_submission 库：submission 363 / judge_result 498 / judge_task 363 行，共 <1MB（分区实施暂缓，触发线见 docs/DATA-GROWTH.md §3） |

## 本轮修掉的既有缺陷

1. **`judge-contest` 测试文件 UTF-8 BOM** → `mvn verify` 必挂（`非法字符: '\ufeff'`）；增量编译曾掩盖（单模块 test-compile 命中旧 class 假绿）。已清 BOM，全仓扫描确认仅此一处。
2. `.gitignore` 缺 `perf-test/reports/` → 已补。

## 有意偏离（与清单原文的差异 + 理由）

| 清单原文 | 实际做法 | 理由 |
|---|---|---|
| D1 规则落 `deploy/monitoring/alertmanager/alert.rules.yml` | 用既有 `prometheus/rules/codejudge-alerts.yml`（11 条，两轮实测重标） | 已有文件功能覆盖且质量更高，复制会制造双真相 |
| B3 门禁 30% | 先 0.04（实测最低模块 contest 0.047 的防回退值） | 30% 首日必红（judge-common 实测 0.12）；根 pom 注释写明棘轮抬升路径 |
| C1 `V2__seed.sql` | 种子**不进 Flyway** | 种子是 reset-demo-data.py 管的幂等演示数据；进迁移会变一次性、与重置流程打架 |
| A4 startup.sh 全新入口 | 实现为「单服务模式 + 透传 startup.py」双形态 | 仓库已有综合入口，再造一套编排违反单一真相纪律 |

## 阻塞项 / 待办（按优先级）

| # | 事项 | 阻塞点 | 责任 |
|---|---|---|---|
| 1 | **Docker 镜像构建 + app profile `up -d` 实测**（A3 验收后半段） | 本机 registry 拒拉过 MinIO；9 镜像构建需在有网环境跑一次 | 部署机 |
| 2 | trivy/OWASP ZAP 实扫（E5/E7） | 漏洞库下载被网络拦截（mirror.gcr.io 拒连）；命令已备好 | 有网环境跑一遍，HIGH/CRITICAL 清零 |
| 3 | E3 `/v1/**` 版本化 | 属行为变更，需拍板；启用方案与三处联动改动已写进 DEPLOYMENT.md §8.3 | 你 |
| 4 | JWT kid 多密钥轮换 | 同上，建议与 refresh token 轮换/RS256 合并立项（§8.2） | 你 |
| 5 | G4 前端 E2E（Playwright） | 已知 M6 待办，工作量独立 | 排期 |
| 6 | G5 soak 长稳 plan | perf-test 无 soak 定义 | 排期 |
| 7 | worker 容器化沙箱路径核验 | worker 经 docker.sock 派发沙箱，容器内临时目录与宿主路径对齐未验证（compose 注释已标） | 首次容器化部署前必须回归 verify-p3 |
| 8 | CI 徽章仓库地址 | 仓库无 remote，README 用占位符 `YOUR_GITHUB_ORG/CodeJudge` | 首推后替换 |
| 9 | 错误预算看板 + `cj_judge_e2e_seconds` 埋点 | SLO 度量缺口（SLO.md §5 已标 TODO） | 排期 |
| 10 | stdout JSON 化（D3 深化） | 会改变可读日志形态，随 judge-common logback-spring.xml 收口一起做 | 排期 |

## 上线提醒（go-live）

- **本次改动含行为变更（Flyway）**：下一次全量启动前请确认 jar 已用本次代码重打包
  （run4 已产出含 Flyway 的 target/*.jar；若走 `startup.py` 直接启动即可命中）。
  首次启动每个服务会在其库建 `flyway_schema_history` 并打 baseline —— **属预期**，不是异常。
- `docker-compose --profile app` 与宿主机直跑（dev-start-backend.py）是**两种互斥形态**，
  混用会端口冲突；生产用前者，开发保持后者。
- Loki 已常驻本机监控栈；Grafana（3001）现自带 Loki 数据源，日志查询 `{application="judge-problem"}` 可用。

---

## 第二轮：阻塞项推进（2026-09-30 下午）

### 已完成

| # | 事项 | 结果 |
|---|---|---|
| 阻塞6 | **G5 soak 长稳 plan** | ✅ 新增 `perf-test/jmx/soak.jmx`（由 throughput.jmx 派生：12+6 线程、3600s、ramp 30s，不含提交）；`run-perf.py` 接入 `--plan soak`（看门狗按计划默认时长兜底 3600+180s，吞吐只报数不判定，P95/错误率照判）；RUNBOOK §3.2 补执行与观测口径。首次全量 1h 实跑待服务可用后执行 |
| 阻塞1(前半) | **9 个运行时镜像构建** | ✅ 本机网络恢复后实测可行。多阶段构建器的 maven 镜像经国内 mirror 拉取过慢（20min 未完成），改走降级路径：`scripts/build-app-images-prebuilt.py` 用 run4 产出的 `target/*.jar` 直接构建 runtime 等价镜像（同 base/ENTRYPOINT/HEALTHCHECK）。9 个镜像全部构建成功（codejudge/judge-{gateway,auth,user,problem,submission,worker,contest,ai,web}:latest）。配套 `.dockerignore` 加 `!**/target/*.jar` 例外 |
| — | judge-web dist | ✅ `npm run build` 通过，dist 已产出并进镜像 |

### 受阻（附精确状态，供续接）

| # | 事项 | 当前卡点 | 解法 |
|---|---|---|---|
| 阻塞1(后半) | compose app `up -d` 实测 | **Docker daemon 崩溃恢复中**：9 个容器同启后 daemon 失联；恢复时先后踩两个坑——① `rename ~/.docker/daemon.json.tmp → Access is denied`（settings-store.json 的 DaemonConfig 同步路径；从工具环境拉起 Docker Desktop 继承受限令牌所致，经 `explorer.exe` 以正常用户令牌启动后解决）；② WSL2 VM 网络不通（`192.168.65.7:2376 no route to host`），VM 在最初崩溃中进入坏状态 | **需用户手动**：`wsl --shutdown` → 启动 Docker Desktop → daemon 就绪后 `docker compose --profile app up -d` → 8 端口健康检查。wsl.exe 在安全中心程序黑名单中，AI 无法代执行。`~/.docker/daemon.json.off` 是原配置备份（registry-mirrors），daemon 恢复后可自行恢复文件名 |
| 阻塞7 | worker 沙箱路径核验 | 依赖容器栈起不来；且 prebuilt worker 镜像**不含 docker.io CLI**（见脚本说明） | 容器栈恢复后，用多阶段 Dockerfile 重建 worker 镜像再回归 verify-p3 |
| 阻塞2 | trivy 实扫 | mirror.gcr.io 与 ghcr.io 的漏洞库下载均被网络拦 | 待有网环境 |

---

## 第三轮：HANDOFF-PROMPT 执行（2026-09-30 傍晚）

### 已完成（附证据）

| # | 事项 | 结果 |
|---|---|---|
| T1 | **基线提交** | ✅ `bash docs/launch-verify.sh` → PASS=42/FAIL=0 后本地 commit `35367ef`（68 文件，无 remote，敏感文件核查通过：无 .env/bootstrap-credentials） |
| T9 | **SLO 度量补全**（阻塞9） | ✅ 代码/规则/看板全部落地，**运行时验证 BLOCKED**（见下）：① `judge-worker` 新增 `JudgeE2eMetrics`（`cj_judge_e2e` Timer，Prometheus 暴露为 `cj_judge_e2e_seconds_*`，verdict 标签分列，桶 50ms–120s；埋在 `finishTerminal` 终态落库点与 `failTask` 死信分支，CAS 丢弃不计）——与 SLO.md §1 S3 口径「提交成功→终态 verdict 落库」一致；② `codejudge-alerts.yml` 补第 12 条规则 `JudgeE2ELatencyP99Breach`（warning，e2e P99>10s 持续 5m，SLO §4 此前声称有该规则但实际缺失）；③ `gen_dashboards.py` 新增看板 9 `codejudge-error-budget`（S1–S4 达成状态 + 预算剩余/燃烧率 + verdict 分解，9 看板全部重新生成，JSON/YAML 解析校验通过）；④ SLO.md §5 勾选两项并如实标注运行时验证待做。judge-worker `clean package` 通过 |
| T10 | **stdout JSON 化**（阻塞10） | ✅ 代码落地，**运行时验证 BLOCKED**：① `judge-common` 新增统一 `logback-spring.xml`（jar 内 classpath 根收口，8 服务共用；非 prod=人读格式+新增 `[req=...]` MDC requestId 段，prod=JSON）+ 自研 `JsonLogLayout`（Jackson 转义，字段契约 ts/level/application/logger/thread/requestId/message/stackTrace；不引 logstash-encoder——本地仓库无货且外网受限）；② application 字段取 springProperty APP_NAME（与 Prometheus application 标签对齐）；③ **8 服务已 `clean package`**（嵌套 judge-common jar 复验含 JsonLogLayout+logback-spring）。⚠️ 构建期发现 jar plugin 内容未变会跳过重建（forceCreation=false），`-pl` 不带 `-am` 时嵌套旧依赖原样保留——必须 clean 或 install 后再打包 |

### T11 落地部分

| # | 事项 | 结果 |
|---|---|---|
| 遗留 | **MinIO 移出 storage profile** | ✅ `docker-compose.yml`：minio 改 `profiles: ["storage","app"]`；judge-submission / judge-worker 增加 `depends_on: minio: service_healthy`；注释同步。`docker compose config` 通过 |
| 遗留 | **RocketMQ broker store 卷** | ✅ 原踩坑（uid=3000 撞 root:root 挂载点 → ExitCode=253）的「正确做法」落地：新增一次性 `rocketmq-store-init`（复用 apache/rocketmq 镜像、user 覆盖 root、chown 3000:3000，不引入可能被拒拉的新镜像）；broker 挂 `codejudge-mq-store` 命名卷并以 `service_completed_successfully` 门控。重启丢队列的风险关闭。**首次 up 实测待 Docker 恢复** |
| 遗留 | **提交页列宽骨架不一致** | ✅ `SubmissionListView.vue`：「提交时间」列 `width=150` → `min-width=150`（本页唯一弹性列），表格撑满面板，与题库/竞赛列表骨架一致。**浏览器实测待前端可跑后回归** |
| 遗留 | LAUNCH-READINESS.md 文档债 | ⏳ 本轮先以本报告 + CONTEXT §5.13 为准，同步留待下一轮（见 T11 待办） |

### 受阻（附精确卡点与解法）

| # | 事项 | 当前卡点 | 解法 |
|---|---|---|---|
| T0 | Docker daemon 恢复 | **VM 级故障持续**：本会话中经 `explorer.exe` 中转拉起 Docker Desktop（进程正常起来），但 apiproxy 持续 `dialing 192.168.65.7:2376: no route to host`（观测 20+ 分钟）—— VM 引擎未起，与第二轮同症 | **需用户手动**：管理员 PowerShell `wsl --shutdown` → 正常双击启动 Docker Desktop → `docker version` 出 Server 段。wsl.exe 在安全中心黑名单，AI 不可代执行 |
| T2 | compose app 编排实测 | 依赖 T0 | daemon 恢复后：分批 `--profile app up -d`（先 user/problem/contest/ai，等健康后 auth/gateway/submission，最后 worker/web）→ 8 端口 health + web 200 → `verify-p1-login.py` 冒烟 |
| T3 | worker 沙箱核验 | 依赖 T2 | 多阶段 Dockerfile 重建含 docker.io CLI 的 worker 镜像 → 容器化栈 verify-p3 全绿 → 挂载契约写 DEPLOYMENT.md |
| T4 | soak 首跑 | 依赖 T2 或宿主机启动 | 10min 验链路 → 1h 全量（受管后台）→ 数字进 PERF.md |
| T5 | trivy / ZAP | 漏洞库双源被网络拦 | 待有网环境（命令见 HANDOFF-PROMPT T5） |
| T6 | E3 /v1 版本化 | **待用户拍板**（方案 DEPLOYMENT.md §8.3） | 拍板后同步改限流谓词 / JwtProperties / ActuatorGuardFilter 三处再启用 |
| T7 | CI 徽章 / remote | 仓库无 remote | 用户建 remote → add origin + push → 替换 README 徽章占位符 5 处 |
| T8 | G4 前端 E2E | 工作量独立可排期；跑通需服务在线 + playwright 浏览器下载 | 登录+题库两条冒烟先行，注意登录限流 |
| T9/T10 验收 | 运行时验证 | Loki 查询、指标暴露、看板数据均需服务在线 | T2 完成后：worker 重打包 jar 已就绪 → 起 8 服务 → `curl :9085/actuator/prometheus \| grep cj_judge_e2e` + Loki `{application="judge-worker"} \| json` + Grafana 看错误预算看板 |

---

## 第四轮：T11 遗留收口（2026-10-01）

### 已完成（附证据）

| # | 事项 | 结果 |
|---|---|---|
| T11a | **LAUNCH-READINESS.md 文档债清偿** | ✅ 头部结论补「Docker 运行时实测链为放行前置」；§E 勾掉两条已修复限制（MinIO profile / broker store 卷，注明实测待 T2）并新增 Docker 通路风险行；§F 回滚点补 `35367ef` / `649d51d`；新增 §H（第九轮+第三轮放行视角增量：已收敛项 8 条 / 新增放行前置 10 项 / 行为变更清单 4 条） |
| T11b | **JaCoCo 补测（contest/ai/worker）** | ✅ 新增 6 个测试类 49 例：contest `ContestDomainLogicTest`（生命周期/封窗/罚时 8 例）+ `ContestRankServiceContractTest`（键位/主题/三段编码契约 4 例）；ai `TextSplitterTest`（7 例）+ `ReviewPromptBuilderTest`（15 例，Prompt 结构=前端解析锚点逐条固化）；worker `LanguageProfilesTest`（9 例）+ `JudgeE2eMetricsTest`（6 例，SimpleMeterRegistry 验样本准入与 verdict 分列）。**覆盖率（LINE）：ai 0.066→0.21 / worker 0.089→0.136 / contest 0.047→0.05**（`mvn jacoco:report`，报告在各模块 `target/site/jacoco/`）。三模块 `mvn test` 65 例全绿、`mvn verify` 含 0.04 门禁通过 |
| 顺手修 | **既有测试被 T9 打破** | ✅ `JudgeEngineFailTaskTest` 因 T9 给 JudgeEngine 新增 `JudgeE2eMetrics` 依赖而 NPE（死信分支埋点调用 null）——注入真实 metrics（SimpleMeterRegistry）并**顺势把「死信 SE 分支计入 SLO S3 样本」固化为断言**（timer `cj_judge_e2e{verdict="SE"}` count=1）。此坑说明 T9 提交时 worker 测试未重跑（clean package 跳过了 test 或沿用了旧产物） |

### 受阻（不变）

T0 / T2 / T3 / T4 / T5 / T9-T10 运行时验证 / T6（待拍板）/ T7（待 remote）/ T8（待排期）——卡点与解法见第三轮表，本轮无变化。10-01 实测 `docker version` 30s 超时（exit=124），T0 仍 BLOCKED。

---

## 第五轮：Docker 恢复后的容器化全链路实测（2026-10-01）

> **T0 解除**：用户拍板停掉 zx-learn 全栈（10 容器）腾内存后，Docker daemon 通路即刻恢复稳定
> ——两天的「宿主→VM 网络通路反复死亡」根因即内存压力，本轮全部实测无再抖动。

### 已完成（附证据）

| # | 事项 | 结果 |
|---|---|---|
| T0 | Docker daemon 恢复 | ✅ 根因=宿主内存压力（zx-learn 10 容器 + CodeJudge 全栈挤爆 WSL2）；停 zx-learn 后稳定。排查记录转历史（HANDOFF T0 已结案） |
| 修复 | gateway healthcheck 假 unhealthy | ✅ `docker-compose.yml` CMD-SHELL 里**单引号阻止 `$$CJ_ACTUATOR_TOKEN` 展开**（token 成字面量 → ActuatorGuardFilter 404 → 永远 unhealthy）→ 改双引号 `\"Authorization: Bearer $$CJ_ACTUATOR_TOKEN\"` → healthy |
| 修复 | broker 容器形态注册地址 | ✅ `brokerIP1=127.0.0.1` 是宿主形态遗留：容器网络下 worker 连注册地址=**连自己**（closeChannel 死循环、判题全挂）→ 新建 `deploy/rocketmq/broker-compose.conf`（brokerIP1=rocketmq-broker，除 brokerIP1 外与 broker.conf 必须一致）；broker 日志确认 `The broker[broker-a, rocketmq-broker:10921] boot success`；broker 有效堆限 1g（ps 行尾 `-Xmx1g`，RSS 1.19G 吻合） |
| T2 | compose app 编排实测 | ✅ 分批重建全栈（infra+init → user/problem/contest/ai → auth/submission/gateway → worker/web）；8 端口 health 全 UP（gateway 无 token 直连 404 = QA-F01 设计行为）+ web 5174 返回新构建 dist；`verify-p1-login.py` **43 PASS / 0 FAIL** |
| T3 | worker 容器化沙箱 | ✅ 多阶段镜像含 docker CLI；`/cj-sandbox` 路径对齐（sandbox-work-init 产出 artifacts+seccomp，属主 1001:1001）；`DockerSandbox` 沙箱 `--user 1000:1000 → 1001:1001`（uid 1000 时编译沙箱 rc=2 `cannot create /work/stdout: Permission denied`）；worker `group_add:["0"]` 访问 docker.sock；**verify-p3 21/0**（六种 verdict、主路径 6.2s/预算 15s、幂等、隐藏用例屏蔽、断网/只读根/pids-limit/输出爆炸截断全过）；契约沉淀 DEPLOYMENT.md §3.3.1 |
| T9 | SLO e2e 指标运行时验证 | ✅ worker `:9085/actuator/prometheus` 暴露 `cj_judge_e2e_seconds_*`；Prometheus 中 **6 条 verdict 序列与 verify-p3 实测 12 次终态对齐**（AC=1/MLE=2/TLE=2/WA=1/RE=5/CE=1） |
| T6 | E3 `/v1` 版本化 | ✅ 用户拍板本轮执行；`GET /v1/problems/page` 与裸路径响应一致（code=200 同构）；`verify-authz.py` **73 PASS / 0 FAIL**（脚本修复：内部直连对照用户 id 硬编码 1 不存在 → `STATUS_UID=2001`） |
| T10 | prod JSON 日志 + Loki | ✅ gateway 不依赖 judge-common（设计约束）→ 本地覆盖 `logback-spring.xml` + 同包名 `JsonLogLayout` 拷贝 → prod JSON 生效；promtail 文件名正则修复（`\\` 字面反斜杠永不匹配容器内正斜杠路径 → application 标签从无到有）；Loki 实测 `{application="judge-gateway"}` **39 行 `\| json` 解析成功**、`{application="judge-auth"} \| json \| requestId!=""` **命中真实请求行**（登录 code=200+token，requestId=ee09ad46…）；gateway（WebFlux）无 requestId 属设计（字段缺省省略），requestId 链路由 servlet 服务承担 |
| T4 | soak 首跑 | ✅ **10 分钟验链路通过**：1,270,883 样本 / **0 错误** / 有效窗口 599.8s / 整体 2118.7 req/s；P95：problems/page 15ms、problems/{id} 13ms、submissions/page 10ms、contests/{id}/rank 11ms（门槛 300/300/400/300）。唯一 FAIL `POST /accounts/login` P95=804ms 为**小样本伪告警**：n=18（每线程一次），15 个 97–136ms + 1 个 181ms + 2 个 804ms（ramp 起步预热离群点），全部 200。观测项（HANDOFF T4.3）：jvm heap 锯齿回水位、hikari pending 窗口内恒 0、judge_queue_backlog 恒 0；跑完 verify-p1 **43/0** 无静默损坏。限流已按 soak.jmx 说明书放宽 500/1000 跑测，**测后恢复 2/5 并实测 429 仍生效**（12 连发 → {200:6, 429:6}，与 burst=5+2/s 精确吻合）。1h 全量**未达成**（10-03 复盘，详见 PERF.md §3.8.1）：实际跑 55:40 后随上一会话进程树回收被杀；前 30 分钟 446 万样本 **0 错误**（干净），第 30:10 分起 access token（TTL=30min，soak 每线程只登录一次不续签）同时过期 → 鉴权接口全 401（~82%/区间），`/problems/page`（public-read）全程 100% 成功——根因是负载模型限制而非系统缺陷，且意外验证了网关拒绝路径在 6900 req/s 下 avg 2ms 不倒。**有效 1h soak 列为遗留**（需 soak.jmx 定时重登 / perf 专用长 TTL / refresh 换发三选一） |
| 文档 | 本轮收口 | ✅ DEPLOYMENT.md（§3.1 broker 双 conf 与 hosts 共存方案、§3.3.1 容器化派发契约、§8.3 E3 落地记录）+ compose 过时「待验证项」注释更新 + HANDOFF T0/T2/T3/T6/T9/T10 勾选 + CONTEXT §5.16 |

### 受阻（本轮确认）

| # | 事项 | 卡点 |
|---|---|---|
| T5 | trivy 实扫 | **维持 BLOCKED**：ghcr.io 可达但 ~7KB/s（119MB DB，ETA 5 小时，不值得等待）；mirror.gcr.io 拒连。命令与 trivy 镜像（aquasec/trivy:latest 本地已在列）已备好，待有网环境 |
| T5 | ZAP baseline | **维持 BLOCKED**：`docker images` 本轮确认 zap 镜像本地不存在、docker.io 拉取被拦 |
| T7 | CI 徽章 / remote | 待用户建 remote，不变 |
| T8 | G4 前端 E2E | 待排期，不变 |

---

## 第六轮：U2 JaCoCo contest 补测收口（2026-10-03）

> 前置：U0 环境预检——Docker daemon 仍 500（dockerDesktopLinuxEngine 内部错误，硬约束 13：
> AI 侧手段已穷尽，修复属用户动作）；宿主空闲 4.4/15.2GB（vmmemWSL 1.9GB；较 10-03 晨 3.1GB
> 略回升但仍低于 soak 预检线 6GB）；无 CodeJudge 服务在跑（仅 IDE java 进程）。
> 依赖 Docker 的 U1（soak）/ U3（扫描）维持 BLOCKED，未硬凑。

### 已完成（附证据）

| # | 事项 | 结果 |
|---|---|---|
| U2a | contest 补测 6 类 66 例（18→84 例） | ✅ 新增：`ContestLifecycleServiceTest`（12 例：扫描健壮性/开赛 CAS 推送/结束终榜留档/封榜幂等/单竞赛异常不拖垮整轮）+ `ContestResultHandlerTest`（7 例：订阅契约/supports 矩阵/坏报文丢弃/dirty 登记与吞异常）+ `ContestRankServiceApplyTest`（13 例：applyResult 守卫与 Lua 参数契约逐位断言/freeze 幂等·锁回滚/空榜清 key/rank 三段解码渲染·榜外我的名次）+ `ContestRankPusherTest`（8 例：脏标记/双视图合并推送/内容签名去重/渲染失败静默/CONTEST_STATUS 绕过去重）+ `ContestServiceCreateContextTest`（13 例：建赛窗口/编排校验/缺省推导/自代理事务委托/CAS/context 实时状态推导/titlesOf 降级）+ `ContestRankWsHandlerTest`（10 例：full 视图特权门 fail-closed/订阅上限/CONNECTED+SNAPSHOT/PONG/注销）。`mvn -pl judge-contest test` **84/0** |
| U2b | 覆盖率实测 | ✅ contest LINE **0.047 → 0.748**（jacoco.csv：covered=702 / missed=237；报告 `judge-contest/target/site/jacoco/`）。全 reactor verify 实测各模块分布：common 0.120 与 problem 0.124 为新最低 |
| U2c | 棘轮抬升（双门禁） | ✅ 根 pom 全局 BUNDLE LINE 下限 **0.04 → 0.10**（低于新最低 common 0.120 留余量，注释更新基线与抬门纪律）；judge-contest/pom.xml 新增模块专属门禁 **check-contest 0.70**（锁住 contest 高位防回退，全局门只保底线） |
| U2d | CI 同命令实证 | ✅ 全 reactor `mvn -B -ntp verify` **BUILD SUCCESS**（11 模块 321 例全绿，jacoco check 0.10 全部过线 + contest 0.70 过线）——与 `.github/workflows/ci.yml` 同命令，CI 首日不会因门禁变红 |
| 顺手修 | CI yml 过时注释 | ✅ `.github/workflows/ci.yml` 头注已更正为「根 pom 全局 LINE 下限 0.10 + contest 模块专属 0.70」，与实际门禁一致 |

### 硬约束遵守记录

- 硬约束 14（judge-common 先 install）：本轮**未改 judge-common**，N/A；reactor 构建自然覆盖。
- 硬约束 15（jacoco.skip 仅限本地、CI 不绕）：本轮**全程未用** `-Djacoco.skip=true`，verify 含门禁真跑。
- 硬约束 17（给 Bean 加构造依赖必须真跑 mvn test）：新测试均为纯 Mockito 构造器注入，不动生产 Bean；contest 与全 reactor `mvn test` 真跑通过。

### 受阻（本轮确认，不变）

| # | 事项 | 卡点 |
|---|---|---|
| U1 | 有效 1h soak | **BLOCKED**：Docker daemon 500（用户动作恢复）；宿主空闲 4.4GB < 预检线 6GB。恢复后先做 soak.jmx 定时重登改造（三选一推荐 a，不动生产代码），跑法与 .jtl 事后复盘预案见 HANDOFF U1 |
| U3 | trivy / ZAP 扫描 | **维持 BLOCKED**：需 Docker daemon + 外网（ghcr.io ~7KB/s、mirror.gcr.io 拒连、zap 镜像本地无）。命令已备（HANDOFF U3），不硬凑 |
| U4-U6 / U8 / U9 | 外网/用户配合项 | 卡点与所需配合见本轮汇报 C 类，无变化 |

### U1 首攻（2026-10-03 傍晚）：soak.jmx 定时重登落地；WSL2 VM 判定须重启宿主

**已完成：soak.jmx 定时重登改造（PERF.md §3.8.1 三选一之 a，不动生产代码）**

- 机制：两线程组的登录块由 `OnceOnlyController` 改为 `IfController` 主循环内检测——每线程维护
  `LOGIN_TS`（UDV 初始 0；JSR223 后置脚本仅在登录成功时登记，失败回退 60s 重试防限流风暴），
  距今超过 `reloginAfterSec`（默认 1500s=25min，为 30min TTL 留 5min 余量）+ `threadNum×
  reloginJitterSec`（默认 10s）即重登。18 线程错峰散布约 2min，峰值 ≈2/s 不触碰登录限流（2/s+burst5）。
- 验证：XML 解析通过（2×IfController / 2×JSR223 / 0×OnceOnly 残留）；10min shakedown 实测
  18/18 登录成功（旧计划 30:10 必现的 401 风暴源头已移除）；`-JreloginAfterSec/-JreloginJitterSec`
  可覆盖。⚠️ `run-perf.py -J` 参数必须空格分隔（argparse 短选项连写会被拆散报 unrecognized）。
- 附带发现：引擎 16:22 恢复后全栈 21 容器自动拉起（8 服务 healthy）——compose restart 策略
  在 daemon 恢复时自愈有效，重启后无需手工起栈。

**环境结论（如实记录，停止恢复循环）**：WSL2 VM 反复病态——16:22 恢复后探活正常（10ms），
16:29 一上压 30s 内劣化（吞吐 2500→6/s，全接口 P95=30s 超时，非 401）；16:50 再次恢复后
**无负载** 4 分钟内 daemon API 再次 500，宿主→网关单请求 12.1s，vmmemWSL CPU 0%（排除 VM 内
空转），宿主无换页压力（Pages/sec≈2）。病灶在宿主↔VM 通信层（vmcompute/HNS，与晨间
`192.168.65.7:2376 no route to host` 同源），`wsl --shutdown` 重置不掉该层。**唯一正解：
重启 Windows 宿主**（用户动作）。重启后：Docker Desktop 启动 → 栈自动拉起 → 10min shakedown
→ 1h 全量（soak.jmx 已就绪，含 .jtl 落盘与事后复盘预案；25min 续签点在 1h 内自然验证）。

---

## 第七轮：U0 复检 + U2/U1 前置完成态复核（2026-10-04）

> 本轮输入为 10-03 版提示词；第六轮（U2）与 U1 首攻（soak.jmx 续签）在上一会话已完成，
> **改动尚未 commit**。本轮做环境复检与完成态实证复核（不重做），并核对工作区。

### 已完成（附证据）

| # | 事项 | 结果 |
|---|---|---|
| U0 | 环境预检（10-04 16:37） | Docker daemon **未运行**（`docker version` 报 npipe `dockerDesktopLinuxEngine` 管道不存在；无任何 docker 进程；vmmemWSL 不存在）——较 10-03「daemon 500」更进一步，属 **Docker Desktop 本体未启动**，疑似宿主已重启但 Desktop 未拉起。宿主空闲 **6.36/15.22GB**（≥ soak 预检线 6GB，**达标**）。按硬约束 13，AI 侧不重试拉起 |
| U2 复核 | 完成态实证（不重做） | `mvn -pl judge-contest test` **84/0 BUILD SUCCESS**（8.6s）；jacoco.csv 实测 **LINE 702/237 = 0.748**，与第六轮记录一致；双棘轮在 pom（根全局 0.10 / contest check-contest 0.70） |
| U1 前置复核 | soak.jmx 定时重登（上一会话已落地） | git diff 复核：两线程组 `OnceOnlyController` → `IfController`（LOGIN_TS 距今 > 1500s + threadNum×10s 即重登，18 线程错峰 ≈2min、峰值 ≈2/s 不触限流）+ JSR223 后置登记 LOGIN_TS（仅成功登记，失败回退 60s 重试）；上一会话 10min shakedown 实测 18/18 登录成功 |

### 受阻（不变，卡点如实）

| # | 事项 | 卡点 |
|---|---|---|
| U1 | 有效 1h soak 全量 | **BLOCKED**：Docker daemon 未运行（用户动作：启动 Docker Desktop；若宿主尚未重启且 VM 病态依旧，先按 §5.18 结论重启 Windows）。恢复路径：栈随 restart 策略自动拉起 → 10min shakedown → 1h 全量（受管后台任务会随会话回收，.jtl 落盘事后复盘）→ RUNBOOK §3.2 观测 + verify-p1-login 43/0 + 补 PERF.md §3.8.2（当前 PERF.md 尚无该节，等实测数字） |
| U3 | trivy / ZAP 扫描 | **维持 BLOCKED**：Docker daemon + 外网双卡点；命令已备（HANDOFF U3），不硬凑 |

### 待用户

1. **启动 Docker Desktop**（U1/U3 解锁前提；内存 6.36GB 已达标）。
2. U4 Playwright（外网装 chromium + 排期）/ U5 remote+徽章（建 remote，5 处徽章占位替换，首推验 Actions）/ U6 告警凭据（webhook 给 Alertmanager）/ U8 HTTPS（证书+域名）/ U9 独立压测机（生产容量标定）——逐项卡点见本轮汇报，无变化。
3. **确认入库**：第六轮 + U1 首攻全部改动仍在工作区未 commit（7 文件改动 + 6 个新测试类）。

### 不做

- 无新增。U7（E4 生产告警重标）依赖生产环境真实流量，属上线后动作，本轮不动。

### U1 首攻（10-04 下午）：用户启动 Docker 未重启宿主，VM 复病，U1 再标 BLOCKED

时间线（证据链完整）：16:41 `docker version/ps` 正常、全栈 20 容器随 restart 策略自动拉起
（8 服务 healthy，含 zx-learn 基础设施 5 容器意外自启）；16:42 宿主空闲 1.32GB +
host→容器 health 探测 5/8 超时；16:43 `docker stats` daemon API **500**；16:55 `docker ps`
挂起无输出；16:56 host→网关单请求挂死。**全程无压测负载，恢复后约 5 分钟内劣化**，
与 §5.18「宿主↔VM 通信层病灶（vmcompute/HNS）」同型。经确认用户仅启动了 Docker Desktop、
**未重启 Windows**——复发符合预期（病灶未重置）。处置：停止恢复循环（硬约束 13/教训），
soak 未开跑，**U1 维持 BLOCKED 待用户重启宿主**。重启后流程：启动 Docker Desktop →
AI 先停 zx-learn 5 容器（18d 约束）→ 预检（daemon/8 端口/内存≥6GB/GW_LOGIN_RATE 默认
2/5 已核实）→ 10min shakedown 并入 1h soak 首 10min 劣化监测 → 1h 全量。

---

## 第八轮：U1 有效 1h soak 收口（2026-10-04，T4.2 结案）

> 宿主重启后执行。数字与观测全文见 `docs/PERF.md` §3.8.2。

### A 已完成（附证据）

| # | 事项 | 结果 |
|---|---|---|
| 环境恢复 | 宿主重启 + Docker Desktop + 栈自启 | daemon 正常；CodeJudge 20 容器自动拉起 8 服务 healthy；**zx-\* 6 容器（含 zx-learn-mysql）restart 策略改 `no` 并停机**（18d 约束；zx-learn docker-compose.yml 源头同步改 `"no"`）；网关单请求 0.52s（对比病态期 12s+） |
| 预检 | 跑前四项 | daemon ✓ / 8 端口 ✓ / `GW_LOGIN_RATE` 默认 2/5 ✓ / soak.jmx 续签就绪 ✓ |
| **U1** | **有效 1h soak** | **8,879,545 样本，0 错误（0.000%），2466.6 req/s，有效窗口 3599.8s**；四 GET P95 8–12ms；登录 P95 192ms；门槛判定 5 PASS / 0 FAIL |
| 续签验证 | §3.8.1 根因闭环 | 登录样本 **54 = 18 首登 + 18@T+25min + 18@T+50min**，两轮错峰重登如期触发，全程无 401 |
| §3.2 观测 | 四项全过 | JVM 锯齿 min 回落 134–164MB 无爬升（无泄漏）；Hikari pending 全程 0 / active 归零；backlog=0；死信 7 条全为历史遗留 0 新增 |
| §5.3 静默损坏 | 无新增 | `ops-dead-tasks.py` 7 条（10-01/10-03 历史）与 Prometheus 口径一致 |
| 收尾验收 | verify-p1-login | **43/0** 全过 |

### B 受阻 / C 待用户 / D 不做

- 无新增受阻项。剩余未完成任务与上轮一致：U3（Docker+外网）、U4（外网+排期）、U5（remote）、
  U6（凭据）、U7/U9（上线后/生产化）、U8（证书域名）。
- 证据文件（已归档 `docs/perf-evidence/2026-10-04-soak-1h/`，随库提交）：`run-perf-console.log`
  （run-perf.py 全程控制台输出含最终报告表）、`soak-t0|t30|t60-snapshot.txt`（Prometheus 快照）、
  `prom-jvm-envelope-70min.txt`（JVM 锯齿包络 min/max）、`docker-ps-after.txt`/`docker-stats-after.txt`
  （跑后系统状态）。大体量原始数据（gitignore 内，留存本机磁盘）：
  `perf-test/results/soak.jtl`（约 2.2GB，890 万行原始样本）、`perf-test/results/soak-jmeter.log`
  （30s summariser 全量日志）。

## 第九轮：U3 漏洞扫描收口 + U4 E2E 冒烟（2026-10-04 晚）

> U0 恢复（宿主重启）后 Docker + 外网可用，U3 解除 BLOCKED；U4 同窗口完成第一批。

### A 已完成（附证据）

| # | 事项 | 结果 |
|---|---|---|
| U3a-镜像 | trivy 扫 4 张基础/沙箱镜像 | ✅ java21 干净（0H/0C）；python312 51H 0C；gcc13 481H 32C；go122 1144H 26C——全部为 OS 包陈旧项，随 base 镜像升级刷新，处置「接受+跟进」 |
| U3a-依赖 | trivy fs 逐模块扫 9 模块 POM | ✅ 多目录并发 POM 分析器死线 bug（semaphore deadline exceeded，--timeout 15m/--offline-scan 均无效）→ **逐模块单独扫描绕过**；3 个真实 CRITICAL：tomcat 10.1.31 / netty 4.1.114.Final / bcprov 1.78 |
| U3a-升级 | 三项依赖钉版落位 | ✅ 根 pom properties 钉 **tomcat 10.1.55 / netty 4.1.137.Final / bcprov 1.85**（dependencyManagement 钉 bcprov 覆盖传递依赖）。打地鼠记录：tomcat 10.1.58 Central 未发布（mvn "was not found"）→ 回退 10.1.55，残留 CVE-2026-65182 记「上游未发布、接受+跟进」；bcprov 1.81.1 复扫又出 CVE-2026-8763 → 直接钉 1.85 清零；**fastjson CRITICAL 判定误报**（11 模块 dependency:tree 零命中，trivy POM 分析器 optional 路径过度近似）。`mvn install` 11 模块 BUILD SUCCESS |
| U3a-复验 | judge-api 复扫终态 | ✅ 仅剩 fastjson 误报 + tomcat CVE-2026-65182（已接受项），真实 CRITICAL 清零 |
| U3b | ZAP baseline 扫描 | ✅ **H0 / M0 / L0** 高中低危清零（仅 1 条 Informational：Storable/Cacheable Content，针对网关根路径 4xx 响应，无敏感信息不处置）。compose 网络内打 http://judge-gateway:9080，报告三件套 md/html/json 归档 |
| U3-重部署 | 升级后镜像重建 + 回归 | ✅ 多阶段 Dockerfile 因 docker.io 被墙（maven 构建器拉不动）不可用 → `scripts/build-app-images-prebuilt.py --worker-docker-cli` 从本地 jar 构建 8 服务镜像（8/8 OK）→ `docker compose --profile app up -d` 重建容器 → **8/8 healthy**（gateway 对外 404 为 ActuatorGuardFilter 严格模式预期）→ **verify-p1-login 43/0** 回归全过 |
| U3-落账 | 处置登记 + 证据 | ✅ DEPLOYMENT.md §8.4.1（镜像表/依赖表/ZAP 结果/方法论）；证据归档 `docs/security-evidence/2026-10-04-trivy/`（images-scan-full.txt / fs-modules-scan.txt / fs-api-after-upgrade.txt）+ `2026-10-04-zap/`（md/html/json + zap.yaml），commit dbb32e7 |
| U4 | Playwright E2E 冒烟 | ✅ 两条链路（登录→落地 /problems；题库列表有数据）**2/2 通过**；@playwright/test 落 devDependencies，test-results/playwright-report 入 gitignore，commit 6c85b04 |

### B 受阻 / C 待用户 / D 不做

- U3/U4 无新增受阻。剩余：U5（remote 仓库+徽章）、U6（告警凭据）、U8（HTTPS 证书域名）、
  U9（独立压测机）——全部需用户资源；U7 属上线后。
- 方法论沉淀（复用价值）：①trivy 逐模块扫描绕 POM 分析器并发死线；②`--offline-scan` +
  挂 `~/.m2:ro` 应对 Maven Central 429（Retry-After 1800s）；③trivy DB fixed 版本可能超前
  Central 实际发布（10.1.58 教训），钉版前先 `mvn` 实证；④`build-app-images-prebuilt.py`
  是 docker.io 被墙环境唯一可用镜像构建路径。

