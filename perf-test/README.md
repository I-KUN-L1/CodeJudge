# CodeJudge 压测方案

> JMeter 5.6.3 ｜ 目标：在**本机单机**环境下测出各核心接口的吞吐与延迟基线，
> 并用它作为告警阈值的标定依据（见 `deploy/monitoring/prometheus/rules/`）。
>
> ⚠ 本文档中的所有"实测值"均来自本机实跑，环境为
> Windows + Docker Desktop（WSL2 后端）单机，服务与压测工具**争抢同一台机器的 CPU**。
> 因此这些数字是**容量下界**，不是生产预期 —— 生产压测必须在独立压测机上执行。

---

## 1. 目录

```
perf-test/
├── seed-users.py            # 播种压测账号池 → csv/users.csv（幂等）
├── run-perf.py              # 无 GUI 运行器 + 指标解析 + 门槛判定 + 墙钟看门狗
├── RUNBOOK.md               # **独立压测机**执行手册（生产容量标定走这份）
├── jmx/
│   ├── smoke.jmx            # 冒烟：全链路正确性（8 个 sampler）
│   ├── load.jmx             # 混合负载：5 个场景线程组 + AI 点评（默认关）
│   └── throughput.jmx       # 恒定负载：无限循环 + 固定时长（**唯一**做吞吐判定）
├── csv/users.csv            # 账号池（运行时生成，含表头）
└── results/                 # .jtl 原始数据 + HTML 报告（运行时生成）
```

---

## 2. 快速开始

```bash
# 前置：后端 8 个服务已启动
python scripts/dev-start-backend.py

# 1) 冒烟（约 1 分钟）
python perf-test/run-perf.py --plan smoke

# 2) 混合负载（约 3-5 分钟，固定工作量模型）
python perf-test/run-perf.py --plan load

# 3) 恒定负载 —— 吞吐门槛只对它判定（默认 60 线程 / 180s 稳态窗口，约 4 分钟）
python perf-test/run-perf.py --plan throughput --no-html

# 4) 自定义并发 / 时长（不改文件）
python perf-test/run-perf.py --plan load -J tgSubmit.threads=40 -J tgBrowse.threads=60
python perf-test/run-perf.py --plan throughput -J tgBrowse.threads=80 -J tgRank.duration=300

# 5) 不生成 HTML 报告（只跑 + 门槛判定；跳过报告目录的批量删除）
python perf-test/run-perf.py --plan load --no-html
```

> ⚠ 测生产容量**不要在业务机上跑**。本文的数字全部来自单机，是容量**下界**；
> 独立压测机的完整流程（准备、爬坡找拐点、观测口径、数据回填阈值）见
> [`RUNBOOK.md`](./RUNBOOK.md)。

脚本会自动播种账号池、跑 JMeter、解析 `.jtl`、按 §4 的门槛判定并给出 PASS/FAIL，
同时输出 HTML 报告路径。

> ⚠ `run-perf.py` 会**删除并重建** `results/<plan>-report/` 目录 ——
> 这是 JMeter 生成 HTML 报告的硬性要求（输出目录必须为空）。

---

## 3. 负载模型

### 3.1 并发参数

| # | 场景 | 线程数 | ramp-up | 循环 | 请求数 | 覆盖接口 |
|---|---|---:|---:|---:|---:|---|
| 01 | 登录鉴权 | 10 | 10s | 20 | 200 | `POST /accounts/login` |
| 02 | 题库浏览 | 30 | 30s | 50 | 3000 | `GET /problems/page`、`GET /problems/{id}` |
| 03 | 提交判题 | 20 | 60s | 10 | 400 | `POST /submissions`、`GET /submissions/{id}` |
| 04 | 提交记录查询 | 20 | 30s | 30 | 1200 | `GET /submissions/page` |
| 05 | 竞赛榜单 | 20 | 30s | 40 | 1600 | `GET /contests/page`、`GET /contests/{id}/rank` |
| 06 | AI 点评 SSE | 5 | 30s | 3 | 30 | `POST /ai/review/stream`（**默认关闭**） |
| | **合计（01-05）** | **100** | **60s** | | **6200** | |

