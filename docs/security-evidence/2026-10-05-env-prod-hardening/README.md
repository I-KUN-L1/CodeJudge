# .env 生产态约定执行（上线检查单 §四.8）——2026-10-05 晚

状态：**✅ 已执行并通过全量回归**。密钥全量轮换 + 两个生产开关落地，凭据只存 gitignored `.env`。

## 轮换清单（值全部已入 .env，不入 git）

| 项 | 方式 | 验证 |
|---|---|---|
| MySQL root（`MYSQL_PASSWORD`+`MYSQL_ROOT_PASSWORD`） | 容器内 `ALTER USER 'root'@'%/localhost'` | 8 服务重连 healthy |
| Postgres postgres | 容器内 `ALTER ROLE` | pg healthy |
| Grafana admin | `grafana-cli reset-admin-password` | 新密登录口径（CLI 确认成功） |
| Redis / MinIO / JWT / actuator token | `.env` 换新 + 服务重建 | 全链回归（下表） |
| **admin 账号真实改密** | 引导凭据 `POST /accounts/password/first-change`（bootstrap 从未消费过） | code=200 + 新密登录 200 + mustChange=false；`.bootstrap-credentials` 已删除 |
| `CJ_ADMIN_INIT_PASSWORD` | .env 同步为新 admin 密码 | 引导文件若重播种则同密 |

**不轮换项（决策记录）**：`CJ_USER_DEFAULT_PASSWORD` 保持——种子/E2E/verify-p1-login/压测账号池
同源依赖（PERF.md 工具链），教学内网场景下配合「首登改密/教师重置」策略管理。

## 生产开关（.env）

- `CJ_DOC_WHITELIST_ENABLED=false` → 网关 `/v3/api-docs`、`/doc.html` 实测 **404**
- `GF_AUTH_ANONYMOUS_ENABLED=false` → Grafana 匿名 API 实测 **401**（登录页 200）
- Prometheus `authorization.credentials` 已同步新 actuator token（[prometheus.yml](../../deploy/monitoring/prometheus/prometheus.yml)）

## 回归验证组（轮换后全绿）

| 检查 | 结果 |
|---|---|
| 8 Java 服务 healthy（重建后） | ✅ 8/8 |
| sandbox-init | ✅ Exited(0) 自动重跑 |
| Prometheus codejudge targets（严格模式 + 新 token） | ✅ 8/8 up（旧 token 即 401/404） |
| verify-p1-login | ✅ 43/0 |
| E2E（fixture `setup-contest.mjs` → `E2E_CONTEST_ID=2107086…`） | ✅ 5/5（15.0s，含判题链=沙箱/MQ/worker 新凭据实证） |
| 核心只读 API | ✅ `/problems/page` 200 |

## 排障实录（两条坑 + 一条接线）

1. **CRLF 卡正则**：`-replace '(?m)^KEY=true$'` 被 `\r` 挡住未生效（`.*$` 模式则正常——`.*` 吃掉 `\r`）。
   教训：改 .env 行值用 `^KEY=.*$`；验证开关改没改**打印真实值**，别用长度判断（true/false 同为 4 字符，曾误判）。
2. **env_file 内容变化不触发重建**：`docker compose up -d --force-recreate` 只重建了 compose 级 env
   变化的基础设施，8 个 Java 服务（env_file 注入）原样未动 → 短暂「旧密码连新库」失联。
   教训：改 .env 后必须**显式 force-recreate 服务清单**。
3. **E2E 接线**：先 `node e2e/setup-contest.mjs` 产出 `E2E_CONTEST_ID` 再跑 playwright；
   沙箱内需 `$env:npm_config_cache="$env:TEMP\npm-cache-e2e"` 否则 npm 写自身目录被拒。

## 交付提醒（用户）

- admin 登录密码 = `.env` 中 `CJ_ADMIN_INIT_PASSWORD` 当前值（本地打开查看；未在任何通道外发）。
- Grafana admin 密码 = `.env` 中 `GRAFANA_ADMIN_PASSWORD`。
- 内网访问如走非 localhost 来源，需按实际访问地址扩 `CORS_ALLOWED_ORIGINS`（现为 localhost/127.0.0.1）。
