# PROMPT-ARCHIVE.md —— CodeJudge 总提示词存档

> 建档日期：2026-09-20 ｜ 状态：**⚠️ 原文缺失，已逆向重建**

---

## ⚠️ 重要声明：原文并未落盘

**结论：总提示词的逐字原文当前不在仓库任何位置，也不在任何记忆中。**

证据（已实际检索）：
- 全仓 `grep` 关键词「项目生成提示词」→ **0 命中**。
- `docs/PLAN.md` 全文仅 **6 处**提及「提示词」，且**全部**是「提示词假设 vs 底座实况」的对照引用，**无原文段落**。
- `conversation_search`（跨会话历史检索）→ 返回的 2 条结果均为 zx-learn 上线前清理相关，**无法检索到本条提示词**（该提示词属于当前对话，检索工具对当前会话零可见）。

**为什么会这样**：原提示词是在对话中一次性传入的长文本，当时的产出物是 `docs/PLAN.md`（规划书），它是提示词的**加工产物**而非原文副本。原文从未被要求单独存档，因此随会话摘要而丢失。

### 若要补齐原文 —— 只需一步

在**任意新对话**里把它再贴一次（一句 `@long-text: ...` 即可），我会：

1. **原样**粘贴到下方 §A（逐字，不做任何改写）；
2. 更新 `docs/CONTEXT.md` §2 的必读索引指向 §A；
3. 核对重建版 §B 与原文的差异，把偏差记录到 §C。

在此之前，**下方 §B 是唯一可用的需求依据**，其权威性来自 `docs/PLAN.md`（已实读底座源码后产出）与 `docs/P1-REPORT.md`。

---

## §A 原文（逐字）

> **【待回填】** 此处预留总提示词原文位置。
> 回填前，请以 §B 为准；§B 覆盖了原文中全部**可执行的需求条款**。

```text
（原文缺失 —— 将原始提示词粘贴到此处即可永久存档）
```

---

## §B 需求条款重建（自 `docs/PLAN.md` 逆向建档）

以下每一条都能在 `docs/PLAN.md` 或 `docs/P1-REPORT.md` 中找到出处，**不是我新编的**。标注【推定】的条目为从多条规定反推出的意图，置信度高但非逐字。

### B1. 项目本体
- 项目名 **CodeJudge**，定位**分布式在线编程评测平台**（判题 / 竞赛 / AI 代码点评）。
- **基于 `D:\1\zx-learn` 底座改造**，不修改 zx-learn 原仓库。
- **不带入**课程 / 订单 / 优惠券 / 学情 / 秒杀等教育业务代码。

### B2. 命名与规范（强制）
- 包名 `com.zx.*` → `com.codejudge.*`（**该假设有误**，实为 `com.zhixing.*`，见 §C-1）。
- 服务名 `zx-*` → `judge-*`，`spring.application.name` 同步。
- **零硬编码**：所有密码 / JWT 密钥 / LLM Key 走环境变量（`ZX_*` → `CJ_*`）。
- 保留底座代码风格：`R<T>`、`CommonException`+`ErrorCode`、`requestId` 透传、MyBatis-Plus、Knife4j、雪花 ID。
- MQ Topic 采用 `judge.submission.created` 点号命名（**该假设有误**，见 §C-6）。

### B3. 模块与端口
- 目标模块：gateway / auth / user / problem / submission / worker / contest / ai / web，加 common / api 两个共享模块 + sandbox。
- 端口整体上移到 9xxx 段避让 zx-learn（详见 `docs/PLAN.md` §2.2）。
- 判题机**多实例**（9085 / 9185 / 9285）。
- 新增组件：**MinIO**（底座中不存在）。
- 服务发现：沿用底座，**本地默认关闭 Nacos**。

### B4. 数据库
- 6 库：`judge_auth` / `judge_user` / `judge_problem` / `judge_submission` / `judge_contest` / `judge_ai`。
- 账号密码位置**存在两种方案**（决策 D1）：方案 A 沿用底座放 `user` 表（推荐、已采用）；方案 B 在 auth 库新建 `account` 表（提示词原案，未采用）。
- 提交幂等以 **DB 唯一索引**为基石：`uniq(user_id, problem_id, contest_id, code_hash, submit_round)`。
- 首个管理员凭据**不在 SQL 硬编码**，沿用 `.bootstrap-credentials` 安全引导 + 首登强制改密。

### B5. 沙箱（12 项隔离要求）
独立命名空间与 tmpfs workdir、非 root、只读根文件系统、`--network none`、CPU/内存/PID 限额、超时强杀→TLE、OOM→MLE、非零退出→RE、编译失败→CE、`no-new-privileges` + 自定义 seccomp、输出上限截断、用例逐跑 + `SE` 区分沙箱自身异常。
- 支持 **4 语言镜像**：`judge-java21` / `judge-python3.12` / `judge-gcc13` / `judge-go1.22`。
- **7 项安全测试必过**：无限循环、读 `/etc/passwd`、开 socket、fork 炸弹、输出爆炸、内存爆炸、`Runtime.exec` 逃逸。
- 运行时 Docker / gVisor 均可（**该假设需修正**，见 §C-8）。

### B6. 判题结论
六种 verdict：**AC / WA / TLE / MLE / RE / CE**（另有 `SE` 表示沙箱自身异常）。

