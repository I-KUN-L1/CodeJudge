# CodeJudge SLO / 错误预算（上线检查清单 D5）

> 状态：**v1 定义稿**。数值基于 2026-09 实测容量数据（`docs/PERF.md`）：
> 3108.8 req/s / P95≤139ms @60 线程（压测机与被测同机，属容量下界）；判题端到端 3.4–4.5s（主路径）。
> 上线后 30 天用真实流量复核一次并修订。

## 1. 服务级目标（SLI → SLO）

| # | SLI | SLO | 度量来源 | 错误预算/月 |
|---|---|---|---|---|
| S1 | 平台可用性（网关 5xx 比率 + 健康检查失败） | **99.9%**（月度） | Prometheus `http_server_requests{uri!~"/actuator.*"}` 5xx 计数 / 总计数 | 43.2 分钟 |
| S2 | 提交受理 P99 | **< 200ms**（不含判题） | `http_server_requests{uri="/submissions",method="POST"}` 直方图 P99 | 每月违规次数 ≤ 3 次×5min 窗口 |
| S3 | 判题端到端 P99 | **< 10s**（提交成功 → 终态 verdict 落库） | submission→result 时间差（`judge_task.create_time` vs `judge_result` 写入），由指标 `cj_judge_e2e_seconds` 暴露 | 同上 |
| S4 | 判题正确率（非 SE 终态占比） | **≥ 99.5%**（SE = 判题机自身故障） | verdict 分布（SE 计数 / 总终态） | 违规即触发 P1 级复盘 |
| S5 | SSE/WebSocket 连接建立成功率 | **≥ 99.9%** | 网关 ws/sse 路由握手失败计数 | 43.2 分钟 |
| S6 | 数据持久性（提交记录/判题结果） | **零丢失**（RPO ≤ 5min） | 备份校验 + binlog/checkpoint 检查 | 违规 = 事故 |

## 2. 判题吞吐容量目标

| 指标 | 目标 | 实测（2026-09） |
|---|---|---|
| 受理吞吐 | ≥ 500 req/s | 3108.8 req/s（6.2×） |
| 判题吞吐（单机单实例） | — | 0.94–1.00 题/s；**加实例不涨**（瓶颈=每题 5 容器启停） |
| 扩容方向 | — | 减少每题容器数 / 跨宿主多实例（须配 `CJ_MQ_CONSUME_THREADS`） |

## 3. 错误预算策略

- 预算剩余 > 50%：正常迭代。
- 预算剩余 25%–50%：非紧急变更冻结，仅修缺陷。
- 预算剩余 < 25%：全部变更冻结，专项复盘（谁打破的、为什么、防复发）。
- 预算耗尽：只允许可靠性投入；恢复条件 = SLO 连续 7 天达标。

## 4. 告警与 SLO 的映射（详见 deploy/monitoring/alertmanager/alert.rules.yml）

| SLO | 对应告警规则 | 级别 |
|---|---|---|
| S1 | PlatformErrorBudgetBurn | critical |
| S2/S3 | JudgeE2ELatencyP99Breach | warning |
| S4 | JudgeSystemErrorSpike | critical |
| 队列积压 | JudgeQueueBacklog | warning |
| 死信 | JudgeDeadLetter | critical |
| worker 掉线 | WorkerOffline | critical |

## 5. 度量落地状态

- [x] Prometheus 直方图（`percentiles-histogram http.server.requests`）已在 8 服务启用
- [x] 告警规则文件落盘（D1）
- [ ] `cj_judge_e2e_seconds` 业务指标暴露（S3 度量需 worker/submission 埋点，**TODO**）
- [ ] 错误预算看板（Grafana，**TODO**，复用 `gen_dashboards.py`）