所有参数都是 `${__P(键,默认值)}`，用 `-J键=值` 覆盖，无需维护多份 JMX。

### 3.2 四个设计要点

**① 登录放在 Once Only Controller 里**
每个线程组 = 每线程登录一次，随后整轮均复用该 token。
否则压的是 BCrypt 校验（故意设计得很慢，≈100ms/次），而不是业务接口 ——
会把 P95 从 200ms 拉到 800ms，得到完全失真的结论。

**② 每次提交的代码必须不同**
`03-提交判题` 的代码里插了 `${__UUID()}` 注释：

```python
import sys  # 550e8400-e29b-41d4-a716-446655440000
data = sys.stdin.read().split()
print(sum(int(x) for x in data))
```

不这么做，同一用户 + 同一题目 + 同一代码会命中**幂等快路径**
（Redis key `judge:submission:idempotent:*` + 唯一索引 `uk_submission_idempotent`），
直接返回已有记录 —— 压测就变成了「压幂等查询」，判题链路（落库 + 入队 + 沙箱）
一次都没走到。**这是压测里最容易发生、也最难察觉的假压测。**

**③ 提交场景 ramp-up 刻意拉长到 60s**
判题是重资源操作（起 Docker 容器 + 编译 + 多用例运行）。瞬时打满会把队列冲垮，
测出来的数字只说明「队列崩了」，不能反映提交接口本身的容量。

**④ 06 AI 点评默认关闭**
每次调用真实消耗 LLM 额度并产生 10-60s 的长连接，混进主跑会污染其它场景的延迟统计。
需要时单独开：

```bash
python perf-test/run-perf.py --plan load -J tgAi.enabled=true \
    -J reviewSubmissionId=<一个已判完成的提交 id>
```

### 3.3 压测前必须放宽登录限流（否则数据必假）

网关对 `POST /accounts/login` 配了防爆破令牌桶，key = `rate:login:{ip}:anon` ——
**压测机只有一个出口 IP，所有登录线程共用同一个令牌桶**，默认 2 req/s、突发 5。
不改配置就跑：

- `01-登录鉴权` 线程组的数据全是 429，错误率远超 1% 门槛；
- 且**提高线程数完全无效** —— 吞吐被令牌桶钳死在 2 req/s。

放宽（仅压测环境）：

```bash
GW_LOGIN_RATE_REPLENISH=500 GW_LOGIN_RATE_BURST=1000 \
    python scripts/dev-start-backend.py judge-gateway --wait
```

默认值保持不变，生产环境无需也不应放宽。`seed-users.py` 也已内置 429 退避重试，
所以**账号播种**在默认限流下也能跑通，只是慢一些。

### 3.4 断言策略

每个 sampler 挂三类断言，缺一不可：

| 断言 | 内容 | 为什么必须 |
|---|---|---|
| HTTP 状态码 | `= 200` | 网络/网关层故障 |
| **业务码** | 响应体含 `"code":200` | HTTP 200 但 `R.fail` 的情况（权限拒绝、幂等冲突、参数非法）**不会被状态码断言发现** |
| 时长 | `< 1500~3000ms` | 硬失败阈值，防止个别极端慢请求污染整体统计 |

> ⚠ 时长断言是**硬失败**，会计入错误率。因此阈值（1500/3000ms）刻意留了远高于目标
> （300/800ms）的余量 —— 它的作用是「抓住异常」，不是「衡量 P95」。

### 3.5 throughput.jmx：恒定负载模型（吞吐只能用这个测）

`throughput.jmx` 与另两份计划的**模型**不同，不能混用：

