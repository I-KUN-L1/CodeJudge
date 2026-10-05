# 2026-10-05 U4b：Playwright E2E 剩余链路 证据索引

| 文件 | 来源 | 说明 |
|---|---|---|
| `playwright-u4b.log` | `cd judge-web && npm run test:e2e`（E2E_CONTEST_ID=2107023093806194689） | 5 条链路 **5 passed (12.5s)**，exit=0 |

## 链路覆盖（judge-web/e2e/smoke.spec.js，serial + storageState 全程仅 1 次登录）

1. 登录冒烟（手机号+密码 → 落地题库）
2. 题库列表渲染
3. **提交判题链路**：进入进行中竞赛（已报名态断言）→ 带 contestId 打开题目（"竞赛提交"标记断言）→
   编辑器写入 Java long 版 A+B（样例含 int 溢出用例）→ 提交 → **轮询页面状态到终态**
   （WS 推送为主 + 「刷新状态」REST 兜底，不写死 sleep）→ AC（通过）→ 判题详情逐用例表
4. **AI 点评链路**：判题详情页点「生成 AI 点评」→ SSE 流式正文渲染 → 生成完成态（见下方 429 说明）
5. **榜单联动链路**：竞赛详情实时榜（WS SNAPSHOT）出现「演示学员一 · 过 1 题」= 本次 AC 真实计入 Redis 榜

## 前置与复跑

```text
cd judge-web
node e2e/setup-contest.mjs        # 新建一场「进行中」演示赛 + 学员报名（只新增数据，可重复执行）
$env:E2E_CONTEST_ID=<脚本输出>; npm run test:e2e
```

`setup-contest.mjs` 为什么存在：种子/历史竞赛到跑测时多半已结束、榜单恒空；
`reset-demo-data.py` 是破坏性清洗（本会话未获授权未执行）。E2E 用竞赛按**服务端时钟（UTC）**发窗口。

## 本次跑通过程中发现并处置的 3 个环境/产品事实（重要）

1. **判题沙箱目录在宿主重启后失效**（已修复）：daemon 自启只拉起 worker 等常驻容器，一次性
   `sandbox-work-init`（restart:"no"）不会重跑 → VM 侧 `/cj-sandbox` 被重建为 root 空目录 →
   worker(uid 1001) 建目录 AccessDenied → 编译沙箱故障 SE→死信。
   处置：`docker start codejudge-sandbox-init` 后沙箱恢复（WRITE_OK，后续 AC）。
   **运维规约：每次宿主重启/全栈自启后，判题前必须先重跑该 init 容器。**
2. **JVM 容器与宿主/MySQL 时钟差 8 小时**（产品级缺陷，待用户决策）：
   compose 仅给 mysql/pg 设 `TZ: Asia/Shanghai`，8 个 Java 服务容器默认 UTC。
   judge-contest 用 `LocalDateTime.now()`(UTC) 与竞赛窗口比较 → **UI 创建的竞赛实际开赛被推迟 8h**
   （E2E 首次复现"竞赛尚未开始"）。E2E fixture 改按 UTC 发窗口以对齐服务端，未改动产品代码；
   正确修复（8 服务加 TZ/`-Duser.timezone` + 重部署 + 回归）超出本次授权，登记待办。
3. **上游 LLM（bigmodel）429 限流**：AI 点评链路（SSE/渲染/落库）真实跑通，但正文为
   产品**设计内降级模板**（`流式调用失败，降级为错误提示：429 Too Many Requests .../chat/completions`，
   Embedding 同样 429 → 伪向量 RAG）。测试断言口径=链路可用；「真实 AI 文本」需上游配额恢复
   或更换 API Key 后单独核对。

## 关联

- 代码提交：见 git log（smoke.spec.js 扩展 3 链路 + setup-contest.mjs 新增）
- 上一批冒烟：`docs/e2e-evidence/2026-10-04-smoke/`
- HANDOFF：U4b 结案；U5/U6/U8/U9 待用户资源（见执行报告第十轮）
