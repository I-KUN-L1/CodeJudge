# CodeJudge 上线收尾执行提示词（2026-10-04 版）

> 接替 `docs/HANDOFF-PROMPT-2026-09-30.md`（该版 U0–U4 第一批已全部结案，勿按旧版开新会话）。
> 权威进度：`docs/CONTEXT.md`（§5.13–§5.22）、执行报告 `docs/LAUNCH-CHECKLIST-执行报告-2026-09-30.md`
> （第二～第九轮）、`docs/PERF.md` §3.8、`docs/DEPLOYMENT.md` §8.4.1。

---

## 一、任务背景（一句话版）

CodeJudge（分布式在线编程评测平台，8 后端微服务 + Vue 前端，仓库 `D:\1\CodeJudge`）：
静态交付全绿（`launch-verify.sh` 42/0）、容器化全栈实测跑通、**1h soak 全绿
（8,879,545 样本 0 错误 / 2466.6 req/s / 四 GET P95 8–12ms）**、漏洞扫描收口
（3 个真实 CRITICAL 依赖升级落位 + ZAP H0/M0/L0）、E2E 冒烟 2/2。
**2026-10-04 晚现状：宿主已重启，全栈 20 容器 healthy，8 服务镜像已含升级后依赖重部署，
verify-p1-login 43/0。** 剩余工作全部为「渐进补测试 / 用户资源依赖 / 上线后动作」。
**2026-10-05 更新：U4b 已收口（5/5，见执行报告第十轮）、tomcat 遗留跟进已结案（10.1.59）；
剩余 U5/U6/U8/U9 全部卡用户资源。**
**2026-10-05 下午再更新（执行报告第十一轮）：TZ 时钟缺陷已真修（8 服务加 TZ + 回归 5/5 + 43/0，
fixture 回切本地时间）；LLM 429 复测仍 429（上游配额）；U6/U8 链路回环/rehearsal 验证通过、
U5 推送审计通过、U9 runbook 就绪——四项一步式操作见 `docs/LAUNCH-USER-ACTIONS.md`。**
**2026-10-05 晚：新对话统一使用接替版 `docs/HANDOFF-PROMPT-2026-10-05.md`；本文件封存，
仅 §七 测试数据登记册（第十~十一轮 23 用例）持续作为总结报告数据源维护。**

## 二、已完成进度（勿重做）

| 任务 | 结果 | 证据/提交 |
|---|---|---|
| U0 环境 | 宿主重启后 daemon 无复发；zx-\* 6 容器自启永久关闭（compose 源头 + 运行时策略双落位） | CONTEXT §5.20/§5.21 |
| U1 1h soak（T4.2 结案） | 8,879,545 样本 0 错误 2466.6 req/s；soak.jmx 定时重登实测 54=18+18+18 无 401；§3.2 四项观测过 | PERF.md §3.8.1/§3.8.2；perf-evidence/2026-10-04-soak-1h/（becf8be） |
| U2 JaCoCo | contest 补测 84 例 LINE 0.047→0.748；双棘轮 全局 0.10 + contest 0.70；全 reactor verify 11 模块绿 | 执行报告「第六轮」/ CONTEXT §5.17 |
| U3 trivy | 镜像 4 张 + fs 逐模块 9 模块；**tomcat 10.1.55 / netty 4.1.137.Final / bcprov 1.85** 钉版清零；fastjson 判误报；镜像重建重部署 8/8 healthy + verify-p1-login 43/0 | DEPLOYMENT.md §8.4.1；security-evidence/2026-10-04-{trivy,redeploy}/（dbb32e7） |
| U3 ZAP | baseline H0/M0/L0（仅 1 条 Info 级缓存提示，不处置） | security-evidence/2026-10-04-zap/（dbb32e7） |
| U4 第一批 | Playwright E2E 冒烟 2 条链路 2/2 通过（登录→落地；题库列表） | e2e-evidence/2026-10-04-smoke/（6c85b04） |
| **U4b（10-05 ✅）** | E2E 剩余链路 **5/5**：提交判题 AC（轮询）/ 判题详情 / AI 点评链路（SSE）/ 榜单 WS 联动；新增 `e2e/setup-contest.mjs` fixture；3 项发现（TZ 缺陷 / sandbox-init 重启规约 / LLM 429 降级） | e2e-evidence/2026-10-05-u4b-chains/；执行报告第十轮；CONTEXT §5.23 |
| **tomcat 跟进（10-05 ✅）** | CVE-2026-65182 **结案**：Central 实际发布 10.1.59（10.1.58 跳过）→ pom 10.1.55→10.1.59 → 8 镜像重建重部署 8/8 healthy → trivy 复扫零命中 → verify-p1-login 43/0 → E2E 回归 5/5 | security-evidence/2026-10-05-tomcat-1059/；DEPLOYMENT.md §8.4.1；CONTEXT §5.24 |