| 维度 | `load.jmx` | `throughput.jmx` |
|---|---|---|
| 模型 | **固定工作量**（循环次数定死，跑完即止） | **恒定负载**（无限循环 + 固定时长） |
| 关键属性 | `LoopController.loops = N` | `loops = -1` + `continue_forever=true` + `scheduler=true` + `duration` |
| 观测量 | 给定工作量下的延迟分位数 | **稳态吞吐**（= 并发数 / 平均响应时间） |
| 能否判吞吐 | **不能** —— 吞吐上限 ≈ 总请求数 / ramp 时长，测到的是计划模型的限制 | 能，这是唯一成立的场景 |

两个线程组（默认 **40 题库浏览 + 20 榜单记录 = 60 线程 / 180s**）：

| # | 线程组 | 默认线程 | 覆盖接口 |
|---|---|---:|---|
| 01 | 题库浏览（恒定负载） | 40 | `GET /problems/page`、`GET /problems/{id}` |
| 02 | 榜单与记录（恒定负载） | 20 | `GET /contests/{id}/rank`、`GET /submissions/page` |

> ⚠ **计划里刻意不含 `POST /submissions`**。判题是重资源操作，恒定负载下无限提交会把
> 判题队列堆爆，而队列积压**不进 HTTP 吞吐统计** —— 结果是"吞吐很好看，队列已经崩了"。
> 提交相关容量看 `judge_queue_backlog` / 队列消费速率，见 §4 末尾的说明。

**看门狗**：`run-perf.py` 会给 JMeter 设一个墙钟上限
（`max(各线程组 duration) + 180s`，可用 `CJ_PERF_MAX_WALL` 覆盖），超时**连子进程一起**强制终止。
它的存在是因为一个真实事故：`scheduler` 写成了 `${__P(...)}`（见 §7 第 8 条），
`duration` 形同虚设，配 180s 的计划跑了 13 分钟、226 万样本、`.jtl` 涨到 400MB 仍不停止。

---

## 4. 预期指标与门槛

`run-perf.py` 内置以下门槛，任一不达标即退出码 1。

| 场景 | 指标 | 目标 | 依据 |
|---|---|---|---|
| `POST /accounts/login` | P95 | **< 500ms** | BCrypt cost=10 约 100ms，加网关 + Feign 查库 |
| `GET /problems/page` | P95 | **< 300ms** | 单表分页 + 标签批量填充 |
| `GET /problems/{id}` | P95 | **< 300ms** | 主表 + 版本表读取 |
| `POST /submissions` | P95 | **< 800ms** | 幂等检查 + 双通道投递（不含判题） |
| `GET /submissions/page` | P95 | **< 400ms** | 用户维度过滤分页 |
| `GET /contests/{id}/rank` | P95 | **< 300ms** | Redis ZSet Range 查询 |
| 整体 | 吞吐 | **≥ 500 req/s** | @ 100 并发 |
| 整体 | 错误率 | **< 1%** | — |

> ⚠ **提交接口的目标里不含判题耗时**。判题是异步的：`POST /submissions` 只做
> 「幂等检查 + 落库 + 入队」就返回。判题本身由 `judge-worker` 在沙箱里跑，
> 其耗时是秒级的，观察口径是 `judge_queue_backlog` 与队列消费速率，不是 HTTP 延迟。

> ⚠ 门槛是**目标**，不是承诺。实测值见 [`../docs/PERF.md`](../docs/PERF.md)；
> 若实测低于目标，应先在文档里记为未达标并分析原因，而不是调低门槛 ——
> 门槛的作用就是暴露问题。

---

## 5. 结果解读

`run-perf.py` 输出的关键列：

| 列 | 含义 | 异常信号 |
|---|---|---|
| `样本` | 该 sampler 完成的请求数 | 远小于「线程数 × 循环」→ 有线程提前退出 |
| `错误` / `错误率` | 断言失败数 | 非 0 先看 `<plan>-jmeter.log` 的断言消息 |
| `平均ms` | 算术平均 | **不要用它判断性能** —— 会被长尾严重拉偏 |
| `P50/P95/P99` | 分位数 | P95 与 P50 差距 > 3 倍 → 长尾问题（GC / 锁 / 冷启动） |
| `max` | 最大值 | 远大于 P99 → 个别极慢请求，优先查首次请求（JIT 预热） |
| `req/s` | 该 sampler 吞吐 | — |

