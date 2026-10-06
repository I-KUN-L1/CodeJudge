# CodeJudge 上线收尾执行提示词（2026-10-05 版）

> 接替 `HANDOFF-PROMPT-2026-10-04.md`（已封存，其 §七 测试数据登记册仍然有效）。
> 本文件自包含：新对话只需读本文 + 本文件第六节指向的两份 runbook 即可开工。

---

## 一、项目一句话与当前状态快照（2026-10-05 晚）

CodeJudge：Java 21 微服务（8 服务 + 网关）+ Vue3 前端的分布式 OJ，Docker Compose 全栈
（16 运行容器）。**当前健康**：8 服务 healthy、TZ 已统一 CST、tomcat 10.1.59、E2E 5/5、
verify-p1-login 43/0、trivy 真实 CRITICAL 清零。**第十二轮（10-05 晚）：U5 已收口（remote=
I-KUN-L1/CodeJudge + CI 首跑绿）、U6 已收口（email 告警通道真实终验，收件确认）、
U8 已关闭（用户决策：内网部署不设域名，范围取消）、U9 已关闭（用户决策方案④：无第二台电脑，
决策降级带触发条件）；**`.env` 生产态约定已执行**（密钥全量轮换+开关落地，43/0 + E2E 5/5）；
**B2 已关闭（10-06）：LLM 切换 DeepSeek-V4.1-Flash（deepseek-flash），真实 AI 正文核验通过**；
**上线前队列：空。**

## 二、已收口（勿重做，证据齐备）

| 项 | 结果 | 证据/提交 |
|---|---|---|
| U1–U3 静态验收 / 1h soak / 漏扫 | 42/0 · 8.88M 样本 0 错误 · ZAP H0/M0/L0 · trivy 真实 CRITICAL 全清零 | PERF.md §3.8 · security-evidence/2026-10-04-*（第 1–9 轮） |
| U4/U4b Playwright E2E | **5/5**（登录/题库/提交判题 AC/判题详情/AI 点评链路/榜单 WS 联动），fixture `e2e/setup-contest.mjs` | e2e-evidence/2026-10-04-smoke · 2026-10-05-u4b-chains（64c1cb8） |
| tomcat CVE-2026-65182 | 结案：10.1.55→10.1.59 全链回归 | security-evidence/2026-10-05-tomcat-1059（a69ca72） |
| **TZ 时钟缺陷** | **已修复**：8 服务容器加 `TZ: Asia/Shanghai`，时钟/调度全 CST；fixture 以本地时间发窗口（兼作金丝雀） | e2e-evidence/2026-10-05-tz-fix（276fb53） |
| **U6/U8/U5/U9 prep** | 告警链路回环实证（真实+合成告警达本地 sink）；TLS 终结 rehearsal 200（9443 自签，`--profile tls`）；推送安全审计通过；对标口径落档 | security-evidence/2026-10-05-u6-u8-prep（16f49e3） |
| **U5 GitHub remote + CI** | **已收口（10-05 晚）**：凭据管理器存量凭据 + REST API 代建（解除无 gh 卡点）→ I-KUN-L1/CodeJudge（Private）→ push b3eef63（徽章 1 处+注释）→ **Actions 首跑 run#1 success** | security-evidence/2026-10-05-u5-github |
| 测试数据登记册 | 第十~十一轮 23 用例：PASS 19 / FAIL 0 / BLOCKED 4（全外部） | **本文件前作（10-04 版）§七** |

## 三、剩余任务队列（全部卡用户资源，资源到位即执行）

**统一 runbook：`docs/LAUNCH-USER-ACTIONS.md`**（每项「卡点/已就位/一步式操作」）：

1. ~~**U5**~~ **已收口（10-05 晚）**：REST API 代建（凭据管理器存量凭据通路，非 gh）→
   I-KUN-L1/CodeJudge（Private）+ push + 徽章 + **CI 首跑绿**（run 37299478009）；登记册 TC-U5-02/03 PASS。
2. ~~**U6**~~ **已收口（10-05 晚）**：email 通道（smtp.qq.com:587 STARTTLS，零中间件）→
   测试+真实告警投递成功（`notifications_total{email}=1`）→ 收件人确认收到；AM 保持 email 双路由生产位；
   535 排障根因=授权码与发件账号不同号。证据 `security-evidence/2026-10-05-u6-email-channel/`。
3. ~~**U8**~~ **已关闭（10-05 晚，用户决策）**：系统内网部署**不设域名** → 真域名 HTTPS 范围取消
   （登记册 TC-U8-02「关闭」非 BLOCKED）；TLS rehearsal 资产留库，日后内网 HTTPS 走内网 CA 路线复用。
4. ~~**U9**~~ **已关闭（10-05 晚，用户决策方案④）**：无第二台电脑 → 决策降级——同机下界
   （2466.6 req/s / 0 错误 / P95 8–12ms）距内网真实负载 ≥2 个数量级，生产容量标定推迟；
   **重测触发条件四条**（形态变更 / 并发≥200 / P95 劣化 10× / 独立机到位）见 CONTEXT §5.29；
   throughput.jmx/soak.jmx/账号池/对标口径就绪，可随时执行。
5. **B2 复核**：LLM（bigmodel）429 复测仍 429（上游配额）；配额恢复/换 Key 后人工核对真实 AI 文本。
6. **U7（上线后）**：`scripts/recalibrate-alerts.py` 按真实流量重标告警（两道守卫已有）。

