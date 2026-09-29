# CodeJudge 上线前检查清单（终版）

> 生成：**2026-09-25 18:00** ｜ 维护人：小柯
> 依据：`docs/LAUNCH-READINESS.md`（放行单）+ 第八轮全仓审查（`docs/REVIEW-2026-09-25.md`）
> 状态图例：✅ 已完成 ｜ 🟦 本轮由 AI 完成并验证 ｜ 👤 **必须用户本人执行** ｜ ⏸ 待用户拍板

---

## 一、阻断项（不做完不能对外放行）

| # | 事项 | 状态 | 负责人 | 需要的材料 / 动作 | 验收判据 |
|---|---|---|---|---|---|
| A1 | 生产环境凭据重新生成（`.env` 全部密码、`CJ_JWT_SECRET`、`CJ_LLM_API_KEY`、Grafana 口令） | 👤 待办 | **用户**（目标环境） | 目标服务器操作权；按 §五 命令序列执行 `rotate-credentials.py` | preflight **B4 PASS**；`check-hardcoded-defaults.py` FAIL=0/WARN=0 |
| A2 | Alertmanager 接真实告警通道（当前路由指向 null，**等于没有告警**） | 👤 待办 | **用户** | 二选一：① SMTP：host/port/账号/授权码 + 收件人列表；② 企业微信机器人 webhook URL | preflight **F1 转 PASS** + 真实触发一条测试告警并确认收到 |
| A3 | 全站 HTTPS（平台承载账号与用户代码，明文不可接受） | 👤 待办 | **用户** | ① 已备案域名；② TLS 证书（自有证书文件，或目标机可签 Let's Encrypt）；⚠️ 反代须对 judge-ai 的 SSE 配 `proxy_buffering off` | 浏览器无混合内容告警；SSE 点评逐字出现 |
| A4 | 判题容量决策（吞吐 0.95 题/s **不随实例数增长**，瓶颈=每题 5 容器启停） | ⏸ 待拍板 | **用户** | 二选一：① 接受现容量并按 `实例数 × CJ_MQ_CONSUME_THREADS ≤ CPU 核数` 约束（多实例须跨宿主机）；② 排期「减少每题容器数」优化（单容器全用例 / 容器池预热） | preflight **C2**；`verify-p3.py` A1b 时限 + 抽样全 AC |

> ⚠️ A1 附带：换 `CJ_JWT_SECRET` 会使全部已签发 Token 立即失效，选无人使用的时间窗；本地 `.env` 旧值视为已泄露。

---

## 二、部署动作（配置我已就绪，由用户在生产执行）

| # | 事项 | 状态 | 负责人 | 具体动作 |
|---|---|---|---|---|
| D1 | 基础设施容器重建（回环绑定生效） | 🟦 配置就绪 → 👤 执行 | 用户 | `docker-compose up -d`（`BIND_IP` 默认 127.0.0.1，需局域网访问时改 `.env`） |
| D2 | 网关与 judge-auth **同批重启** | 🟦 已同步契约 → 👤 执行 | 用户 | token `type` 校验两侧版本必须一致，否则合法请求被判 401 |
| D3 | MinIO 启动（P3 超长代码存储） | 👤 待办 | 用户 | `docker compose --profile storage up -d minio`（受限网络若镜像被拒需先解决拉取） |
| D4 | RocketMQ broker store 卷持久化 | 🟦 方案明确 → 👤 执行 | 用户 | 先建卷并 `chown 3000:3000`，再挂载 `/home/rocketmq/store`（**不可直接挂命名卷**——镜像内目录不存在 + uid 3000 无写权限，broker 会 ExitCode=253 静默退出，踩坑记录见 docker-compose.yml 注释） |
| D5 | 接口文档生产关闭 | 🟦 **已完成**（① `CJ_SPRINGDOC_ENABLED` 开关铺满 7 个对外服务；② 2026-09-28 补网关层开关 `CJ_DOC_WHITELIST_ENABLED` —— `.env.example` 生产安全默认 false，开发 `.env` 显式 true） | 用户（生产 `.env` **两项都置 false**：`CJ_SPRINGDOC_ENABLED=false` + `CJ_DOC_WHITELIST_ENABLED=false`） | 两层独立防线：服务端关文档端点 + 网关关匿名放行（网关开关开着时 `/v3/api-docs`、`/doc.html` 匿名可达，等于公开全部 API 结构；置 false 后匿名访问一律 401） |
| D6 | 日志滚动 | 🟦 **本轮核实**：`logs/*.log` 是启动脚本重定向的开发态产物，代码层无需改动 | 用户（按部署形态） | 容器部署 → json-file/local 日志驱动配 max-size/max-file；systemd → logrotate |
| D7 | Grafana 匿名访问 | 🟦 本轮已改默认 false（fail-closed） | 用户 | 本地需匿名浏览时 `.env` 显式 `GF_AUTH_ANONYMOUS_ENABLED=true` |

