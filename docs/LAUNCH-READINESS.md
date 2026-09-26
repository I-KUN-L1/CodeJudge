# 上线放行单（CodeJudge）

> 生成：**2026-09-22** ｜ 维护：每次 preflight / 配置变更后同步本页
> 依据：`scripts/verify-p6.py`（PASS=98 / FAIL=0）、`scripts/preflight-check.py` 终版读数、
> 2026-09-22 的 promtool / amtool / 零硬编码复验。

---

## 一句话结论

**功能层面 100% 就绪，代码已建立回滚点。**
**放行前必须完成 3 项**（1 项凭据类 / 1 项 HTTPS / 1 项告警通道），另有 1 项判题容量决策需要拍板。
本地凭据轮换**已于 2026-09-22 完成并实测**（Grafana 默认口令已失效、伪造 JWT 被拒），
但**生产环境必须重新生成一套**——本地值不得直接带去生产。

---

## A. 阻断放行 —— 必须做完才能对外

| # | 事项 | 为什么阻断 | 你要做的动作 | 验收判据 |
|---|---|---|---|---|
| A1 | **生产环境凭据重新生成**：`.env` 全部密码、`CJ_JWT_SECRET`、`CJ_LLM_API_KEY`、`GRAFANA_ADMIN_PASSWORD` | 本地轮换已完成（preflight **B4 已 PASS**、伪造 JWT 被拒），但**本地值不可复用到生产**；且两处兜底口令已改 fail-closed，未配置时不会静默降级（`CJ_ADMIN_INIT_PASSWORD` 未配置 → 随机 24 位写入凭据文件；`CJ_USER_DEFAULT_PASSWORD` 未配置 → 重置接口返回 400） | 在目标环境执行 `python scripts/rotate-credentials.py --rotate` → 确认后 `--rotate --write` → 执行脚本打印的 DB/Redis/MinIO 同步命令 → 重启 | preflight **B4 PASS**；`check-hardcoded-defaults.py` **FAIL=0 / WARN=0**（本地已达成） |
| A2 | **Alertmanager 接真实通道** | 当前所有路由指向 `null` receiver —— **告警不会发到任何地方，等于没有告警**（**preflight F1 FAIL**） | 填 `.env` 的 `ALERTMANAGER_DEFAULT_RECEIVER` / `_CRITICAL_RECEIVER`（+ 邮件/企微凭据），`--force-recreate` | preflight **F1 转 PASS**；并**真的触发一条测试告警**确认收到 |
| A3 | **全站 HTTPS** | 平台承载账号与用户代码，明文传输不可接受 | 上 TLS 终止；⚠️ `judge-ai` 的 SSE 必须配 `proxy_buffering off`，否则流式点评会整段卡住后一次性吐出 | 浏览器无混合内容告警；SSE 点评**逐字出现**而非一次性 |
| A4 | **判题容量决策（吞吐不随实例数增长，需拍板）** | 🔴 **2026-09-22 实测标定**：单实例 **0.95 题/s**，**3 实例也只有 0.96 题/s** —— 「多实例线性扩容」**不成立**；且未限并发时**正确解会被判假 TLE**（抽样 18/25）。瓶颈是**每题 5 个容器的启停开销**（宿主容器启停上限约 10–11 容器/s） | 二选一：① 接受当前容量并按 §3.7 的公式约束并发（`实例数 × CJ_MQ_CONSUME_THREADS ≤ CPU 核数`，且**多实例须跨宿主机**）；② 排一个「减少每题容器数」的优化项（如全部用例合到单容器 / 预热容器池） | preflight **C2**；`verify-p3.py` 的 **A1b 主路径时限**（新增）与抽样 verdict 全 AC |

> ⚠️ **A1 的三条附加提醒**：① 换 `CJ_JWT_SECRET` 会让**所有已签发 Token 立即失效**，
> 请选无人使用的时间窗；② `.env` 不入 git，但旧值可能留在备份/截图/聊天记录里，生产上应视为已泄露；
> ③ 命令与逐步复验见 `docs/CONTEXT.md` §5.6。

---

## B. 强烈建议（不阻断，但会显著降低上线风险）

