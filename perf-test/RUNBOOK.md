# 生产压测执行手册（独立压测机）

> 适用场景：上线前在**真实容量规划**下测出系统吞吐与延迟，并用它标定告警阈值。
>
> 本机（业务服务 + JMeter 同机）跑出来的数字见 [`../docs/PERF.md`](../docs/PERF.md)，
> 那**只是容量下界**：压测工具本身吃掉一整颗 CPU，测到的是"机器还剩多少"，不是"系统能扛多少"。
> 本文是**唯一**能给出容量结论的执行方式。

---

## 0. 为什么必须换一台机器

同机压测有三个无法通过"多跑几次"消除的偏差：

| 偏差 | 表现 | 为什么致命 |
|---|---|---|
| CPU 争抢 | JMeter 线程与服务 JVM 抢核 | 吞吐被工具税压低，**低估**真实容量 |
| 网络栈零成本 | loopback 不经过真实网卡与交换机 | 延迟被**低估**（省掉了 RTT、丢包重传），生产上必然更慢 |
| 内存压力 | 8 个 JVM + JMeter + Docker 共享物理内存 | 更早触发 GC 与 swap，长尾被**放大** |

三者方向不一致（有的高估有的低估），所以**不能靠"打个折"来修正** —— 只能换机器重测。

---

## 1. 压测机要求

| 项 | 要求 | 说明 |
|---|---|---|
| CPU | ≥ 4 物理核，且**不低于**被测网关机器的核数 | 压测机先成为瓶颈时，得到的是压测机上界 |
| 内存 | ≥ 8 GB 可用 | JMeter 默认堆 1 GB，实测 40+20 线程足够；超 200 线程需调 `HEAP` |
| 网络 | 与被测环境**同可用区 / 同交换机**，RTT < 1 ms | 跨公网测出来的延迟不可用于标定 |
| 工具 | JMeter 5.6.3（`D:\1\jmeter`） | 版本需与 `README.md` 记录一致，否则指标口径会漂 |
| 独占 | 跑测期间**不跑其它任务** | 包括浏览器、编辑器索引、系统更新 |

> ⚠ 压测机上的 `perf-test/` 必须与仓库版本一致。JMX 里任何断言/线程组的改动都会改变结论，
> 拿旧脚本测出来的数字会静默地与文档不符。

---

## 2. 被测环境准备（不可跳过的 6 步）

按顺序执行，任何一步跳过都会让整轮数据作废。

### 2.1 服务全量启动并确认健康

```bash
python scripts/dev-start-backend.py --wait
```

8 个服务（网关 9080、auth 9081、user 9082、problem 9083、submission 9084、
worker 9085、contest 9086、ai 9087）全部 `UP` 后再继续。

### 2.2 放宽登录限流（否则登录场景吞吐恒为 2 req/s）

网关对 `POST /accounts/login` 有防爆破令牌桶（默认 2 req/s、突发 5），
key = `rate:login:{ip}:anon`；压测机**只有一个出口 IP**，所有登录线程共用一个桶。

```bash
GW_LOGIN_RATE_REPLENISH=500 GW_LOGIN_RATE_BURST=1000 \
    python scripts/dev-start-backend.py judge-gateway --wait
```

生产默认值不动；这里是压测环境的临时放宽。

### 2.3 播种账号池

```bash
CJ_PERF_GATEWAY=http://<被测网关IP>:9080 python perf-test/seed-users.py
```

脚本对 429 做退避重试，所以即便忘了 §2.2 也能跑通，只是慢。

### 2.4 清空判题积压

压测会往队列里灌提交，**上一轮的残留会让本轮从"队列已满"起跑**：

```bash
# 队列水位（应为接近 0）
curl -s http://<网关IP>:9080/actuator/prometheus | grep judge_queue_backlog
# 在途/死信任务
python scripts/ops-dead-tasks.py list
```

未消化的任务先等它跑完（观察 `judge_queue_backlog` 归零），不要直接开测。

### 2.5 记录基线（否则事后无法归因）

开测前把这三组数记下来，写进 §7 的记录表：

