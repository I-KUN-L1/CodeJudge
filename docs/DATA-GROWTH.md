# 数据增长治理方案（上线检查清单 C3）

> 状态：**方案已定稿 + 现状取证**；分区/归档属 DDL 行为变更，实施窗口放上线后第一次数据增长预警时。
> 现状取证日期：2026-09-30（命令见 §4，数字以当日实跑为准）。

## 1. 增长画像（写入路径决定）

| 表 | 写入频率 | 行大小 | 增长预测（1000 活跃学员） |
|---|---|---|---|
| `submission` | 每次提交 1 行（高频） | 小（代码在 compile_info/MinIO） | ~2 万行/日，700 万行/年 |
| `judge_task` | 每次判题 1 行 | 小 | 同 submission 量级 |
| `judge_result` | 每用例 1 行（4×提交） | 中（含 stdout/stderr 摘要） | ~8 万行/日，**增长最快** |
| `compile_info` | 编译失败 1 行 | 大（完整编译输出） | 低频但行大 |
| `login_record` | 每次登录 1 行 | 小 | ~5 千行/日 |
| `contest_rank_snapshot` | 每场竞赛封榜/终榜 | 中 | 极低频 |

## 2. 方案（按表分级）

### 2.1 `submission` —— 月分区（实施窗口：上线后首个月度维护窗）

```sql
-- 思路：按 submit_time RANGE 月分区。MySQL 分区键必须落在主键/唯一键内，
-- 本表主键为雪花 id，需把主键改为 (id, submit_time) 组合主键后按月 RANGE 分区。
ALTER TABLE submission
  DROP PRIMARY KEY,
  ADD PRIMARY KEY (id, submit_time),
  PARTITION BY RANGE (TO_DAYS(submit_time)) (
    PARTITION p202609 VALUES LESS THAN (TO_DAYS('2026-10-01')),
    PARTITION p202610 VALUES LESS THAN (TO_DAYS('2026-11-01')),
    PARTITION pmax    VALUES LESS THAN MAXVALUE
  );
-- 每月维护：REORGANIZE PARTITION pmax 拆出新月分区（可由事件调度 automate）。
```

- 查询侧收益：提交记录页/排行榜按时间窗查询天然分区裁剪；
- **前置条件**：所有按 `id` 的等值查询不受影响（组合主键最左前缀仍是 id）；
- 上线前不动 DDL（当前数据量小，分区收益为负——见 §4 实测行数）。

### 2.2 `judge_result` / `compile_info` —— 冷热分离 + 归档

- **热**：保留 90 天内全量（在线查询）；
- **冷**：90 天前 `judge_result` 的 stdout/stderr 大字段压缩归档到 MinIO（`codejudge-archive` 桶），
  行内字段置 NULL + 归档标记；`compile_info` 整行按月导出 parquet/SQL 转储后删行；
- 归档作业用 XXL-JOB/调度脚本实现（当前仓库无调度器，先用运维 cron + `scripts/`）。

### 2.3 提交记录保留期

- 默认 **1 年**：1 年前 submission 及其派生（judge_task/judge_result/compile_info）归档后删除；
- 竞赛数据（contest_rank_snapshot）永久保留（体量极小，审计价值高）；
- 用户申诉场景：归档可恢复（MinIO 对象保留策略 2 年）。

### 2.4 `login_record`

- 保留 180 天，按月清理（安全审计窗口通常 90-180 天）。

## 3. 容量红线（触发治理动作的阈值）

| 指标 | 预警 | 行动 |
|---|---|---|
| `judge_submission` 库体积 | 5 GB | 启动 2.1 分区实施 |
| `judge_result` 行数 | 1000 万 | 启动 2.2 归档 |
| MySQL 磁盘占用 | 70% | 全面归档 + 评估扩容 |

## 4. 现状取证（上线时点基线）

```bash
docker exec codejudge-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -e \
  "SELECT table_schema, table_name, table_rows, \
          ROUND(data_length/1024/1024,1) AS data_mb, \
          ROUND(index_length/1024/1024,1) AS idx_mb \
   FROM information_schema.tables \
   WHERE table_schema LIKE \"judge_%\" ORDER BY table_schema, table_name;"'
```

> 结论随时间变化，以最近一次实跑输出为准（本轮实跑结果见 2026-09-30 工作日志）。
