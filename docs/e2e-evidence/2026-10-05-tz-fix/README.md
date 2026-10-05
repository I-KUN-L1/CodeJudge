# 2026-10-05 TZ 时钟缺陷修复（执行报告第十一轮 B1）证据索引

## 缺陷与修复

- **缺陷**：compose 仅给 mysql/pg 设 `TZ: Asia/Shanghai`，8 个 Java 服务容器默认 UTC →
  JVM `LocalDateTime.now()` 比宿主 CST 慢 8h → UI 创建的竞赛开赛被推迟 8 小时
  （E2E run-2「竞赛尚未开始」首次实锤，登记于执行报告第十轮 B1）。
- **修复**：`docker-compose.yml` 为 8 个 app 服务（user/auth/problem/submission/worker/contest/ai/gateway）
  的 `environment:` 加 `TZ: Asia/Shanghai` → `docker compose --profile app up -d` 滚动重建
  （不动基础设施容器、无需重编译镜像——TZ 是容器环境变量）。
- **fixture 回切**：`judge-web/e2e/setup-contest.mjs` 从「UTC 墙钟绕过」改回宿主本地时间发窗口，
  并作为 TZ 金丝雀：若服务端时钟回退，E2E T3 会以「竞赛尚未开始」失败。

## 验证链

| 步骤 | 命令/来源 | 结果 |
|---|---|---|
| 容器时区 | `docker exec codejudge-judge-user-1 date`（+contest/gateway） | `2026-10-05 17:06:48 CST`，与宿主 17:06 一致 |
| 业务时钟 | judge-contest 日志生命周期调度 | 时间戳 CST；旧 UTC 窗口竞赛（存库墙钟 10:19）被正确判「已结束」并落 FINAL 快照 |
| E2E 金丝雀 | `npm run test:e2e`（E2E_CONTEST_ID=2107034953829392385，CST 窗口建赛） | `playwright-tz-fix-regression.log`：**5 passed (9.7s)**，T3 以 CST 窗口提交判题 AC |
| 全量回归 | `python scripts/verify-p1-login.py` | `verify-p1-login-43-0.txt`：**43 通过 / 0 失败** |

## 迁移说明（一次性影响）

历史上以 UTC 墙钟存库的竞赛时间（本次 E2E fixture 造的演示赛）在修复后按 CST 语义解释，
会提前 8 小时结束/过期——均为可丢弃的测试资产，生命周期调度已自动推进并解封，无需人工处理。
真实业务数据此前一直由 UI 在 CST 宿主上创建（存的是 CST 墙钟字符串），修复后语义与录入意图一致。