```bash
# ① 判题机在线数（应为预期实例数，不多不少）
curl -s http://<网关IP>:9080/actuator/prometheus | grep judge_workers_online
# ② 宿主/容器资源空闲水位
docker stats --no-stream
# ③ 上一轮的标定值（对比用）
grep -n "@tune" deploy/monitoring/prometheus/rules/codejudge-alerts.yml
```

### 2.6 预热（JIT + 连接池 + 页面缓存）

**第一次请求必然慢**（JIT 解释执行、HikariCP 建连、MyBatis 预编译）。
不预热就测，`max` 列会出现 3–5 秒的尖峰，把 P99 带飞。

```bash
CJ_PERF_HOST=<网关IP> python perf-test/run-perf.py --plan smoke
```

---

## 3. 执行顺序

三轮，顺序不可颠倒 —— 前一轮的结论决定后一轮的参数。

```bash
cd <压测机上的仓库路径>

# ① 冒烟：验证链路与断言都正确（约 1 分钟，不做吞吐判定）
CJ_PERF_HOST=<网关IP> python perf-test/run-perf.py --plan smoke

# ② 恒定负载：**唯一**做吞吐判定的计划（默认 180s 稳态窗口）
CJ_PERF_HOST=<网关IP> python perf-test/run-perf.py --plan throughput

# ③ 混合负载：业务场景分布下的延迟分位数（固定工作量模型，只报数不判吞吐）
CJ_PERF_HOST=<网关IP> python perf-test/run-perf.py --plan load
```

**为什么必须用 `throughput` 判吞吐**（而不是 `load`）：

- `load.jmx` 是**固定工作量**模型 —— 总请求数由循环数定死，跑完即止。
  实测已证：工作量 ×2.77 → 吞吐 ×2.74、延迟不劣化，说明测到的是**计划模型的限制**，
  不是系统容量（详见 `docs/PERF.md` §3.2）。用它判 500 req/s 会得到恒假的 FAIL。
- `throughput.jmx` 是**恒定负载**模型 —— 无限循环 + 固定 180s 时长，
  稳态吞吐 = 并发数 / 平均响应时间，这才是吞吐门槛唯一成立的场景。

`throughput` 计划默认 **40（题库浏览）+ 20（榜单）= 60 线程、180s**，可用 `-J` 覆盖：

```bash
CJ_PERF_HOST=<网关IP> python perf-test/run-perf.py --plan throughput \
    -J tgBrowse.threads=80 -J tgRank.threads=40 -J tgBrowse.duration=300
```

> ⚠ `throughput.jmx` **故意不含 `POST /submissions`**。
> 判题是重资源操作，恒定负载下无限提交会把判题队列堆爆，
> 而队列不参与 HTTP 吞吐统计 —— 结果是"吞吐很好看，队列已经崩了"。
> 提交相关容量见 §5 的队列指标。

### 3.2 长稳（soak）—— 探内存泄漏 / 连接耗尽 / 慢查询累积

```bash
# 默认 12（浏览）+ 6（榜单）= 18 线程、3600s，低负载长跑
CJ_PERF_HOST=<网关IP> python perf-test/run-perf.py --plan soak --no-html
```

- **目的不是容量**，是验证"跑得久不劣化"：P95 / 错误率仍按统一门槛判 PASS/FAIL，
  吞吐只报数（低并发下吞吐天然低于水位，判 500 req/s 是门槛误用）。
- 由 `throughput.jmx` 派生，同样**不含提交**（长跑下更不能无限提交）。
- 观测配套（缺一不可，否则 soak 白跑）：
  1. Prometheus 看 `jvm_memory_used_bytes` 各服务曲线 —— 1h 内锯齿应回到同一水位；
  2. `hikaricp_connections_active` / `pending` —— 连接池不持续增长；
  3. `judge_queue_backlog` —— 结束后归零；
  4. 压测后立刻跑 §5.3 的"静默损坏"确认。
- 首次执行建议先 `-J tgBrowse.duration=600` 跑 10 分钟验证链路，再放全量 1h。

---

## 4. 容量爬坡（找拐点，本轮新增方法）

单点测一个数字说明不了容量；要回答"能扛多少"，必须**画曲线找拐点**。

并发从低到高跑 5 档，每档 180s：