## 三、未完成任务队列（按优先级与解锁条件）

### U4b. Playwright E2E 剩余链路（✅ 2026-10-05 已收口，勿重做）
- **结果**：5/5 通过（冒烟 2 + 提交判题/AI 点评/榜单联动 3）；新增 `judge-web/e2e/setup-contest.mjs`
  前置 fixture。剩余链路维护见 `judge-web/e2e/smoke.spec.js` 与
  `docs/e2e-evidence/2026-10-05-u4b-chains/README.md`（含 TZ 绕过说明）。
- **目标**：补齐 选题 → 编辑 → 提交 → 看判题结果 → 查榜单 → AI 点评。
- **做法**：扩展 `judge-web/e2e/smoke.spec.js`（或新增分文件）；沿用 serial 模式 +
  storageState 复用（登录限流 2 req/s burst 5，严禁循环反复登录）；提交用种子题目 +
  学员账号 13900000001/123456；判题结果断言走轮询页面状态而非固定 sleep。
- **验收**：全部链路 `npm run test:e2e` 通过；日志归档 `docs/e2e-evidence/<日期>-<主题>/`；
  执行报告追加轮次 + CONTEXT 追加小节。
- **注意**：必须在 `judge-web/` 目录内跑（仓库根会误扫 vitest）；Chromium 与本地
  @playwright/test 版本必须匹配（`npm exec playwright install chromium` 用本地版本补装）。

### U5. remote 仓库 / README 徽章 / 首次 Actions（需用户：创建远程仓库）
1. 用户建 remote 后：`git remote add origin <url>` 并推送。
2. 替换 README 徽章占位符 `YOUR_GITHUB_ORG/CodeJudge`（共 5 处：CI/Java/Spring Boot/License/Coverage）。
3. 首推后确认 GitHub Actions 真跑 `.github/workflows/ci.yml`（本地从未跑过 Actions；
   CI 门禁 = 全局 LINE 0.10 + contest 0.70，本地同命令已实证 BUILD SUCCESS，首日不会变红）。

### U6. F1 告警通道（需用户：提供凭据）
- Slack/钉钉/邮件 webhook 凭据 → 配置进 Alertmanager（`deploy/monitoring/`）→
  配一条测试告警验证触达。
- **10-05 prep**：Prometheus→AM→webhook 投递链路已回环实证（真实+合成告警均达本地 sink）；
  凭据到位=改 .env + recreate + 注入测试告警三步，见 `docs/LAUNCH-USER-ACTIONS.md`。

### U8. HTTPS（需用户：域名 + 证书）
- 网关 9080 前置 TLS 终结（或网关自身 SSL）；证书/域名到位前不硬凑。
- **10-05 prep**：TLS 终结 rehearsal 通过（`docker-compose.tls.yml` + `deploy/tls/`，9443，
  自签证书，`--profile tls` 隔离）；换真证书=两行配置，见 `docs/LAUNCH-USER-ACTIONS.md`。

### U9. 独立压测机（需用户：第二台机器）
- 1h soak 目前与本机共宿主；独立压测机到位后复跑对标（soak.jmx 已含定时重登，可直接搬）。
- 10-05 runbook：`docs/LAUNCH-USER-ACTIONS.md` U9 节（对标口径已写死）。

### U7. E4 生产告警重标（上线后动作）
- 全部告警阈值仅适用本地基线；上线后按真实流量用 `scripts/recalibrate-alerts.py` 重标
  （脚本已有两道守卫：样本稀疏不计入、无提交流量不改阈值）。

### 遗留跟进：tomcat CVE-2026-65182（✅ 2026-10-05 已结案，勿重做）
- **结果**：Central 实际发布 10.1.59（10.1.58 被官方跳过）→ pom 升版 → 复扫零命中 →
  8 镜像重部署 → verify-p1-login 43/0 → E2E 回归 5/5。证据
  `docs/security-evidence/2026-10-05-tomcat-1059/`；登记见 DEPLOYMENT.md §8.4.1。

