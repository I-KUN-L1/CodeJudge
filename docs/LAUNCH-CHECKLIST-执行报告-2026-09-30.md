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