## 四、硬约束精选（踩坑实录，违者返工）

1. **时钟**：TZ 修复已落位（compose 8 服务 `TZ: Asia/Shanghai`）；E2E fixture 发**宿主本地时间**
   窗口，若 T3 报「竞赛尚未开始」= 服务端时钟回退的金丝雀告警，先查 TZ 再查别的。
2. **沙箱**：宿主/daemon 重启后判题前必须 `docker start codejudge-sandbox-init`
   （/cj-sandbox 一次性初始化，不会自启），否则判题 SE。
3. **构建**：mvn 不在 PATH（用 wrapper dist）；改 judge-common 先 install；`jacoco.skip` 仅本地；
   docker.io 被墙，镜像唯一路径 `build-app-images-prebuilt.py --worker-docker-cli`；重部署后必跑 verify-p1-login。
4. **trivy**：逐模块扫；**必挂 `~/.m2:ro` 否则 parent pom 解析失败假干净**；DB fixed 版本可超前 Central，钉版前 mvn 实证。
5. **本机路径**：npm 用 `D:\git,nodejs,notepad--\node\npm.cmd`（system32 空壳截胡）；python 用
   Python311 全路径 + `PYTHONPYCACHEPREFIX`（后台子进程也要）；openssl 经 PowerShell 需
   `MSYS_NO_PATHCONV=1` + `-subj "/CN=..."`。
6. **PowerShell**：无 `&&`（用 `;`）；`Set-Content -Encoding utf8` 带 BOM（AM/JSON 接口会 400，用
   ASCII）；原生命令进管道会挂（重定向优先）；git 提交信息单行 `-m`；`-J` 参数空格分隔。
7. **资源**：CodeJudge 与 zx-learn 不同机同跑；9 容器同启曾压垮 daemon（分批 up -d）；内存紧张时
   先查 StarRail/浏览器占用，重负载动作最小化、逐个重建。
8. **安全**：生产 `.env` 必须 `CJ_DOC_WHITELIST_ENABLED=false` + `CJ_ACTUATOR_TOKEN`（轮换同步
   Prometheus yml 两处）；`/problems/{id}` 永不入白名单；非管理员用户信息脱敏。
   **✅ 已执行（10-05 晚，第十二轮）**：密钥全量轮换（MySQL/PG/Grafana/admin/JWT/Redis/MinIO/actuator）
   + 两开关落地 + Prometheus 同步，回归 43/0 + E2E 5/5；证据 `2026-10-05-env-prod-hardening/`。
   admin 密码=CJ_ADMIN_INIT_PASSWORD 当前值。另：改 .env 后须显式 force-recreate 服务清单
   （env_file 内容变化不触发重建）；改行值正则用 `^KEY=.*$`（CRLF 卡 `=true$`）。
9. **TLS/告警 prep 资产**：`docker-compose.tls.yml`（`--profile tls`，9443，与默认栈零冲突）；
   AM 注入测试告警模板 `docs/security-evidence/2026-10-05-u6-u8-prep/test-alert-payload.json`。

## 五、汇报与文档同步义务

按 **A/B/C/D** 分类：A 已完成（附证据与提交号）/ B 受阻（附精确卡点）/ C 待用户（附所需资源）/
D 不做（附理由）。**假绿比 FAIL 危险**——rehearsal/回环验证必须如实标注，不得冒充终验。
每轮同步：执行报告追加轮次表（`docs/LAUNCH-CHECKLIST-执行报告-2026-09-30.md`，当前至第十一轮）+
`docs/CONTEXT.md` 追加小节（当前至 §5.25）+ 本文件状态行 + project memory。
测试执行结果按 10-04 版 §七 登记册格式（用例 ID/时间/输入/预期/实际/状态/备注）续登。

---

## 六、可直接粘贴的新对话提示词

```text
请先完整阅读 docs/HANDOFF-PROMPT-2026-10-05.md（CodeJudge 上线收尾执行提示词，2026-10-05 版）
与 docs/LAUNCH-USER-ACTIONS.md（四项待用户任务 runbook），理解已收口进度（勿重做）与剩余队列。

我已提供以下外部资源（见下）。请按 runbook 对应节执行，并遵守：
1. 严格按 LAUNCH-USER-ACTIONS.md 的「一步式操作」执行，改动最小化；全程对照其「已就位」清单，
   prep 资产（TLS 配置/告警模板/审计结论）直接复用，不重建。
2. 验收口径 = 真实终验（真 CI 绿 / 真群收到消息 / 真域名 HTTPS / 独立机对标基线），
   不得用本地回环或 rehearsal 冒充；阻塞项标 BLOCKED，假绿比 FAIL 危险。
3. 每完成一项：证据归档 docs/{e2e,security,perf}-evidence/<日期>-<主题>/，测试结果按
   HANDOFF 10-04 版 §七 登记册格式续登（用例 ID/时间/环境/输入/预期/实际/状态/备注），
   更新 LAUNCH-USER-ACTIONS.md 勾选状态。
4. 汇报按 A/B/C/D 分类，同步执行报告（新开「第十二轮」）与 CONTEXT.md（§5.26+）。

本次提供的资源：<在此填写，例如：GitHub 仓库 URL=<…> / 告警通道=email，SMTP 凭据=<…> /
域名=<…> 证书已放 <路径> / 压测机=<IP，已放通 9080>；多项可一次给，按 U5→U6→U8→U9 顺序执行。
```