## 四、硬约束精选（都是踩过的坑，全量见旧版 HANDOFF「硬约束」节）

1. **构建**：改 judge-common 先 `mvn install`；`jacoco.skip` 仅限本地、CI 禁用；给既有 Bean
   加构造依赖必须真跑 `mvn test`；`mvn` 不在 PATH，用 wrapper dist
   `C:\Users\20670\.m2\wrapper\dists\apache-maven-3.9.16-bin\...\bin\mvn.cmd`。
2. **镜像**：docker.io 被墙，多阶段 Dockerfile 不可用；唯一构建路径
   `python scripts/build-app-images-prebuilt.py --worker-docker-cli`（本地 jar → 运行时镜像），
   然后 `docker compose --profile app up -d`；重部署后必跑 verify-p1-login。
3. **trivy**：fs 多目录并发有 POM 分析器死线 bug → 逐模块扫；DB 用
   `--db-repository ghcr.io/aquasecurity/trivy-db`；Central 429 后 IP 封 30min →
   `--offline-scan` + 挂 `~/.m2:ro`；DB fixed 版本可能超前 Central，钉版前先 mvn 实证。
4. **环境**：`python` 用 `C:\Users\20670\AppData\Local\Programs\Python\Python311\python.exe`
   且设 `PYTHONPYCACHEPREFIX=$env:TEMP\pycache`；detached 服务进程启动必须关沙箱；
   后台任务句柄不能 TaskOutput 轮询 → `Start-Sleep ≤570s` 合并探针；会话结束杀后台任务
   （长跑须有落盘事后复盘预案）；PowerShell 原生命令进管道会挂（mvn/npm 用纯重定向），
   git 提交信息用单行 `-m`；`run-perf.py -J` 参数必须空格分隔。
5. **安全**：生产 `.env` 必须 `CJ_DOC_WHITELIST_ENABLED=false` + 设 `CJ_ACTUATOR_TOKEN`
   （轮换同步 Prometheus yml，两处）；`/problems/{id}` 永不加入网关白名单；
   用户信息非管理员脱敏。
6. **资源**：CodeJudge 全栈与 zx-learn 不同机同跑（zx-learn 自启已永久关闭）；9 容器同启
   曾压垮 daemon（分批 up -d）；WSL2 VM 无负载复病 = 唯一正解重启 Windows，停止恢复循环。

## 五、汇报格式

按 **A/B/C/D** 分类：A 已完成（附证据与提交号）/ B 受阻（附精确卡点）/ C 待用户（附所需资源）/
D 不做（附理由）。阻塞项标注 BLOCKED 不许假绿（**假绿比 FAIL 危险**）。
每轮同步：执行报告追加轮次表 + `docs/CONTEXT.md` 追加小节 + 本文件状态更新 + project memory。

---

## 六、可直接粘贴的新对话提示词

```text
请先完整阅读 docs/HANDOFF-PROMPT-2026-10-04.md（CodeJudge 上线收尾执行提示词，2026-10-04 版），
理解任务背景、已收口进度（勿重做）与未完成任务队列，然后按以下顺序执行：

1. U0 环境预检：docker version + 宿主内存余量 + 容器健康状态，报告现状；异常则先按
   文档「硬约束精选」第 6 条处置，不许硬凑。
2. 立即可做：U4b Playwright E2E 剩余链路（选题→编辑→提交→判题结果→榜单→AI 点评）。
   严格复用 storageState 避免登录限流；跑法必须在 judge-web/ 目录内；完成后日志归档
   docs/e2e-evidence/<日期>-<主题>/ 并提交。
3. 无外部资源可做的任务结束后，逐项输出 U5/U6/U8/U9 的精确卡点与所需用户配合
   （remote 仓库 / 告警 webhook 凭据 / 域名证书 / 独立压测机），不得跳过验收假绿。
4. 遗留跟进检查（仅当有网）：查询 Maven Central tomcat-embed-core 是否已发布 10.1.58+；
   若已发布则按文档「遗留跟进」节流程执行升版→复扫→重部署→回归。

汇报按 A/B/C/D 分类（已做附证据 / 受阻附卡点 / 待用户 / 不做附理由），
同步更新执行报告（新开「第十轮」）与 CONTEXT.md（追加 §5.23+），结束后给上线提醒总结。
```

