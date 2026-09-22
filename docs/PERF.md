# CodeJudge 压测实测报告

> 数据来源：**本机真实执行**，非估算。运行日期 2026-09-21。
> 负载模型与门槛定义见 [`../perf-test/README.md`](../perf-test/README.md)。

---

## 一、测试环境（结论的适用范围必读）

| 项 | 值 |
|---|---|
| 宿主 | Windows + Docker Desktop（WSL2 后端），**单机** |
| 规模 | 8 个 Spring Boot 服务（9080–9087）**与 JMeter 抢同一台机器的 CPU** |
| 中间件 | MySQL 3307 / Redis 6380 / PostgreSQL 5433 / RocketMQ（4 容器） |
| 监控栈 | Prometheus 2.54 + Grafana 11.3 + Alertmanager 0.27（3 容器） |
| JMeter | 5.6.3，**无 GUI 模式**（`-n`），CSV 格式 `.jtl` + 自行解析 |
| 账号池 | 120 个独立学员账号（`seed-users.py` 播种） |
| 网关登录限流 | **已放宽**（`GW_LOGIN_RATE_REPLENISH=500` / `GW_LOGIN_RATE_BURST=1000`） |

⚠️ **三条必须理解的限制**：

1. **压测工具与业务服务同机** —— 这是**容量下界**，不是生产预期。生产压测必须在独立压测机执行。
2. **放宽登录限流是压测前置条件** —— 默认 2 req/s 的令牌桶与压测机单一出口 IP 组合，
   会把登录吞吐钳死在 2 req/s（详见 `perf-test/README.md` §3.3）。默认值本身不变。
3. **submit 场景不含判题耗时** —— `POST /submissions` 是异步入口（幂等检查 + 落库 + 入队即返回），
   判题由沙箱在 `judge-worker` 侧秒级完成，其观察口径是 `judge_queue_backlog`，不是 HTTP 延迟。

---

## 二、冒烟（`--plan smoke`）—— 全链路正确性

1 线程组 / 8 个 sampler / 5 线程，**用于确认链路与断言正确，不用于评估容量**。

| sampler | 样本 | 错误 | 平均ms | P50 | P95 | P99 | max |
|---|---:|---:|---:|---:|---:|---:|---:|
| POST /accounts/login | 5 | 0 | 98.4 | 85 | **149.2** | 161.8 | 165 |
| POST /submissions | 10 | 0 | 33.2 | 31.5 | **47.7** | 56.8 | 59 |
| GET /problems/page | 10 | 0 | 21.0 | 16.5 | **42.5** | 57.3 | 61 |
| GET /problems/{id} | 10 | 0 | 15.2 | 14.5 | **19.6** | 20.7 | 21 |
| GET /submissions/page | 10 | 0 | 11.6 | 11.5 | **13.5** | 13.9 | 14 |
| GET /submissions/{id} | 40 | 0 | 18.9 | 19 | **21.0** | 22.0 | 22 |
| GET /contests/page | 10 | 0 | 18.8 | 19 | **23.2** | 24.6 | 25 |
| GET /contests/{id}/rank | 10 | 0 | 20.2 | 19 | **25.7** | 27.6 | 28 |
| **合计** | **105** | **0** | | | | | |

有效压测窗口 20.4s。**8/8 项 P95 与错误率达标，0 错误。**

> 冒烟不做吞吐判定 —— 它是 5 线程的正确性计划，吞吐天然是个位数，
> 拿 500 req/s 去判它属门槛误用（此前会稳定输出一条假 FAIL，已修）。

---

## 三、混合负载（`--plan load`）—— 100 并发主跑

5 个有效线程组（AI 点评默认关闭），合计 **5890 个请求**，有效窗口 **57.4s**。