| # | 事项 | 说明 |
|---|---|---|
| B1 | **独立压测机做容量标定** | 本机压测工具与服务同机，实测 **3108.8 req/s / 0 错误 / P95 ≤ 139ms** 只是**容量下界**。按 `perf-test/RUNBOOK.md` 在独立压测机爬坡找拐点 |
| B2 | **按生产容量重标告警阈值** | 现在阈值取自本机基线。生产流量稳定后 `python scripts/recalibrate-alerts.py --prometheus http://<prom>:9090 --write`（**preflight E4 WARN**） |
| B3 | **数据卷备份与回滚演练** | ✅ **2026-09-22 已落地并演练通过**：新增 `scripts/backup-volumes.py`（`--backup` 备份 5 个 MySQL 库 + PG + Redis RDB + MANIFEST 校验和；`--verify` 做**非破坏性**回滚演练）。实测：MySQL `user` 表 **生产 133 = 演练 133**、PG `knowledge_chunk` **1 = 1**、RDB `redis-check-rdb` 可加载、临时库无残留。⚠️ 生产仍需：备份异地存放 + 保留周期 + **定期重跑演练**（一次演练不等于长期可靠） |
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
| **P3 回归（2026-09-22 补）** | `verify-p3.py` → **PASS=21 / FAIL=0**（新增 `A1b 主路径时限` 4.2s；负向对照压到 3s 会 FAIL，证明断言非恒真）；E 段管理员凭据改由 `.env` 提供后**不再 SKIP** |
| **P6 回归（2026-09-22 补）** | `verify-p6.py` → **PASS=98 / FAIL=0**（22.7s） |
| **提交限流已恢复并验证（2026-09-22 补）** | 吞吐标定临时放宽后已恢复 `CJ_SUBMIT_RATE_LIMIT=30`；**31 次连续提交 → 前 30 成功、第 31 次 400「提交过于频繁」** |
| **判题机并发旋钮（2026-09-22 新增）** | `CJ_MQ_CONSUME_THREADS` 默认 0 = 行为不变；实测 `=4` 时日志 `消费线程数已限定：min=4 max=4`，假 TLE 消失（25/25 AC） |

---

## D. 放行前命令清单（按序执行）

```bash
# 1. 凭据（在**目标环境**重新生成，本地值不可复用）
python scripts/rotate-credentials.py --rotate          # 预演
python scripts/rotate-credentials.py --rotate --write  # 落盘 + 打印同步命令
#    → 逐条执行打印出来的 DB / Redis / MinIO / Grafana 同步命令

# 2. 重启全部服务与监控栈（新凭据生效）
docker-compose up -d
python scripts/dev-start-backend.py --wait
cd deploy/monitoring && docker-compose --env-file ../../.env -f docker-compose.monitoring.yml up -d && cd ../..

# 3. 复验
python scripts/preflight-check.py            # 期望 F1 转 PASS，FAIL=0
python scripts/check-hardcoded-defaults.py   # 期望 FAIL=0 / WARN=0
python scripts/verify-p1-login.py            # 换密钥后登录链路必须仍通过
python scripts/verify-p3.py                  # 判题链路 + A1b 主路径时限，期望 PASS=21 / FAIL=0
python scripts/verify-p6.py                  # 功能回归，期望 PASS=98 / FAIL=0

# 4. 判题容量标定（可选但强烈建议；详见 docs/PERF.md §3.7）
#    先按脚本提示放宽 CJ_SUBMIT_RATE_LIMIT，测完**必须恢复并验证**
python scripts/measure-judge-throughput.py --n 150 --label baseline
#    多实例判题必须同时限并发（实例数 × 该值 ≤ CPU 核数）：
CJ_MQ_CONSUME_THREADS=4 python scripts/dev-start-backend.py --extra judge-worker=9185:9285 --wait
```

> ⚠️ **验收脚本与提交限流会打架**：`verify-p3.py` 单轮提交 ~20 次，而默认限流是 30 次/分钟/账号
> —— **一分钟内连跑两次会大面积 FAIL**（看着像回归，其实是限流）。跑前先等窗口。

---

## E. 已知限制（放行前请确认是否能接受）

以下均来自 `docs/CONTEXT.md` §7 的既有记录，**本轮未重新验证**，列出供放行决策时逐条拍板：