## 七、测试数据登记册（第十~十一轮，2026-10-05，供总结报告引用）

> 结构化登记最近两轮全部测试执行：用例 ID / 时间 / 输入 / 预期 / 实际 / 状态 / 备注。
> **测试环境基线（下表所有用例共享，除非行内特别标注）**：Windows 宿主（CST, Asia/Shanghai）；
> Docker Desktop daemon 29.7.2（WSL2 后端）；16 运行容器（8 Java 服务全 healthy + web + 基础设施
> mysql/redis/pg/rocketmq + 监控栈）；8 服务镜像 = tomcat 10.1.59（10-05 重建）；
> **TZ=Asia/Shanghai（10-05 起，8 服务容器与宿主同钟）**；网关 9080 / 前端 5174；
> E2E = Playwright serial + storageState 单次登录，`cd judge-web && npm run test:e2e`。
> 状态口径：PASS（预期=实际）/ FAIL（不符）/ **BLOCKED（外部资源缺失，非代码缺陷）**。

| 用例 ID | 时间(CST) | 输入参数 | 预期结果 | 实际结果 | 状态 | 备注 / 证据 |
|---|---|---|---|---|---|---|
| TC-U0-01 环境预检 | 10-05 午后 | —（探测 docker/mem/health） | daemon 可用、8 服务 healthy | daemon 29.7.2、8/8 healthy、宿主余 7GB | PASS | 依据：第十轮 U0 行 |
| TC-U4B-T1 登录冒烟 | 10-05 午前 | 13900000001/123456 → web 5174 | 登录落地 /problems | 落地成功 997ms | PASS | playwright-u4b.log（E2E_CONTEST_ID=2107023093806194689） |
| TC-U4B-T2 题库渲染 | 10-05 午前 | GET /problems | 列表有数据 | 渲染 781ms | PASS | 同上 |
| TC-U4B-T3 提交判题链路 | 10-05 午前 | contestId=2107…4689 + problemId=4001 + Java long 版 A+B | 轮询到「通过」→详情用例≥2 行 | AC + 2 用例表 1.6s | PASS | WS 推送+REST 兜底，无固定 sleep |
| TC-U4B-T4 AI 点评链路 | 10-05 午前 | 判题详情页 → 生成 AI 点评 | SSE 流式出正文+完成态 | 渲染完成 1.6s | PASS | **口径=链路可用**；上游 429 走设计内降级模板（见 TC-B2-01） |
| TC-U4B-T5 榜单联动 | 10-05 午前 | /ws/contests/{id}/rank SNAPSHOT | 实时榜出现「演示学员一·过 1 题」 | 首行可见 689ms | PASS | AC 真实计入 Redis 榜 |
| TC-TOM-01 Central 发布确认 | 10-05 午前 | GET maven-metadata tomcat-embed-core | 出现 10.1.58+ | 10.1.59（58 被官方跳过） | PASS | central-metadata-excerpt.txt |
| TC-TOM-02 升版+产物核验 | 10-05 午前 | pom 10.1.55→10.1.59 + mvn install | BUILD SUCCESS，6 servlet 服务 embed-core=10.1.59 | 符合预期 | PASS | judge-ai/gateway 仅 embed-el（无 core，符合架构） |
| TC-TOM-03 镜像重建重部署 | 10-05 午前 | build-app-images-prebuilt.py --worker-docker-cli + compose up | 8 镜像构建、8/8 healthy | 符合预期 | PASS | images-build.log |
| TC-TOM-04 trivy 复扫 | 10-05 午前 | fs 扫 judge-user（挂 ~/.m2:ro） | tomcat CVE-2026-65182 清除 | tomcat 零命中 | PASS | 不挂 m2 会假干净（0 findings 假象） |
| TC-TOM-05 p1 回归 | 10-05 午前 | verify-p1-login.py | 43/0 | 43 通过 0 失败 | PASS | verify-p1-login-43-0.txt |
| TC-TOM-06 E2E 升版回归 | 10-05 午前 | E2E 全量（旧 fixture UTC 窗口） | 5/5 | 5 passed 21.4s | PASS | e2e-post-upgrade.log |
| TC-TZ-01 容器时钟 | 10-05 17:06 | docker exec date ×3 服务 | 显示 CST 且与宿主一致 | 17:06:48 CST = 宿主 | PASS | 修复前为 UTC（差 8h） |
| TC-TZ-02 生命周期调度 | 10-05 17:06 | judge-contest 日志 | 时间戳 CST、旧 UTC 赛正确终局 | 17:06:11 CST 判已结束+FINAL 快照 | PASS | 迁移语义：存库 UTC 墙钟按 CST 提前 8h 过期（可丢弃测试资产） |
| TC-TZ-03 E2E CST 金丝雀 | 10-05 17:07 | fixture 改本地时间窗（contestId=2107034953829392385） | 5/5（T3 若时钟回退则报「尚未开始」） | 5 passed 9.7s | PASS | playwright-tz-fix-regression.log |
| TC-TZ-04 p1 回归 | 10-05 17:09 | verify-p1-login.py | 43/0 | 43/0 | PASS | tz-fix 目录 |
| TC-B2-01 LLM 上游复测 | 10-05 17:08 | chat/completions + embeddings（bigmodel） | 非 429（配额恢复） | 双通道仍 429 | **BLOCKED** | 上游配额问题，非本地缺陷；降级路径工作正常；judge-ai-429-recheck-2026-10-05.log |
| TC-U6-01 告警回环（真实） | 10-05 17:18 | Prometheus 规则 JudgeDeadTasksPresent（8 死信） | 投递到 webhook sink | 17:18:36 sink HTTP 200 收 1745B | PASS | 全链=Prometheus→AM(critical 路由)→webhook |
| TC-U6-02 告警回环（合成） | 10-05 17:21 | POST /api/v2/alerts U6PipelineTest（critical） | POST 200→~10s 后投递 | 200 + sink 收完整 AM JSON | PASS | 坑：PS5.1 utf8 带 BOM 被 AM 400，须 ASCII |
| TC-U6-03 AM 路由还原 | 10-05 17:24 | 无 shell 覆盖 recreate | 默认 receiver=null | rendered yml `receiver: 'null'` | PASS | 验证后恢复零残留 |
| TC-U8-01 TLS rehearsal | 10-05 17:20 | curl -sk https://127.0.0.1:9443/problems/page | 200 真实 JSON | 200（nginx/1.31.6 → 网关） | PASS | 自签证书 CN=codejudge.local；tls-handshake-check.txt |
| TC-U8-02 真域名 HTTPS | — | 用户域名+CA 证书 | 终验 | 未执行 | **BLOCKED** | prep 已就绪：换证书=两行配置（LAUNCH-USER-ACTIONS.md U8） |
| TC-U5-01 推送安全审计 | 10-05 午后 | git ls-files 全量 | 无 secrets 入库、无 >5MB | 符合预期 | PASS | .env 未跟踪；命中 3 项为审计文档/脚本本体 |
| TC-U5-02 代建远程仓库 | 10-05 晚 | REST POST /user/repos（凭据管理器存量凭据，非 gh） | 仓库创建+推送 | I-KUN-L1/CodeJudge Private 建成 + push master 成功（b3eef63） | PASS | 卡点解除：凭据管理器存有 GitHub 凭据，API 代建替代 gh CLI；证据 2026-10-05-u5-github/ |
| TC-U5-03 CI 首跑确认 | 10-05 晚 | push 后轮询 Actions API | run#1 真跑且绿（首日不红） | run#1 completed/**success**（Frontend 55s + Backend 3m32s 双 job 绿） | PASS | run 37299478009；徽章替换实测仅 1 处 CI 徽章+注释（runbook 记 5 处系静态徽章误计） |
| TC-U9-01 独立压测对标 | — | 第二台机器 1h soak | 对标 §3.8.2 基线 | 未执行 | **BLOCKED** | soak.jmx 就绪（含定时重登）；对标口径已写入 runbook U9 |
| TC-U6-04 真实 email 通道终验 | 10-05 晚 | QQ SMTP 587/STARTTLS + 注入 U6PipelineTest | 真邮箱收到告警 | notifications_total{email}=**1**（成功计数）零错误 + 收件人确认收到 | PASS | 排障：12+ 重试持续 535 → 根因=授权码属 2067063203@qq.com 而发件账号误填 10086 → 切换后一次通过；证据 2026-10-05-u6-email-channel/ |

**登记册统计**：PASS 22 / FAIL 0 / BLOCKED 3（全部外部资源：上游 LLM 配额 ×1、用户资源 ×2）。
历史轮次（1–9）测试数据已在各自证据目录 + 执行报告对应轮次表格中登记，不在此重复。
