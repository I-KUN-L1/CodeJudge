# 上线放行单（CodeJudge）

> 生成：**2026-09-22** ｜ 维护：每次 preflight / 配置变更后同步本页
> 依据：`scripts/verify-p6.py`（PASS=98 / FAIL=0）、`scripts/preflight-check.py` 终版读数、
> 2026-09-22 的 promtool / amtool / 零硬编码复验。

---

## 一句话结论

**功能层面 100% 就绪，代码已建立回滚点。**
**放行前必须完成 4 项**（2 项凭据类 / 1 项 HTTPS / 1 项告警通道），其中凭据类**必须由你本人执行**
（AI 在本机读不到 `.env`）。另有 2 项强烈建议在放行前做掉。

---

## A. 阻断放行 —— 必须做完才能对外

| # | 事项 | 为什么阻断 | 你要做的动作 | 验收判据 |
|---|---|---|---|---|
| A1 | **凭据轮换**：`.env` 全部密码、`CJ_JWT_SECRET`、`CJ_LLM_API_KEY`、`GRAFANA_ADMIN_PASSWORD`；并确认两处兜底弱口令已覆盖 | 当前 Grafana 仍可用 `admin/codejudge` 登录（**preflight B4 FAIL**）；两处兜底口令（`CJ_ADMIN_INIT_PASSWORD` / `CJ_USER_DEFAULT_PASSWORD`）不设也是 `123456`，**静默生效** | `python scripts/rotate-credentials.py --rotate` → 确认后 `--rotate --write` → 执行脚本打印的 DB/Redis/MinIO/Grafana 同步命令 → 重启 | preflight **B4 转 PASS**；`check-hardcoded-defaults.py` 的 2 条 WARN 已确认被环境变量覆盖 |
| A2 | **Alertmanager 接真实通道** | 当前所有路由指向 `null` receiver —— **告警不会发到任何地方，等于没有告警**（**preflight F1 FAIL**） | 填 `.env` 的 `ALERTMANAGER_DEFAULT_RECEIVER` / `_CRITICAL_RECEIVER`（+ 邮件/企微凭据），`--force-recreate` | preflight **F1 转 PASS**；并**真的触发一条测试告警**确认收到 |
| A3 | **全站 HTTPS** | 平台承载账号与用户代码，明文传输不可接受 | 上 TLS 终止；⚠️ `judge-ai` 的 SSE 必须配 `proxy_buffering off`，否则流式点评会整段卡住后一次性吐出 | 浏览器无混合内容告警；SSE 点评**逐字出现**而非一次性 |
| A4 | **判题机多实例** | 单实例实测约 **1.5 题/秒**；200 次提交即可把 `judge_queue_backlog` 推到 ~170。**这是全系统最先饱和的一环**（Web 层实测 3108 req/s 远未到顶） | 起 9085/9185/9285 多实例，确认 MQ 消费组分流正常 | preflight **C2** 不再是单实例；压测下 `judge_queue_backlog` 能回落 |

> ⚠️ **A1 的三条附加提醒**：① 换 `CJ_JWT_SECRET` 会让**所有已签发 Token 立即失效**，
> 请选无人使用的时间窗；② `.env` 不入 git，但旧值可能留在备份/截图/聊天记录里，生产上应视为已泄露；
> ③ 命令与逐步复验见 `docs/CONTEXT.md` §5.6。

---

## B. 强烈建议（不阻断，但会显著降低上线风险）

| # | 事项 | 说明 |
|---|---|---|
| B1 | **独立压测机做容量标定** | 本机压测工具与服务同机，实测 **3108.8 req/s / 0 错误 / P95 ≤ 139ms** 只是**容量下界**。按 `perf-test/RUNBOOK.md` 在独立压测机爬坡找拐点 |
| B2 | **按生产容量重标告警阈值** | 现在阈值取自本机基线。生产流量稳定后 `python scripts/recalibrate-alerts.py --prometheus http://<prom>:9090 --write`（**preflight E4 WARN**） |
| B3 | **数据卷备份与回滚演练** | MySQL / PG / Redis / MinIO 的备份策略 + 回滚步骤，**生产前演练一次**（preflight **M5**） |
| B4 | **前端关键路径真实浏览器走查** | 登录 → 选题 → 提交 → 判题进度(WS) → AI 点评(SSE) → 竞赛榜单(WS)（preflight **M6**）。单测 136 项已绿，但**浏览器 E2E 是另一层** |