| 档 | 并发（`tgBrowse.threads`+`tgRank.threads`） | 目的 |
|---|---|---|
| 1 | 20 + 10 = 30 | 确认线性区（吞吐应随并发近似线性增长） |
| 2 | 40 + 20 = 60 | 参考点（= `throughput` 默认） |
| 3 | 80 + 40 = 120 | 接近拐点 |
| 4 | 120 + 60 = 180 | 跨过拐点 |
| 5 | 200 + 100 = 300 | 确认拐点后的劣化斜率 |

```bash
for cfg in "20 10" "40 20" "80 40" "120 60" "200 100"; do
  set -- $cfg
  CJ_PERF_HOST=<网关IP> python perf-test/run-perf.py --plan throughput \
      -J tgBrowse.threads=$1 -J tgRank.threads=$2 --no-html
done
```

**拐点判据**（三条同时成立才叫拐点，否则只是噪声）：

1. 吞吐不再增长：`TPS(n+1) / TPS(n) < 1.1`；
2. P95 显著劣化：`P95(n+1) > 1.5 × P95(n)`；
3. 错误率抬头：`err(n+1) > 1%`（或出现 429/5xx）。

**推荐容量 = 拐点并发的 60%**。留 40% 不是因为保守，而是因为：
压测是**均匀随机**流量，生产有突发尖峰；且压测期间判题队列是空的，
生产高峰时同一批 CPU 还要跑沙箱。

> ⚠ 爬坡时必须盯 `judge_workers_online` 不变。worker 掉线会让吞吐曲线出现假拐点。

---

## 5. 观测口径（压测机侧 vs 服务侧）

**只看 JMeter 的数字会漏掉最关键的一半** —— 异步链路（判题）的容量不在 HTTP 延迟里。

### 5.1 压测机侧（`run-perf.py` 输出）

| 列 | 判读 |
|---|---|
| `样本` | 应 ≈ 并发 × (窗口 / 平均响应时间)；远低于估算 → 线程提前退出 |
| `P95` | **唯一**用于门槛的指标；`平均ms` 会被长尾拉偏，不看 |
| `max` | 远大于 P99 → 查是否首轮未预热，或个别请求踩了锁 |
| `req/s` | `throughput` 计划下即稳态吞吐 |

### 5.2 服务侧（Prometheus，压测的**主判据**）

```promql
# 判题队列水位 —— 稳态下应平稳，持续增长说明 worker 吞吐不足
judge_queue_backlog
# 在线判题机数 —— 必须恒定，掉了说明 worker 崩了
judge_workers_online
# 死信任务数 —— 非 0 立即停下查日志
judge_dead_tasks
# 业务口径延迟（**必须排除 /actuator 与 SSE**，否则抓取自身会污染 P95）
histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{uri!~"/actuator.*|/ai/review/stream.*"}[5m])))
# 连接池等待 —— Hikari pending > 0 说明 DB 连接成为瓶颈
hikari_connections_pending
```

> ⚠ 第 4 条的两个 `uri!~` 过滤条件不是可选优化。
> Prometheus 每 15s 抓一次 `/actuator/prometheus`，这些抓取**同样被计入
> `http_server_requests`**；不过滤会让业务 P95 虚高 10 倍以上（实测）。

### 5.3 压测结束后立刻确认"没有静默损坏"

```bash
python scripts/ops-dead-tasks.py list          # 死信应为 0
curl -s http://<网关IP>:9080/actuator/prometheus | grep judge_dead_tasks
```

---

## 6. 数据回填：实测值 → 告警阈值

**阈值来自实测，不来自感觉。** 拿到 §4 的曲线后：

```bash
# 1) 从 Prometheus 拉实测分位数（已排除 /actuator 与 SSE），按余量系数推导新阈值
#    先预演，确认它对得上再落盘
python scripts/recalibrate-alerts.py

# 2) 确认无误后写入规则文件（脚本按 "# @tune <name> <当前值>" 锚点精确定位替换；
#    锚点缺失会直接报错，不会静默跳过）
python scripts/recalibrate-alerts.py --write

# 3) 校验语法并热加载
promtool check rules deploy/monitoring/prometheus/rules/codejudge-alerts.yml
curl -X POST http://localhost:9090/-/reload
```

