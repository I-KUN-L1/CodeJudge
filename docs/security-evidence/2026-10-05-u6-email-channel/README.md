# U6 email 通道接入（已结案：真实终验 PASS）

时间：2026-10-05 19:20–19:45 CST · 状态：**✅ 已结案**——AM 侧 `notifications_total{email}=1` 零错误
+ 收件人确认收到 `[FIRING] CodeJudge JudgeDeadTasksPresent (2)`。AM 保持 email 双路由生产位。

## 已实证

1. `.env` 7 行凭据填入（email 通道，smtp.qq.com:587 STARTTLS；**凭据只存 .env，不入 git**）。
2. `--force-recreate alertmanager` → 渲染配置全部正确：
   `smtp_smarthost: 'smtp.qq.com:587'` / `smtp_from=smtp_auth_username=10086@qq.com` /
   `smtp_require_tls: true` / 默认+critical 双路由 `receiver: 'email'` / `- to: 2067063203@qq.com` /
   auth_password 非空（未回显）。容器 healthy。
3. `POST /api/v2/alerts` 测试告警 → **200**；Prometheus 真实告警 `JudgeDeadTasksPresent`
   （critical）经 10s group_wait 触发 email 投递——**路由与触发链路验证通过**。

## 阻塞点（非链路缺陷）

QQ SMTP 对 AUTH 持续返回 **535 Login fail**（Account is abnormal / service is not open /
password is incorrect / frequency limited / system busy），12+ 次重试跨 3 分钟全部 535——
**非瞬态**。AM 侧日志摘录见 `am-535-auth-fail.log`。

判定：AM 容器已实际到达 smtp.qq.com 并收到 SMTP 层认证拒绝 → 网络通路无问题，
问题在**发件账号 SMTP 服务未开启或授权码失效/有误**。

## 止血与根因

19:25 将 AM 双路由临时回退 `null`（避免无效重试加重 QQ 频控）；`.env` 凭据保留。

**根因（19:36 确认）**：授权码实际属于 **2067063203@qq.com**，而最初 `SMTP_FROM`/`SMTP_AUTH_USERNAME`
误填 10086@qq.com → QQ 535。按用户指示切换发件账号为 2067063203@qq.com（同一授权码）后：
19:38 recreate AM（email 路由恢复）→ 注入测试告警 200 → **80s 观察窗零错误行**（对照此前 15s 内必现 535）
→ **`alertmanager_notifications_total{integration="email"} = 1`（成功计数）、
`alerts_received_total` = 2 firing + 1 resolved**。AM 侧投递链路全通。

## 终验结论（19:45 CST）

收件人确认 `2067063203@qq.com` 收到测试/真实告警邮件（主题 `[FIRING] CodeJudge JudgeDeadTasksPresent (2)`）。
**TC-U6-04 PASS，U6 结案**：登记册续登（PASS 22/FAIL 0/BLOCKED 3）、runbook U6 勾选、
第十二轮报告 + CONTEXT.md §5.27 落账。