| sampler | 样本 | 错误 | 错误率 | 平均ms | P50 | P95 | P99 | max | req/s |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| POST /accounts/login | 290 | 0 | 0.000% | 93.9 | 84 | **146.0** | 174.1 | 199 | 5.1 |
| POST /submissions | 200 | 0 | 0.000% | 39.3 | 29 | **91.0** | 110.0 | 121 | 3.5 |
| GET /problems/page | 1500 | 0 | 0.000% | 11.0 | 9 | **22.0** | 34.0 | 73 | 26.1 |
| GET /problems/{id} | 1500 | 0 | 0.000% | 10.8 | 9 | **22.0** | 35.0 | 86 | 26.1 |
| GET /submissions/page | 600 | 0 | 0.000% | 8.4 | 7 | **16.0** | 22.0 | 47 | 10.5 |
| GET /submissions/{id} | 200 | 0 | 0.000% | 16.3 | 13 | **32.1** | 43.0 | 49 | 3.5 |
| GET /contests/page | 800 | 0 | 0.000% | 13.8 | 11 | **29.0** | 43.0 | 57 | 13.9 |
| GET /contests/{id}/rank | 800 | 0 | 0.000% | 22.9 | 14 | **60.0** | 84.0 | 150 | 13.9 |
| **合计** | **5890** | **0** | **0.000%** | | | | | | **102.7** |

### 3.1 门槛判定

| 场景 | 指标 | 目标 | 实测 | 结论 |
|---|---|---|---|---|
| POST /accounts/login | P95 | < 500ms | 146.0ms | ✅ 达标（余量 3.4×） |
| GET /problems/page | P95 | < 300ms | 22.0ms | ✅ 达标（余量 13.6×） |
| GET /problems/{id} | P95 | < 300ms | 22.0ms | ✅ 达标（余量 13.6×） |
| POST /submissions | P95 | < 800ms | 91.0ms | ✅ 达标（余量 8.8×） |
| GET /submissions/page | P95 | < 400ms | 16.0ms | ✅ 达标（余量 25×） |
| GET /contests/{id}/rank | P95 | < 300ms | 60.0ms | ✅ 达标（余量 5×） |
| GET /submissions/{id} | P95 | < 400ms | 32.1ms | ✅ 达标 |
| GET /contests/page | P95 | < 300ms | 29.0ms | ✅ 达标 |
| 整体 | 错误率 | < 1% | **0.000%** | ✅ 达标（5890 样本零错误） |
| 整体 | 吞吐 | ≥ 500 req/s | **102.7 req/s** | ❌ **未达标** |

### 3.2 吞吐未达标 —— 归因分析

**结论：这不是系统容量问题，是负载模型与门槛不匹配。** 依据如下。

**依据 1（算术）**：本计划的**总工作量是固定的**（由 loop 数决定，约 5890 个请求），
运行在 loop 跑完即结束。而各线程组的 ramp-up 合计 60s，因此
**观测吞吐的算术上限 ≈ 总工作量 / ramp 时长 = 5890 / 60 ≈ 98 req/s**。
实测 102.7 req/s 已在该上限附近 —— 也就是说，**无论服务多快，这个计划都跑不出 500 req/s**。

**依据 2（实测：工作量放大探针）**。命令：

```bash
python perf-test/run-perf.py --plan load --no-html \
    -J tgBrowse.loops=150 -J tgHistory.loops=90 -J tgRank.loops=120
```

只放大**只读**场景的工作量（submit 保持 10 轮，避免把判题队列冲爆），结果：

| 指标 | 标准跑（×1 工作量） | 探针（×2.77 工作量） | 变化 |
|---|---:|---:|---|
| 总请求数 | 5890 | **16290** | ×2.77 |
| 有效窗口 | 57.4s | 57.9s | 基本不变 |
| **观测吞吐** | **102.7 req/s** | **281.4 req/s** | **×2.74** |
| 错误数 | 0 | **0** | — |
| 最差 P95（登录） | 146.0ms | 136.6ms | 未劣化 |
| `GET /problems/page` P95 | 22.0ms | 22.0ms | 不变 |
| `GET /contests/{id}/rank` P95 | 60.0ms | 77.0ms | +28% |
| `POST /submissions` P95 | 91.0ms | 110.9ms | +22% |

**吞吐随工作量近似线性上升（×2.74），而延迟几乎没有劣化、错误率仍为 0。**
这就是「计划是工作量受限、不是系统受限」的决定性证据 ——
若真到了系统容量边界，放大工作量只会让吞吐**停滞**且 P95 急剧抬升。

**依据 3（延迟余量）**：所有 sampler 的 P95 距门槛还有 3.4× ~ 25× 的余量，
且 P95 与 P50 的比值全部 < 4（无长尾失控）。