### B7. 竞赛与排行榜
- 规则支持 **ACM / IOI** 两种；ACM 罚时默认 20 分钟/次。
- Redis ZSet 实时榜、**封榜**（公开榜冻结、后台继续记录、解封后合并）、快照。
- 排行榜 score 编码：`passed_count * 10^7 + (10^7 - 1 - penaltySeconds)`，三段排序单 ZSet 完成。
  - ⚠️ **2026-09-20 P4 实现时修正**：上述公式只有两段，权重与罚时都相同时无法再区分，
    即"同分按最后 AC 时间"这一条**用该公式表达不出来**（会退化成按 userId 字符串排序）。
    P4 按"同分规则"要求补上第三关键字，改为
    `权重 × 10^12 + (999999 − 罚时秒) × 10^6 + (999999 − 末次通过偏移秒)`。
    详见 `docs/PLAN.md` §4.2 与 `docs/P4-REPORT.md`。

### B8. AI 点评
- LLM 代码点评 + **RAG 检索** + **SSE 流式**返回。
- pgvector `vector(1024)` + HNSW（与底座 `embedding-3` 对齐）。
- **LLM 未配置时优雅降级**（`enabled=false` 不报 500）。

### B9. 交付物
- `sql/init.sql`（建库建表 + 种子：≥5 题覆盖六种结论、2 场竞赛）、`docker-compose.yml`、`.env.example`、`scripts/`（启动 / 构建镜像 / 冒烟）、`perf-test/`（JMeter）、`docs/` 全套。

### B10. 工作方式（**最关键的一条**）
> **先输出规划、不写代码 → 用户确认 → 再分阶段逐模块生成代码。**
> 每阶段只产出一个**可编译、可运行、可自测**的增量，附验收命令；**上一阶段验收不过不进下一阶段**。

---

## §C 提示词中被修正的 8 处假设（原文 vs 底座实况）

> 这 8 条是**原文中确凿存在的内容**（因为 PLAN.md 是对照它们写的），照抄会直接编译失败。已全部修正落地。

| # | 原文假设 | 底座实况（已实读源码核实） | 处置 |
|---|---|---|---|
| 1 | 包名 `com.zx.*` | 实为 **`com.zhixing.*`**（groupId 同） | 替换规则改 `com.zhixing→com.codejudge` |
| 2 | 统一异常 `BizException` | **该类不存在**，实为 `CommonException`+`ErrorCode` 家族（9 类） | 原样复用，不新造 |
| 3 | 分页 `PageResult` | 实为 **`PageDTO` + `PageQuery`** | 复用 |
| 4 | 权限表 `permission` | 实体是 **`Privilege`**（`privilege`/`role_privilege`/`menu`/`role_menu`/`account_role`/`login_record`） | 沿用 |
| 5 | `judge_auth.account` 表 | `zx_auth` **无 `account` 表**，账号密码在 `zx_user.user`（BCrypt），登录经 Feign `UserClient` | 走决策 D1 方案 A |
| 6 | Topic `judge.submission.created` | 底座规范是**下划线前缀 + Tag 大写**，且是**手写 `rocketmq-client` 4.9 封装** | 改 `judge_submission` + Tag `CREATED/RETRY/RESULT` |
| 7 | RocketMQ 只需避让 namesrv 到 9877 | **broker 10909/10911/10912 会直接冲突** | 追加避让 broker → 10919/10921/10922 |
| 8 | 沙箱 Docker / gVisor 均可 | 本机 Docker Desktop 29.7.2 **仅 runc，无 runsc(gVisor)** | 默认加固 runc + seccomp，gVisor 作可选 |

---

## §D 验收标准（原文 8 条 → 阶段映射）

| # | 验收标准 | 阶段 | 验证方式 |
|---|---|---|---|
| 1 | `mvn clean install -DskipTests` 成功 | P1 | 命令行 |
| 2 | `docker compose up -d` 启动全部基础设施 | P1 | 健康检查 + 端口探测 |
| 3 | 最小链路可启动（gateway/auth/user/problem/submission/worker） | P2–P3 | `scripts/dev-start-backend.sh` |
| 4 | 登录→建题→提交→判题→WS 收结果 | P3–P4 | `scripts/verify-core-chain.sh` |
| 5 | 沙箱拦截无限循环/读文件/开网络/fork 炸弹 | P3 | 7 项安全用例脚本 |
| 6 | 排行榜实时更新 + 封榜 | P4 | 并发提交 + 封榜/解封断言 |
| 7 | AI 点评 SSE 流式返回 | P5 | `curl -N` 观察增量输出 |
| 8 | README 中 curl 可直接验证核心链路 | P6 | 逐条实跑 |

---

## §E 阶段计划（6 阶段）

P1 底座+骨架 → P2 用户/题目 → P3 提交/MQ/worker/沙箱 → P4 WebSocket/竞赛/排行榜 → P5 AI 点评+RAG+SSE → P6 前端+可观测+压测+文档。

各阶段硬验收标准见 `docs/PLAN.md` §7 与 `docs/CONTEXT.md` §4。

---

## §F 维护约定

1. 本文件是**需求单一起点**。任何需求变更（含用户口头追加的）都应回写到 §B，并注明日期。
2. §A 一旦回填原文，§B 降级为「条款索引」，冲突时**以 §A 原文为准**，但 §C 的 8 处修正**始终优先**（因为原文那 8 处是错的）。
3. 不要在别处复制本文件内容 —— 只做单向引用，避免多副本漂移。
