# Alertmanager 告警外发接入

> 默认形态是 **`null` receiver —— 告警只出现在 Alertmanager UI（http://localhost:9093），
> 不会发到任何地方。** 上线前必须接一个真实通道，否则「有监控」等于「没有告警」。

本目录三个文件：

| 文件 | 作用 |
|---|---|
| `alertmanager.yml.tpl` | 配置**模板**，含 `${VAR}` 占位符 |
| `entrypoint.sh` | 容器入口：先把模板渲染成实际配置，再启动 alertmanager |
| `README.md` | 本文 |

---

## 一、为什么多了一层 `entrypoint.sh`

因为 Alertmanager **做不到「凭据走环境变量」**，而这是本项目的硬规范。三个实测结论：

1. **`--config.expand-env` 不是 Alertmanager 的参数。**
   它是 **Prometheus** 的。在 `prom/alertmanager:v0.27.0` 上加这个 flag：
   ```
   alertmanager: error: unknown long flag '--config.expand-env'
   ```
   容器直接 CrashLoop。（如果不加，配置文件里的 `${VAR}` 会被当**字面量字符串**，
   receiver 名变成 `"${ALERTMANAGER_DEFAULT_RECEIVER}"`，路由永远匹配不到 →
   **告警静默不发，而 UI 和日志全都正常** —— 比崩溃更难发现。）

2. **官方镜像是精简镜像，没有 `envsubst`**（只有 `sh` / `sed` / `awk` / `grep`）。
   所以 `entrypoint.sh` 用 `awk` 做展开。它只替换 `${...}` 形式，
   **不会碰 Go 模板的 `{{ .Status }}`**（邮件主题里用到）。

3. **渲染结果有自检**：若残留 `${...}`（模板里变量名拼错），容器**直接退出**并打印位置，
   不带半成品配置启动 —— 避免又变成「启动成功但通知不到人」。

---

## 二、启用步骤（以邮件为例）

### 1. 在项目根 `.env` 追加

```properties
# ---- 告警外发 ----
ALERTMANAGER_DEFAULT_RECEIVER=email
ALERTMANAGER_CRITICAL_RECEIVER=email
ALERTMANAGER_SMTP_SMARTHOST=smtp.example.com:587
ALERTMANAGER_SMTP_FROM=alertmanager@your-domain.com
ALERTMANAGER_SMTP_AUTH_USERNAME=alertmanager@your-domain.com
ALERTMANAGER_SMTP_AUTH_PASSWORD=<SMTP 授权码>
ALERTMANAGER_EMAIL_TO=oncall@your-domain.com
```

### 2. 带上 `--env-file` 重启

```bash
cd deploy/monitoring

# ⚠ --env-file 不能省。原因见第四节。
docker-compose --env-file ../../.env -f docker-compose.monitoring.yml \
    up -d --force-recreate alertmanager
```

### 3. 验证

```bash
docker logs codejudge-alertmanager | grep entrypoint
# [entrypoint] 默认接收器 = email ｜ critical = email

python scripts/preflight-check.py     # F1 应变为 PASS
```

---

## 三、全部可配置变量

| 变量 | 默认值 | 说明 |
|---|---|---|
| `ALERTMANAGER_DEFAULT_RECEIVER` | `null` | 默认路由与 warning 路由的接收器：`null` / `email` / `webhook` / `wecom` |
| `ALERTMANAGER_CRITICAL_RECEIVER` | `null` | critical 路由的接收器。**生产不可为 null** |
| `ALERTMANAGER_WECOM_WEBHOOK` | `http://127.0.0.1:1/disabled` | 企业微信/钉钉 webhook（**需转换器**，见第五节） |
| `ALERTMANAGER_SMTP_SMARTHOST` | `localhost:25` | SMTP 服务器 `host:port` |
| `ALERTMANAGER_SMTP_FROM` | `alertmanager@codejudge.invalid` | 发件人 |
| `ALERTMANAGER_SMTP_AUTH_USERNAME` | 空 | SMTP 用户名 |
| `ALERTMANAGER_SMTP_AUTH_PASSWORD` | 空 | SMTP 密码 / 授权码 |
| `ALERTMANAGER_EMAIL_TO` | `alerts@codejudge.invalid` | 收件人 |

