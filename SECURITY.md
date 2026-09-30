# 安全策略（SECURITY.md）

## 报告漏洞

**不要**通过公开 Issue / PR 报告安全漏洞。

- 联系方式：仓库维护者私有渠道（邮件 / 私信；上线前在此处填入安全联系人邮箱）。
- 请包含：影响版本/commit、复现步骤、影响评估、（如可行）修复建议。
- 我们会在 **72 小时内**确认收到，**7 天内**给出评估结论。

## 处置时限（自确认之日起）

| 严重级别（CVSS 3.1） | 修复目标 | 备注 |
|---|---|---|
| Critical（≥9.0） | 72 小时 | 立即热修 + 回顾 |
| High（7.0–8.9） | 7 天 | |
| Medium（4.0–6.9） | 30 天 | 随版本发布 |
| Low（<4.0） | 下个版本 | |

## 披露政策

- 修复发布后，我们会按贡献者意愿在 CHANGELOG 致谢并披露细节（保留缓冲期 ≥ 30 天）。
- 在修复发布前，请勿公开披露或利用。

## 当前安全基线（2026-09）

- 认证：单登录入口 + 双 Token（access/refresh 分离、refresh 带 type claim）+ 登录限流
- 授权：能力码 fail-closed（未知 user.type → 空集）；归属校验 `OwnerAccessGuard` / `InternalOnlyGuard`
- 沙箱：非 root + 只读根 + 禁网 + cgroups + seccomp（12 项隔离，`verify-p3.py` 回归）
- 密钥：零硬编码（`CJ_*` env），判据 `scripts/check-hardcoded-defaults.py`；凭据轮换 `scripts/rotate-credentials.py`
- 扫描：trivy（沙箱镜像 + 依赖 fs 扫描）、CI 接入 osv-scanner（报告期）
- 已知遗留项与处置见 `docs/LAUNCH-READINESS.md` §G