> 顺带得到一个**可外推的容量下界**：在 100 并发下，只读接口实测吞吐已到 281 req/s
> 且延迟仍处低位，说明本机 HTTP 层容量远高于 500 req/s（受限于计划模型而未能测满）。

### 3.3 真正的瓶颈在判题机，不在 HTTP 层

压测期间通过 `judge_queue_backlog` 观察到（这是在 HTTP 数字里**完全看不到**的事实）：

| 时刻 | 积压 | 说明 |
|---|---:|---|
| 压测中 | 131 → 158 → **170** | 200 次提交在 ~1 分钟内涌入 |
| 压测后 0s | 150 | |
| 压测后 10s | 142 | |
| 压测后 30s | 111 | |
| 压测后 50s | **88** | |

**单实例 `judge-worker` 的判题吞吐约 1.5 题/秒**（起容器 + 编译 + 多用例运行），
而 `POST /submissions` 可以轻松接受数十倍于此的到达速率 ——
**提交接口是异步的，它不背判题的锅，积压全部由队列吸收**。

三条结论：

1. **平台扩容的第一优先级是判题机实例数，不是 Web 服务副本数。**
   `judge-worker` 已支持多实例（9085/9185/9285），水平扩展即是线性扩容。
2. `JudgeQueueBacklogHigh > 200` 这条告警阈值**定得贴近真实**：20 线程 × 10 轮提交
   就能把积压推到约 170。这类积压**在 HTTP 指标上完全不可见**（用户看到的是「排队中」），
   正是这条告警存在的意义。
3. 积压能稳定回落，说明消费链路无泄漏；配合 `queueReconcileScan` 对账，
   不会出现 P6 修复前那种「只增不减」的虚增。

### 3.4 正确的吞吐度量方式

500 req/s 这类**恒定吞吐目标**需要**恒定负载模型**，而当前 `load.jmx` 是**固定工作量模型**。
二者不可互换。要测真实容量上限，需要下列任一种：

1. 给 `load.jmx` 增加一个**基于时长的线程组**（`ThreadGroup.scheduler=true` + `duration`，
   或 Constant Throughput Timer），让负载持续跑满 3–5 分钟再统计；
2. 使用 `-J tgXxx.loops=<大值>` 把工作量放大到远超 ramp，使观测值收敛到真实容量上限；
3. 用开环压测工具（wrk / k6 / Gatling）直接施加恒定 RPS。

**在补齐上述任一项之前，`run-perf.py` 的 500 req/s 门槛对 `load` 计划同样不成立**，
应视为「待重新标定」而非「系统不达标」。本节如实记录未达标，
按 `perf-test/README.md` §4 的约定：**不在没有依据的情况下调低门槛**。

---

### 3.5 恒定负载实测（`--plan throughput`）—— 吞吐门槛已达标（2026-09-21）

§3.4 的第 1 种方案已落地：`perf-test/jmx/throughput.jmx` 是**恒定负载**计划
（无限循环 + 固定时长 + 两个线程组：40 题库浏览 / 20 榜单记录 = 60 线程）。

| sampler | 样本 | 错误 | 平均ms | P50 | P95 | P99 | max | req/s |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| `GET /contests/{id}/rank` | 198 586 | 0 | 12.4 | 12 | **16** | 20 | 127 | 1 104.3 |
| `GET /submissions/page` | 198 576 | 0 | 5.0 | 5 | **8** | 10 | 114 | 1 104.3 |
| `GET /problems/page` | 80 914 | 0 | 37.1 | 35 | **62** | 79 | 158 | 450.0 |
| `GET /problems/{id}` | 80 899 | 0 | 49.1 | 47 | **82** | 102 | 207 | 449.9 |
| `POST /accounts/login` | 60 | 0 | 112.3 | 111 | **139** | 147 | 151 | 0.3 |
| **合计** | **559 035** | **0** | — | — | — | — | — | **3 108.8** |

- 稳态窗口 **179.8s**；JMeter 进程自行在 **183.9s** 结束（时长调度生效）。
- **整体吞吐 3 108.8 req/s vs 门槛 500 req/s → PASS，余量 6.2×**
- **错误率 0.000%**；最长单请求 207ms，无长尾。
- 8/8 响应时间门槛全部达标（`POST /submissions` 本计划不含，故 SKIP —— 见 §3.3）。