> 邮件相关默认值带 `.invalid` 后缀 —— 这是 RFC 2606 保留域名，**永远不会被解析到**，
> 保证「默认不外发」形态下配置依然合法（Alertmanager 会无条件校验所有 receiver，
> 哪怕它没被任何路由引用；`to` 为空会直接报 `missing to address in email config` 并 CrashLoop）。

---

## 四、⚠ 两个 Git Bash 环境陷阱（都实测踩过）

### 1. `VAR=x docker-compose ...` 前缀赋值**不生效**，`export` 也**不生效**

本机 `docker-compose` 是 Docker Desktop 的 v5.4.0 二进制（`/c/Program Files/Docker/Docker/resources/bin/docker-compose`）。
实测：无论用命令前缀赋值还是 `export`，它都**读不到 shell 环境变量**，一律走 compose 里的默认值。

```bash
# ❌ 不生效（容器内仍是 null）
ALERTMANAGER_DEFAULT_RECEIVER=email docker-compose ... up -d --force-recreate alertmanager
export ALERTMANAGER_DEFAULT_RECEIVER=email && docker-compose ... up -d --force-recreate alertmanager

# ✅ 生效
docker-compose --env-file ../../.env -f docker-compose.monitoring.yml up -d --force-recreate alertmanager
```

对比印证：同一 shell 里 `python -c "import os; print(os.environ['X'])"` **能**读到 `export` 的变量
（项目其它脚本如 `dev-start-backend.py` 依赖的正是这一点），只有这个 compose 二进制读不到。

### 2. 变量名大小写与作用域

`--env-file` 指向的必须是 **properties 格式**（`KEY=VALUE`，不要引号、等号两侧不要空格），
即项目根 `.env` 的格式 —— 与 Spring 读的是同一份，无需维护两套。

---

## 五、企业微信 / 钉钉：必须过一层转换器

**Alertmanager 原生 webhook 发的是自家 JSON**：

```json
{"version":"4","groupKey":"...","status":"firing","alerts":[...]}
```

而机器人要的是：

```json
{"msgtype":"markdown","markdown":{"content":"..."}}     // 企业微信
{"msgtype":"text","text":{"content":"..."}}             // 钉钉
```

**直接把机器人 URL 填进 `ALERTMANAGER_WECOM_WEBHOOK` 是收不到消息的**
（Alertmanager 认为发送成功，机器人那边静默丢弃 —— 又是一个「看起来配好了」的坑）。

两条路：

1. **不想引入中间件 → 用 `email`**。邮件是 Alertmanager 原生支持的，零依赖。
   对内部运维通知来说，邮件完全够用，建议优先选它。
2. **要发群消息 → 加一个转换器**，把 `ALERTMANAGER_WECOM_WEBHOOK` 指向转换器地址。
   社区现成镜像：`timonwong/prometheus-webhook-dingtalk`（钉钉）、
   各类 `webhook-bridge`（企业微信）。转换器不在本 compose 内，需自行部署。

---

## 六、验证告警真的能发出去

不想等真实故障，可以手工造一条告警：

```bash
# 往 Alertmanager 推一条测试告警（走 v2 API）
curl -X POST http://127.0.0.1:9093/api/v2/alerts \
  -H 'Content-Type: application/json' \
  -d '[{"labels":{"alertname":"TestAlert","severity":"critical","application":"manual-test"},
        "annotations":{"summary":"通道连通性测试","description":"手工注入，可忽略"},
        "startsAt":"2026-01-01T00:00:00Z"}]'
```

- 配好通道：几秒内收到邮件/群消息；
- 仍是 `null`：只在 http://localhost:9093 的 UI 里看到这条告警。

发完清理（否则会一直躺在 UI 里）：

```bash
curl -X DELETE http://127.0.0.1:9093/api/v2/alerts \
  -H 'Content-Type: application/json' \
  -d '[{"labels":{"alertname":"TestAlert","application":"manual-test"}}]'
```