每轮重标都要在规则文件顶部「阈值标定记录」里补一行：
**日期 / 数据来源（哪台机器、什么并发、多长窗口）/ 改动项 / 理由**。
没有出处的阈值等于没有阈值。

重标后回写文档：

- `docs/PERF.md` —— 追加一节「生产压测实测（独立压测机）」，与同机下界并列，不要覆盖；
- `perf-test/README.md` §4 的目标值若被实测证明偏松/偏紧，改目标并在表格「依据」列写清依据。

---

## 7. 记录模板

每轮压测复制一份填，归档到 `perf-test/results/`。

```
压测轮次：<日期> 第 N 轮
压测机：<CPU 核数> / <内存> / 与网关 RTT <x> ms / JMeter 5.6.3
被测环境：<部署方式> / 网关 <IP> / worker 实例数 <n>
账号池：<N> 个（seed-users.py 生成时间 <...>）
前置确认：[ ] 8 服务 UP  [ ] 限流已放宽  [ ] 队列已清零  [ ] 已预热 smoke

负载参数：tgBrowse.threads=<n> tgRank.threads=<n> duration=<n>s
实测结果：
  | 指标 | 值 | 门槛 | 判定 |
  |---|---|---|---|
  | 整体吞吐 | ___ req/s | ≥ 500 | |
  | P95 /problems/page | ___ ms | ≤ 300 | |
  | P95 /contests/{id}/rank | ___ ms | ≤ 300 | |
  | 错误率 | ___ % | < 1 | |
服务侧观测：
  judge_queue_backlog 峰值 ___ ｜ 末值 ___ ｜ judge_workers_online ___ ｜ judge_dead_tasks ___
拐点：第 __ 档（并发 ____）｜ 推荐容量 ___ req/s

结论：______
遗留问题：______
```

---

## 8. 陷阱清单（每条都实际踩过或确切推演过）

| # | 陷阱 | 后果 | 规避 |
|---|---|---|---|
| 1 | 未设 `CJ_PERF_HOST` | 打压测机自己：连接被拒或"快得不真实" | 开跑前 `echo $CJ_PERF_HOST` |
| 2 | 未放宽登录限流 | 登录场景吞吐恒 2 req/s，错误率爆表 | §2.2 |
| 3 | 提交代码相同 | 命中幂等快路径，**判题链路一次没走到** | JMX 内置 `${__UUID()}` 注释，勿删 |
| 4 | 未清空队列就开测 | 从"已积压"起跑，吞吐被上游拖累 | §2.4 |
| 5 | 未预热 | `max` 出现秒级尖峰，P99 失真 | §2.6 先跑 smoke |
| 6 | 用 `load` 判吞吐 | 恒假 FAIL（测的是计划模型限制） | 用 `throughput` |
| 7 | 用 `throughput` 灌提交 | 判题队列堆爆，而 HTTP 吞吐照样好看 | `throughput.jmx` 已刻意不含提交 |
| 8 | Prometheus 未排除 `/actuator` | 业务 P95 虚高 10 倍以上 | §5.2 的两个 `uri!~` |
| 9 | JMX 注释里写 `--` | JMeter 拒绝加载**整个**文件 | 注释不写连续减号 |
| 10 | 爬坡时 worker 掉线 | 曲线出现假拐点 | 盯 `judge_workers_online` |
| 11 | 压测机与业务同机 | 数据只是容量下界 | 本文存在的全部理由 |
| 12 | 只在低并发测一次 | 单点数字无法回答"能扛多少" | §4 爬坡找拐点 |

---

## 9. 与其它文档的关系

| 文档 | 内容 |
|---|---|
| [`README.md`](./README.md) | 压测方案的**设计与实现**（负载模型、断言策略、JMX 扩展方式） |
| 本文 | 压测的**执行流程**（独立压测机、爬坡、观测、回填） |
| [`../docs/PERF.md`](../docs/PERF.md) | **实测数据**与同机下界的差异分析 |
| [`../docs/DEPLOYMENT.md`](../docs/DEPLOYMENT.md) §7 | 上线检查清单中的压测项 |