---

## C. 已完成（本轮复核通过，不阻塞）

| 事项 | 证据 |
|---|---|
| 功能回归 | `verify-p6.py` → **PASS 98 / FAIL 0** |
| 前端单测 | vitest **136 项**全绿（覆盖率 58.9% stmt / 87.5% branch） |
| 告警规则语法 | `promtool check rules` → **11 rules SUCCESS**；`check config` → **SUCCESS** |
| 告警外发机制 | `amtool check-config`（生效的 `rendered-alertmanager.yml`）→ **SUCCESS**，4 receivers 已定义；外发链路此前已用本地接收器实测打通 |
| 零硬编码 | `check-hardcoded-defaults.py` → **FAIL 0 / WARN 2**（WARN 属 A1 的确认项） |
| 仓库可回滚 | `master` 已建提交：`ce335f1`（首提交 430 文件）→ `667872a`（文档修正） |
| 敏感文件未入库 | `node_modules` / `.jtl` / `users.csv` / 真实 `.env` / `.workbuddy` / `logs/` / `coverage/` 全部 0 命中 |
| 网关登录限流 | 已恢复生产默认值（preflight **C1 PASS**：12 次瞬时登录 → 5×200 + 7×429） |

---

## D. 放行前命令清单（按序执行）

```bash
# 1. 凭据（★ 必须你本人跑，AI 读不到 .env）
python scripts/rotate-credentials.py --rotate          # 预演
python scripts/rotate-credentials.py --rotate --write  # 落盘 + 打印同步命令
#    → 逐条执行打印出来的 DB / Redis / MinIO / Grafana 同步命令

# 2. 重启全部服务与监控栈（新凭据生效）
docker-compose up -d
python scripts/dev-start-backend.py --wait
cd deploy/monitoring && docker-compose --env-file ../../.env -f docker-compose.monitoring.yml up -d && cd ../..

# 3. 复验
python scripts/preflight-check.py            # 期望 B4 / F1 转 PASS，FAIL=0
python scripts/check-hardcoded-defaults.py   # 期望 FAIL=0
python scripts/verify-p1-login.py            # 换密钥后登录链路必须仍通过
python scripts/verify-p6.py                  # 功能回归，期望 PASS=98 / FAIL=0
```

---

## E. 已知限制（放行前请确认是否能接受）

以下均来自 `docs/CONTEXT.md` §7 的既有记录，**本轮未重新验证**，列出供放行决策时逐条拍板：

| 限制 | 影响 |
|---|---|
| MinIO 当前在 `storage` profile（本机 registry 拉取被拒） | 上线前需移出该 profile，否则对象存储不可用 |
| RocketMQ broker **未挂 store 卷** | **消息不持久化**，broker 重启会丢未消费消息 |
| 沙箱运行时是 `runc` + seccomp（本机无 gVisor） | 与宿主共享内核，隔离强度弱于 gVisor。公网判题平台建议装 gVisor（preflight **D2 WARN**） |
| Grafana 匿名只读仍开启（`GF_AUTH_ANONYMOUS_ENABLED=true`） | 生产必须置 false（preflight **B3 WARN**） |
| `CJ_LLM_ENABLED=false` 时 AI 点评走结构化降级 | 接真实 LLM 后需复跑 `verify-p5.py` 的 G 段（切到非降级分支） |
| SSE 并发上限默认 200，未压测 | 上线后按实际并发观察（P5 遗留增强项） |

---

## F. 回滚点

- `ce335f1` —— 首提交，430 文件 / +56 035 行，覆盖 P1–P6 全部交付物
- `667872a` —— 文档一致性与陷阱表修正
- ⚠️ **无远端**：`git remote -v` 为空。要异地备份需先 `git remote add` 并处理认证
