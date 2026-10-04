# 2026-10-04 U4 第一批：Playwright E2E 冒烟 证据索引

| 文件 | 来源 | 说明 |
|---|---|---|
| `playwright-smoke.log` | `cd judge-web && node_modules\.bin\playwright.cmd test --reporter=list` 全录 | 2 条冒烟链路（登录→落地 /problems；题库列表有数据）**2 passed (5.1s)**，exit=0 |

## 复跑方式
`cd judge-web && npm run test:e2e`（serial 模式 + storageState 复用，规避登录限流 2 req/s burst 5）。
凭据 `13900000001/123456` 与 `scripts/verify-p1-login.py` 同源（种子学员）。
用例：`judge-web/e2e/smoke.spec.js`；配置：`judge-web/playwright.config.js`。

## 关联
- 代码提交：6c85b04（@playwright/test 落 devDependencies，报告产物入 gitignore）
- HTML 报告本机留存（gitignore 内）：`judge-web/playwright-report/`
- 剩余链路（渐进补）：选题→编辑→提交→看判题结果→查榜单→AI 点评，见 `docs/HANDOFF-PROMPT-2026-10-04.md` U4 节