### 常见异常与归因

| 现象 | 可能原因 |
|---|---|
| 全部 sampler 401/403 | 账号池未生成或 token 提取失败；先跑 `seed-users.py` |
| `GET /problems/page` 返回空 list | 传了 `page=1` 而非 `pageNo=1`（参数名写错会静默用默认值） |
| 提交接口 P95 极低但队列暴涨 | 正常 —— 提交是异步的；去 Grafana「判题链路」看积压 |
| 后期样本错误率上升 | 判题沙箱容器堆积 / 磁盘或内存见顶；查 `docker ps -a` 与宿主资源 |
| `POST /accounts/login` 出现 429 | 触发网关令牌桶限流（2 req/s，突发 5）—— **这是设计行为，不是缺陷**。压测前按 §3.4 放宽，否则登录场景吞吐恒为 2 req/s |

---

## 6. 上手 JMeter GUI

两个 JMX 都可直接用 GUI 打开：

```bash
D:\1\jmeter\bin\jmeter.bat -t perf-test/jmx/load.jmx
```

但**不要用 GUI 跑正式压测** —— GUI 自身的渲染开销会显著影响结果（通常低估吞吐 20%+）。
GUI 只用来调试脚本结构与断言。

---

## 7. 扩展新场景

1. 在 `jmx/load.jmx` 里复制一个 `<ThreadGroup>` 块（连同其后的 `<hashTree>`）；
2. 改线程数/ramp/循环为 `${__P(tgXxx.threads,默认值)}` 形式；
3. 加 sampler 与断言（业务码断言用 `ResponseAssertion`，test_field=`Assertion.response_data`，
   pattern 写 `&quot;code&quot;:200`，test_type=2）；
4. 在 `run-perf.py` 的 `THRESHOLDS` 里加一行门槛。

> ⚠ 编辑 JMX 时注意：**XML 注释里不能出现 `--`**（如 `--plan`），
> 属性值里不能出现裸 `<`（要写 `&lt;`）。这两条会让 JMeter 直接拒绝加载整个文件。

### 已知陷阱（都已实测，别再踩一遍）

| # | 陷阱 | 后果 | 规避 |
|---|---|---|---|
| 1 | `<boolProp name="ThreadGroup.scheduler">${__P(...)}</boolProp>` | JMeter 的 **boolProp 不做函数替换**，整串被 `Boolean.parseBoolean()` 判成 `false` → 「`loops=-1` 无限循环 + 调度器关闭」= **永不停止**。实测：配 180s 的计划跑了 13 分钟、226 万样本、`.jtl` 400MB 仍不停 | `scheduler` 必须写**字面量** `true`；时长仍可用 `-J tgXxx.duration=N` 覆盖 |
| 2 | 用 `load` 计划判吞吐门槛 | 恒假 FAIL —— 测到的是计划模型的限制，不是系统容量 | 用 `throughput` |
| 3 | JMeter 的 JVM 是启动脚本的**子进程** | 只 kill 父进程会留下**继续压测**的孤儿 java | `run-perf.py` 的看门狗用 `taskkill /T` 整树杀；手工处理时也要带 `/T` |
| 4 | 提交代码里不带随机串 | 命中幂等快路径，判题链路一次没走到 | JMX 内置 `${__UUID()}` 注释，勿删 |
| 5 | 未放宽登录限流 | 登录场景吞吐恒 2 req/s | §3.3 |

> 更完整的压测陷阱清单（含 Prometheus 抓取自身污染 P95 等）见 [`RUNBOOK.md`](./RUNBOOK.md) §8。