**关键推论**：60 线程跑出 3 108.8 req/s ⇒ 单线程约 51.8 req/s（平均响应 19.3ms）。
Web 层（网关 + 只读接口）**离饱和还很远**，与 §3.3 的结论一致：
**真正的上限在判题机（单实例约 1.5 题/秒），不在 HTTP 层。**
扩容优先级 = 判题机实例数 > Web 副本数。

> ⚠️ 这仍是**同机**数字（压测工具与服务争抢 CPU、走 loopback 无网络成本），
> 只能作**容量下界**。生产容量标定必须在独立压测机做，流程见 `perf-test/RUNBOOK.md`。

> ⚠️ 本轮实测踩到一个**会让整轮数据作废**的坑并已修复：`ThreadGroup.scheduler` 曾写成
> `${__P(...)}`，而 JMeter 的 `<boolProp>` **不做函数替换**，整串被判成 `false` ——
> 「无限循环 + 调度器关闭」= 永不停止。该计划配 180s 实际跑了 13 分钟、226 万样本、
> `.jtl` 涨到 400MB 才被手工 kill。现已改为字面量 `true`，并给 `run-perf.py` 加了
> 墙钟看门狗（超时整树杀）。详见 `perf-test/README.md` §7 陷阱表与 §3.5。

---

## 四、告警阈值与实测的对应关系

`deploy/monitoring/prometheus/rules/codejudge-alerts.yml` 的 11 条阈值取自本报告：

| 告警 | 当前阈值 | 与实测的关系 |
|---|---|---|
| `CodeJudgeHighLatencyP95` | 业务 P95 > **0.3s** | 恒定负载下最慢 P95 = 82ms（`/problems/{id}`）、登录 139ms → **余量 2.2×（相对登录）**。已修正口径：排除 `/actuator` 与 SSE |
| `HikariPoolSaturation` | 平均获取连接耗时 > **0.2s** `or` acquire 超时 > 0 | 健康态实测 7.6ms（judge-problem）→ 余量 26×。**判据已换**：原 `pending > 5` 被证伪（见下） |
| `JvmHeapHigh` | 堆 > 85% 持续 10m | 未触发 |
| `JudgeQueueBacklogHigh` | 积压 > 200 | `load` 计划下 200 次提交使积压峰值 **170**（单 worker ≈1.5 题/s）→ 阈值贴近真实，可用 |
| `CodeJudgeHighErrorRate` | 5xx > 1% | 实测 **0.000%**（559 035 样本） |
| `CodeJudgeSubmitFailureBurst` | 提交 5xx > 0.5/s | 过滤条件已收紧为 `method="POST", uri="/submissions"`（原 `uri=~"/submissions.*"` 会混入只读列表页） |

#### ⚠️ 本轮实测推翻了一条阈值：`HikariPoolSaturation`

原判据 `hikaricp_connections_pending > 5` 在**健康状态**下就会触发：

| 观测 | 值 | 说明 |
|---|---|---|
| 持续负载 | ≈2 900 req/s（13 分钟） | 失控压测那轮（见 §3.5 注） |
| `hikaricp_connections_pending` 峰值 | **29** | 远大于 5 → 告警真的触发了（`for: 3m`） |
| 同期 P95 / 错误率 | 82ms / **0.000%** | 用户侧完全健康 |
| 同期 `acquire_timeout_total` | **0** | 没有任何请求拿不到连接 |
| 平均获取连接耗时 | 7.6ms | 排队存在，但每次只等 7.6ms |
| 负载停止后 | active 10→0、pending 29→0（数秒内） | **不是泄漏**，池子调度正常 |

结论：`pending` 衡量的是"有没有线程在排队"，而排队是 10 个连接服务 60 个并发线程时的**正常复用行为**。继续用它等于给健康系统报 warning，把真正的故障淹掉。

新判据改用**平均获取连接耗时**（`rate(acquire_seconds_sum) / rate(acquire_seconds_count)`），它直接对应"用户等了多久"；再 `or` 上 acquire 超时次数兜住"已经有人失败"。