---

## 三、强烈建议（不阻断，显著降低上线风险）

| # | 事项 | 状态 | 负责人 |
|---|---|---|---|
| B1 | 独立压测机做容量标定（本机 3108 req/s 只是**下界**） | 👤 待办（需第二台机器） | 用户，按 `perf-test/RUNBOOK.md` |
| B2 | 生产流量稳定后重标告警阈值 | 👤 上线后 | 用户 `recalibrate-alerts.py --write`（preflight E4） |
| B3 | 数据卷备份：异地存放 + 保留周期 + **定期重跑演练** | ✅ 脚本已落地演练通过（B3=2026-09-22）→ 定期重跑 👤 | 用户 |
| B4 | 前端关键路径真实浏览器 E2E（登录→选题→提交→判题 WS→AI 点评 SSE→榜单 WS） | ✅ **2026-09-25 完成**：`scripts/e2e-critical-path.cjs` **8/8 PASS**（Edge headless + 原生 CDP，可重复执行挂 CI）。并当场抓到并修复 1 个真 bug：`ContestDetailView.vue` 漏导入 `watch` → 竞赛详情页 setup 崩溃（此前榜单 WS 建不起来的根因）；修复后 vitest 167/167 | 小柯（脚本化，可重复） |
| B5 | gVisor 沙箱运行时（现为 runc+seccomp，与宿主共享内核） | 👤 待办 | 用户（目标机装 runsc，preflight D2） |

---

## 四、待拍板的暂缓项（本轮审查已记录、有意未动）

| # | 事项 | 为什么暂缓 |
|---|---|---|
| P1 | refresh token 轮换/吊销（Redis jti） | 属行为变更（会踢在线用户），需拍板后排期 |
| P2 | Python 语法错误判 RE 非 CE（沙箱加 `py_compile` 预检） | 判题语义增强，需配套回归 |
| P3 | Java 堆内 MLE 判不出 | 依赖沙箱内存采样方案 |
| P4 | `problem.submit_count/accepted_count` 代码无写入点（数据侧已回填） | 补写入点需定增量口径（事务内 update vs MQ 汇总） |
| P5 | 无 git 远端（回滚点仅本机） | 需用户提供远端仓库地址 + 凭据 |
| P6 | `.bootstrap-credentials` 引导态改密 | **留给用户本人**执行 first-change |

---

## 五、放行前命令序列（目标环境按序执行）

```bash
# 1. 凭据（目标环境重新生成，本地值不可复用）
python scripts/rotate-credentials.py --rotate          # 预演
python scripts/rotate-credentials.py --rotate --write  # 落盘 → 逐条执行打印的 DB/Redis/MinIO/Grafana 同步命令

# 2. 重启全部服务与监控栈
docker-compose up -d
python scripts/dev-start-backend.py --wait
cd deploy/monitoring && docker-compose --env-file ../../.env -f docker-compose.monitoring.yml up -d && cd ../..

# 3. 复验
python scripts/preflight-check.py            # 期望 F1 转 PASS，FAIL=0
python scripts/check-hardcoded-defaults.py   # 期望 FAIL=0 / WARN=0
python scripts/verify-p1-login.py            # 换密钥后登录链路
python scripts/verify-p3.py                  # 判题链路（⚠️ 一分钟内勿连跑两次，提交限流 30 次/分）
python scripts/verify-p6.py                  # 功能回归 PASS=98 / FAIL=0

# 4. 容量标定（可选，测完必须恢复 CJ_SUBMIT_RATE_LIMIT=30 并验证）
python scripts/measure-judge-throughput.py --n 150 --label baseline
```

---

## 六、已完成基线（证据索引）

| 事项 | 证据 | 时间 |
|---|---|---|
| 功能回归 | `verify-p6.py` PASS 98 / FAIL 0 | 2026-09-22 |
| 鉴权收敛 | `verify-authz.py` 56/0；后端 90 / 前端 153 单测 | 2026-09-23 |
| 第八轮安全审查（约 40 项） | `mvn clean install` 11 模块全绿；vitest 167/167；`vite build` 通过 | 2026-09-25 |
| 回滚点 | `ce335f1` → `667872a` → `9c52a47` → **`690f226`**（本轮 170 文件） | 2026-09-25 |
| 备份/回滚演练 | `backup-volumes.py` 实测通过（MySQL 133=133、PG 1=1） | 2026-09-22 |
| 告警规则语法 | promtool 11 rules / amtool check-config SUCCESS | 2026-09-22 |
| 敏感文件未入库 | node_modules/.env/logs/coverage 全 0 命中 | 2026-09-25 复核 |
