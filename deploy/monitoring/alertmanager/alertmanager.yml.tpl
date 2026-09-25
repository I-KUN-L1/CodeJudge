# ============================================================
# Alertmanager 配置**模板** —— 由 entrypoint.sh 渲染后使用
#
# 本文件中的变量占位符（美元符 + 花括号）在容器启动时被替换为环境变量值，渲染结果写到
# /alertmanager/rendered-alertmanager.yml，再交给 alertmanager 主进程。
#
# 【为什么要有这一层】Alertmanager **原生不支持从环境变量读配置**：
#   `--config.expand-env` 是 **Prometheus** 的参数，不是 Alertmanager 的
#   （在 v0.27.0 上直接报 `unknown long flag '--config.expand-env'`，容器会 CrashLoop）。
#   而项目规范要求「凭据走环境变量、不硬编码进配置文件」，
#   官方镜像里又没有 envsubst，于是用 entrypoint.sh 里的 awk 做一次展开。
#
# 【默认行为】走 `null` receiver：告警只出现在 Alertmanager UI
#   （http://localhost:9093），**不外发任何通知**。本地开发不该往群里发消息。
#
# 【怎么接真实通道】不改本文件，改环境变量（默认值见 docker-compose.monitoring.yml）：
#   ALERTMANAGER_DEFAULT_RECEIVER    默认/warning 路由的接收器：null / email / webhook / wecom
#   ALERTMANAGER_CRITICAL_RECEIVER   critical 路由的接收器（生产不可为 null）
#   ALERTMANAGER_WECOM_WEBHOOK       企业微信/钉钉 webhook 地址
#   ALERTMANAGER_SMTP_* / ALERTMANAGER_EMAIL_TO   邮件通道
#   改完执行：
#     cd deploy/monitoring && docker-compose -f docker-compose.monitoring.yml \
#         up -d --force-recreate alertmanager
#
# **webhook 的坑（务必先读）**：Alertmanager 原生 webhook 发的是**自家 JSON 结构**
#   （{version, groupKey, alerts:[...]}），而企业微信机器人要的是
#   {"msgtype":"markdown","markdown":{"content":"..."}}、钉钉要
#   {"msgtype":"text","text":{"content":"..."}}。**直接把机器人 URL 填进来是收不到消息的**，
#   中间必须有一层转换器（自建 HTTP 转发，或社区镜像如 timonwong/prometheus-webhook-dingtalk）。
#   详见同目录 README.md。
#   若不想引入中间层，用 `email` —— 邮件是 Alertmanager 原生支持的，零中间件。
#
# 【变量未定义时】渲染器支持写成「变量名:-默认值」的形式（与 compose 同义）；
#   但不建议在这份模板里写默认值 —— compose 的 `environment:` 已经用
#   带默认值的形式（冒号-连字符）兜底过了，默认值保持「只有一个来源」。
#   （注：本行故意不复述带花括号的写法，否则渲染器的残留自检会把它当成漏展开的变量。）
# ============================================================

global:
  resolve_timeout: 5m
  # ---- 邮件通道（留空则 email receiver 不可用；默认路由不走 email，故不影响启动）----
  smtp_smarthost: '${ALERTMANAGER_SMTP_SMARTHOST}'
  smtp_from: '${ALERTMANAGER_SMTP_FROM}'
  smtp_auth_username: '${ALERTMANAGER_SMTP_AUTH_USERNAME}'
  smtp_auth_password: '${ALERTMANAGER_SMTP_AUTH_PASSWORD}'
  smtp_require_tls: true

route:
  # 默认接收器由环境变量决定。compose 保证它至少有值 'null'。
  receiver: '${ALERTMANAGER_DEFAULT_RECEIVER}'
  group_by: ['alertname', 'application']
  group_wait: 30s
  group_interval: 5m
  repeat_interval: 4h
  routes:
    # critical 立即发、重复间隔短 —— 用户已经不可用，压不住就是事故
    - matchers:
        - severity = "critical"
      receiver: '${ALERTMANAGER_CRITICAL_RECEIVER}'
      group_wait: 10s
      group_interval: 1m
      repeat_interval: 1h
    # warning 攒一批再发，避免刷屏
    - matchers:
        - severity = "warning"
      receiver: '${ALERTMANAGER_DEFAULT_RECEIVER}'
      group_wait: 1m
      group_interval: 10m
      repeat_interval: 6h

inhibit_rules:
  # 服务整体 down 时，抑制它下面那些「错误率高 / 延迟高 / 连接池排队」的派生告警。
  # 不抑制的话一次宕机会弹 5 条，真正的原因被埋掉。
  - source_matchers: [alertname = "CodeJudgeServiceDown"]
    target_matchers: [severity =~ "warning|critical"]
    equal: ['application']
  # worker 全下线时，抑制「队列积压」——积压只是全下线的结果，不是原因
  - source_matchers: [alertname = "JudgeWorkersAllOffline"]
    target_matchers: [alertname = "JudgeQueueBacklogHigh"]
    equal: ['application']

receivers:
  # 本地默认：不打搅任何人
  - name: 'null'

  # 通用 webhook（**需要中间转换器**，见文件头「webhook 的坑」与 README.md）
  # 占位 URL 保证配置始终合法；只要路由不指向它就不会被调用。
  - name: 'webhook'
    webhook_configs:
      - url: '${ALERTMANAGER_WECOM_WEBHOOK}'
        send_resolved: true
        max_alerts: 0            # 0 = 不截断，告警全量发出

  # 邮件（原生支持，零中间件）—— 最省事的生产通道
  - name: 'email'
    email_configs:
      - to: '${ALERTMANAGER_EMAIL_TO}'
        send_resolved: true
        headers:
          Subject: '[{{ .Status | toUpper }}] CodeJudge {{ .CommonLabels.alertname }} ({{ .Alerts | len }})'

  # 语义别名：企业微信走 webhook 转换器，单独命名便于阅读配置时一眼看懂意图
  - name: 'wecom'
    webhook_configs:
      - url: '${ALERTMANAGER_WECOM_WEBHOOK}'
        send_resolved: true
        max_alerts: 0
