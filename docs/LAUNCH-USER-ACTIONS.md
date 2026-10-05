# 上线待用户动作 Runbook（U5 / U6 / U8 / U9）

> 生成：2026-10-05（执行报告第十一轮）。四个任务全部卡**用户外部资源**，
> 本文档记录「已就位的准备 + 资源到位后的一步式操作」。不做任何假绿验收。

---

## U5. GitHub remote 仓库 + 徽章 + 首次 CI

> **✅ 已完成（2026-10-05 晚，真实终验）**：凭据管理器存量凭据 + REST API 代建（解除「无 gh CLI」卡点）
> → `I-KUN-L1/CodeJudge`（Private）建成 → push master（b3eef63，徽章替换 1 处 CI 徽章+注释；
> 其余 4 徽章系 shields.io 静态徽章无需替换）→ **Actions 首跑 run#1 success**（Frontend 55s +
> Backend 3m32s）。证据：`docs/security-evidence/2026-10-05-u5-github/`。

**卡点**：本机未安装 `gh` CLI，无法代建仓库；需要一个用户创建的远程仓库 URL。

**已就位（2026-10-05 审计）**：
- 推送安全审计通过：无敏感文件被跟踪（`.env` 未入库，`git ls-files` 无 `*.key/*.pem/secret` 泄露，
  `docs/SECRETS-AUDIT-*.md` 等命中是管理文档本体）；无 >5MB 大文件（无 .jtl 等压测原始数据入库）。
- CI 门禁本地等价命令已实证 BUILD SUCCESS（全局 LINE 0.10 + contest 0.70 双棘轮）。

**资源到位后执行**（用户可只做第 1 步，其余交回）：
1. GitHub 上创建空仓库（建议 Private 起步）。
2. `git remote add origin <URL>`；`git push -u origin master`
3. README 徽章占位符 `YOUR_GITHUB_ORG/CodeJudge` 共 5 处替换为实际 `<owner>/CodeJudge`
   （CI / Java / Spring Boot / License / Coverage）。
4. 首推后打开 Actions 确认 `.github/workflows/ci.yml` 真跑并绿（首日不该红）。

---

## U6. 告警通道（Alertmanager）

> **✅ 已完成（2026-10-05 晚，真实终验）**：email 通道（smtp.qq.com:587 STARTTLS，零中间件）接入 →
> 注入测试告警 + 真实告警 `JudgeDeadTasksPresent` 经 critical 路由成功投递
> （`notifications_total{email}=1` 成功计数、零错误行）→ **收件人确认收到**。
> 教训：QQ 授权码与发件账号必须同号（首次误填 10086 致持续 535）。
> 证据：`docs/security-evidence/2026-10-05-u6-email-channel/`。AM 保持 email 双路由生产位。

**卡点**：真实通道凭据为空——`.env` 中 `ALERTMANAGER_WECOM_WEBHOOK` / `ALERTMANAGER_SMTP_*` /
`ALERTMANAGER_EMAIL_TO` 全部未填（三选一即可；企业微信/钉钉原生 webhook 与 AM 格式不兼容，
需转换层，详见 `deploy/monitoring/alertmanager/README.md`；**不想搭中间层就选 email，零中间件**）。

**已就位（2026-10-05 回环验证 PASS）**：
- 投递链路已实证：Prometheus 评估的真实告警（`JudgeDeadTasksPresent`）+ API 注入的合成告警
  （`U6PipelineTest`）都经 critical 路由（group_wait 10s）投递到本地 webhook sink（HTTP 200 收包）。
  证据：`docs/security-evidence/2026-10-05-u6-u8-prep/alertmanager-webhook-sink.log` +
  `test-alert-payload.json`。**凭据到位后剩下的只是「换 sink 为真 URL」一步。**

**凭据到位后执行**：
1. `.env` 填 `ALERTMANAGER_DEFAULT_RECEIVER=email`（或 webhook/wecom）+
   `ALERTMANAGER_CRITICAL_RECEIVER=`（生产不可为 null）+ 对应凭据行。
2. `docker compose -f deploy/monitoring/docker-compose.monitoring.yml --env-file .env up -d --force-recreate alertmanager`
3. 测试触达（模板已验证过的注入法）：
   `curl.exe -s -X POST -H "Content-Type: application/json" --data-binary "@docs\security-evidence\2026-10-05-u6-u8-prep\test-alert-payload.json" http://localhost:9093/api/v2/alerts`
   → 通道（群/邮箱）收到即闭环。⚠ 测试 JSON 保存须 ASCII/无 BOM（PS5.1 `utf8` 带 BOM 会被 AM 400 拒收）。

---

## U8. HTTPS（域名 + 证书）

**卡点**：无真实域名与 CA 证书。

**已就位（2026-10-05 rehearsal PASS）**：
- `docker-compose.tls.yml` + `deploy/tls/nginx-tls.conf`：TLS 终结 rehearsal 容器
  （`codejudge-tls-terminator`，nginx:alpine，**9443** 端口，`--profile tls` 隔离，对默认栈零影响），
  WebSocket 升级与 SSE 透传已配。
- 实测：`curl.exe -sk https://127.0.0.1:9443/problems/page?page=1&size=1` → **200** 真实 JSON
  （TLS→网关全链通）。证据：`docs/security-evidence/2026-10-05-u6-u8-prep/tls-handshake-check.txt`。
- 自签证书（CN=codejudge.local，SAN=localhost/127.0.0.1，365 天）在 `deploy/tls/certs/`，已 gitignore（私钥不入库）。

**资源到位后执行**：
1. 证书文件放 `deploy/tls/certs/`（fullchain + key），改 `nginx-tls.conf` 两行
   `ssl_certificate`/`ssl_certificate_key` 指向真实文件；`server_name` 换真实域名。
2. 生产端口切换：compose 端口映射改 `443:443`（或负载均衡/防火墙层转发）。
3. 打开 `nginx-tls.conf` 内注释的 hardening（HSTS 等）；`curl https://<域名>` 复验。
4. 停 rehearsal：`docker compose -f docker-compose.yml -f docker-compose.tls.yml --profile tls down`
   （容器保留仅作演示，不冲突）。

---

## U9. 独立压测机 1h soak 对标

**卡点**：需要第二台物理/虚拟机（与被测宿主分离——同宿主 VM 不算独立，负载发生器与被测端
争抢 CPU/内存会污染数据，这正是 U1 结论里「与压测共宿主」的保留意见来源）。

**已就位**：`soak.jmx` 含定时重登（1500s + threadNum×10s 错峰，已实测 54=18+18+18 零 401）；
基线数据：PERF.md §3.8.2（8,879,545 样本 / 0 错误 / 2466.6 req/s / 四 GET P95 8–12ms）。

**机器到位后执行**：
1. 压测机装 JMeter 5.6.3 + JDK 17+；拷贝 `perf/soak.jmx`（或 `scripts/run-perf.py` + `--plan soak`）。
2. `run-perf.py -J tgBrowse.duration=3600 ...`（⚠ `-J` 参数必须空格分隔）跑 1h soak。
3. 对标口径：样本错误率 0、吞吐 ≥ 基线 80%、四 GET P95 ≤ 基线 ×2（跨机网络抖动容差）、
   登录样本数符合重登公式。结果回填 PERF.md §3.9（新节）。
4. 若压测机无法直连被测网关（跨网段），仅放通 9080 一个端口即可（压测走网关）。