> ⚠️ **这些阈值仍只适用于本地基线，上线前必须按真实容量重标**（独立压测机流程见 `perf-test/RUNBOOK.md`）。
> 连接池阈值 0.2s 相对实测 7.6ms 有 26× 余量，仍属"偏钝"，需在生产流量下收紧。
> 重标工具 `scripts/recalibrate-alerts.py` 已有两道守卫：样本稀疏的 URI 不计入延迟基线、
> 无提交流量时不改提交阈值 —— 没有数据就不改，好过凭残缺数据调。

---

## 五、复现命令

```bash
# 前置：基础设施 + 8 服务 + 沙箱镜像已就绪
# ① 压测前必须放宽登录限流（否则登录吞吐恒为 2 req/s）
GW_LOGIN_RATE_REPLENISH=500 GW_LOGIN_RATE_BURST=1000 \
    python scripts/dev-start-backend.py judge-gateway --wait

# ② 账号池（幂等，自动复用）
python perf-test/seed-users.py --count 120

# ③ 冒烟 → 混合负载（固定工作量模型）
python perf-test/run-perf.py --plan smoke
python perf-test/run-perf.py --plan load

# ④ 恒定负载 —— §3.5 的吞吐数据来自这一步（默认 60 线程 / 180s，约 4 分钟）
python perf-test/run-perf.py --plan throughput --no-html

# ⑤ 自定义并发 / 放大工作量 / 跳过 HTML 报告
python perf-test/run-perf.py --plan load -J tgSubmit.threads=40 -J tgBrowse.threads=60
python perf-test/run-perf.py --plan load -J tgBrowse.loops=150 -J tgHistory.loops=90 -J tgRank.loops=120
python perf-test/run-perf.py --plan throughput -J tgBrowse.threads=80 -J tgRank.threads=40
python perf-test/run-perf.py --plan load --no-html
```

> `run-perf.py` 对 JMeter 设有墙钟看门狗（`max(duration)+180s`，可用 `CJ_PERF_MAX_WALL` 覆盖），
> 超时会连同子进程一起终止 —— 避免"计划写错 → 无限压测 → 机器被打满"这种无界失败。

产物：`perf-test/results/load.jtl`（原始）+ `perf-test/results/load-report/index.html`（GUI 报告）。

---

## 六、小结

| 维度 | 结论 |
|---|---|
| 正确性 | **5890 样本零错误**（`load`）+ **559 035 样本零错误**（`throughput`）；判题链路端到端可用（冒烟含真实判题到 `verdict=AC`） |
| 响应时间（`load`） | **8/8 达标**，最差 P95 146ms（登录，含 BCrypt），只读接口 P95 ≤ 60ms |
| 响应时间（`throughput`） | **8/8 达标**（提交类 SKIP），最差 P95 139ms（登录）；`/problems/{id}` 82ms、榜单 16ms、记录列表 8ms |
| 长尾 | P95/P50 比值均 < 4，无长尾失控；`throughput` 单请求 max 仅 207ms |
| 吞吐（`load`） | 标准跑 **102.7 req/s** / 放大工作量探针 **281.4 req/s**（随工作量线性上升）→ 该模型**不能**用来判容量 |
| 吞吐（`throughput`） | **3 108.8 req/s** @ 60 线程 / 180s 稳态窗口，**0 错误** —— 500 req/s 门槛 **PASS，余量 6.2×** |
| 单线程效率 | 51.8 req/s / 线程（平均 19.3ms）→ Web 层离饱和很远 |
| HTTP 层瓶颈 | **未观测到**：延迟余量 3.4×~25×、零错误、放大工作量后延迟未劣化 |
| 判题层瓶颈 | **已观测到**：单实例 `judge-worker` 约 **1.5 题/秒**；200 次提交使积压冲到 170，约 2 分钟排空。**扩容优先级：判题机实例数 > Web 副本数** |
| 告警阈值 | 1 条被实测**推翻并换判据**（`HikariPoolSaturation`）、2 条**修正统计口径**（延迟/错误率排除 `/actuator` 与 SSE）、1 条**收紧过滤条件**（提交 5xx）；全部仍需生产重标 |
| 前端单测 | `judge-web` **136 项全绿**（8 个文件：SSE 解析 / HTTP 拦截器与 401 单飞 / Pinia / 路由守卫 / 格式化 / 组件） |