| 限制 | 影响 |
|---|---|
| MinIO 当前在 `storage` profile（本机 registry 拉取被拒） | 上线前需移出该 profile，否则对象存储不可用 |
| RocketMQ broker **未挂 store 卷** | **消息不持久化**，broker 重启会丢未消费消息 |
| 沙箱运行时是 `runc` + seccomp（本机无 gVisor） | 与宿主共享内核，隔离强度弱于 gVisor。公网判题平台建议装 gVisor（preflight **D2 WARN**） |
| Grafana 匿名只读仍开启（`GF_AUTH_ANONYMOUS_ENABLED=true`） | 生产必须置 false（preflight **B3 WARN**） |
| `CJ_LLM_ENABLED=false` 时 AI 点评走结构化降级 | ✅ **2026-09-26 已接入真实 LLM**（智谱 glm-4.5-air，Key 在本地 `.env`）：`degraded=false` 流式输出正常，`verify-p5.py` 复跑 **PASS=46 / FAIL=0**。生产环境凭据生成（A1）时同步决定 `CJ_LLM_ENABLED` 与 `CJ_LLM_API_KEY` |
| SSE 并发上限默认 200，未压测 | 上线后按实际并发观察（P5 遗留增强项） |
| **判题吞吐 ≈0.95 题/s，且不随实例数增长** | 队列会以约 1 题/s 的速度排空：200 次提交 ≈ 3.5 分钟。**这是当前最紧的一环**（详见 A4 与 `docs/PERF.md` §3.7） |
| **每题 5 个 `docker run`**（1 编译 + 4 用例） | 宿主容器启停上限约 10–11 容器/s，故理论上限约 2 题/s；且并发争抢会让**正确解被判 TLE**（已提供 `CJ_MQ_CONSUME_THREADS` 约束） |

---

## F. 回滚点

- `ce335f1` —— 首提交，430 文件 / +56 035 行，覆盖 P1–P6 全部交付物
- `667872a` —— 文档一致性与陷阱表修正
- ⚠️ **无远端**：`git remote -v` 为空。要异地备份需先 `git remote add` 并处理认证

---

## G. 2026-09-25 上线前全面审查轮（安全收口）

全仓审查（4 路并行：后端业务模块 / 基础与网关 / 配置部署 / 前端）共确认约 40 项问题，
高危项全部当日修复，验证方式：`mvn clean install`（含单测）+ `vitest 167/167` + `vite build`。

**本轮修复的安全项（放行视角重点）**：

| # | 修复 | 文件 |
|---|---|---|
| 1 | 🔴 `/jwks` 匿名泄露 HMAC 签名密钥 → 端点/工具方法/白名单/路由全删（§4.4 方案 A） | judge-auth / judge-gateway / judge-api |
| 2 | 🔴 refresh token 可直接当 access token 用 → 网关校验 `type=access`，续签校验 `type=refresh` | JwtUtils / AccountService |
| 3 | 🔴 `PUT /users` 水平越权（可改任意人含管理员密码）→ 目标强制取 UserContext，自助入口不再受理密码/状态/角色 | UserController / UserService |
| 4 | 🔴 `PageQuery.sortBy` SQL 盲注 → 列名白名单正则 + 页宽钳制到 MAX_PAGE_SIZE | judge-common PageQuery |
| 5 | `/accounts/admin/login` 绕过登录限流 → 限流谓词扩到两个入口 | gateway application.yml |
| 6 | `GET /users/{id}` PII（手机号）任意登录可读 → OwnerAccessGuard（本人/STAFF） | UserController |
| 7 | refresh token 随响应体下发（架空 HttpOnly）→ `@JsonIgnore`，只走 Cookie | LoginResultVO |
| 8 | 改密接口零校验（缺字段 NPE、无强度门槛）→ 判空 + 6~64 位 | UserService.changePassword |
| 9 | CookieBuilder SameSite 分支静默丢弃 secure/domain、setHeader 吞多枚 cookie | CookieBuilder |
| 10 | 中间件端口绑 0.0.0.0 → 默认 `127.0.0.1`（`.env` 的 `BIND_IP` 可覆盖）；Prometheus admin API 移除；Grafana 匿名默认 false | docker-compose*.yml / .env.example |

**注意（部署动作）**：
- `.env` 现存文件不受影响，但**基础设施容器需 `docker-compose up -d` 重建**才会应用回环绑定。
- `CJ_JWT_SECRET` 行为变化：旧签发的 token 均含 `type` claim，无兼容问题；但**网关与 auth 必须同批重启**（一边新一边旧会把合法请求判 401）。
- `verify-p1-login.py` 已同步：refreshToken 不再随 body 下发的新断言。
- MySQL 驱动 8.0.23 → 8.3.0（新坐标 `com.mysql:mysql-connector-j`）：首次在线构建后即可离线。
