# 敏感配置审计与整改报告（2026-09-22）

> 范围：`D:\1\CodeJudge` 全项目 —— `.env` 凭据与运行环境一致性、源码/配置中的硬编码敏感值、模板与配套脚本。
> 原则：**优先以本机系统实际配置为准**；系统未提供者，按该技术栈最推荐、最安全的方式处理。
> 本文档不含任何明文口令。

---

## 一、结论摘要

| 诉求 | 结论 |
|---|---|
| 把系统实际的 MySQL / Redis 密码填入 `.env` | **无需改动** —— 实测三者早已一致（`.env` 本就是容器口令的来源），强行改写反而会让服务连不上 |
| 扫描并整改必须修改的敏感配置 | **发现 6 处，全部修复**；另 1 处（Grafana 容器环境变量滞后）单独修复 |
| 确保项目可直接部署运行 | 11 模块 `BUILD SUCCESS`；8 服务健康；端到端验收与预检复跑无回归 |
| 系统未提供的敏感项 | 告警通道 6 项保留空值 + 说明（详见 §七）；LLM Key 保留现值 + 轮换提示 |

**本轮最有价值的发现不是"哪个密码填错了"，而是三个「静默失效」** —— 都是"不报错、看起来正常，实际已经在用不安全的默认值"：

1. `judge-auth` 的 yml 与 Java **双份**兜底 `123456`（首个管理员口令）
2. `UserService` 的常量兜底 `123456`（重置用户口令）
3. Grafana 容器环境变量停留在旧默认值 `codejudge`，而 `.env` 里已是新值 —— **重建前"以为改过了"**

---

## 二、基础设施凭据核对（.env ↔ 运行环境）

方法：从容器**实际生效处**取值（`Config.Env` 启动命令参数），与 `.env` 逐项比对；再用该凭据做**真实连通性测试**。比对只输出长度与哈希前缀，不回显明文。

| 组件 | `.env` 键 | 容器实际值来源 | 一致性 | 连通性实测 |
|---|---|---|---|---|
| MySQL | `MYSQL_PASSWORD` / `MYSQL_ROOT_PASSWORD` | `Config.Env` | ✅ 一致（len=15） | `.env` 口令 `SELECT 1` → `OK` |
| Redis | `REDIS_PASSWORD` | `Config.Cmd` 的 `--requirepass` | ✅ 一致（len=16） | `.env` 口令 `PING` → `PONG`；无口令 → `NOAUTH` |
| PostgreSQL | `POSTGRES_PASSWORD` | `Config.Env` | ✅ 一致（len=13） | `.env` 口令 `SELECT 1` → `OK` |
| MinIO | `MINIO_ROOT_PASSWORD` | 容器**未创建**（compose 的 `storage` profile） | — 无法比对 | 值已就位，首次启动即生效 |
| Grafana | `GRAFANA_ADMIN_PASSWORD` | `Config.Env` | ❌ **不一致**（`.env` 35 / 容器 9） | 见 §三 |

### 为什么"填进去"在这里的正确动作是"不动"

`.env` 是 MySQL / Redis / PG 容器口令的**唯一来源**（compose 里写的是 `${VAR:?...}`，无默认值）。
即"系统实际配置"与"`.env` 配置"在架构上就是同一个值 —— 它们同时由 `.env` 决定。

> 实测佐证：宿主 shell 里 `MYSQL_PASSWORD` / `REDIS_PASSWORD` **均未设置**，而 yml 中这两项**没有默认值**，
> 服务却能正常连库 ⇒ 只可能来自 `.env`。这条链路是通的，**乱动它才是风险**。

### MinIO 的特别说明

`docker-compose.yml` 中 MinIO 位于 `storage` profile 下（刻意不随 `up -d` 启动，避免镜像拉取失败拖垮整条启动链）。故本机从未创建 MinIO 容器，**也不存在历史口令冲突**：`.env` 的值将直接成为首次启动时的 root 口令。无需改动。

---

## 三、Grafana：`.env` 已是新值，但容器环境变量还是旧的

### 现象与证据

| 探针 | 结果 |
|---|---|
| 容器 `Config.Env` 中 `GF_SECURITY_ADMIN_PASSWORD` | 长度 **9**（= 旧的公开默认值 `codejudge`） |
| `.env` 中 `GRAFANA_ADMIN_PASSWORD` | 长度 **35** |
| 用 `.env` 口令登录 `GET :3001/api/user` | **200** |
| 用 `codejudge` 登录 | **401** |

### 根因

上一轮已通过 `grafana cli admin reset-admin-password` 把 **Grafana DB 内的口令**改成了 `.env` 的值，
但**容器的环境变量**仍是创建时的旧值。环境变量只在**容器创建那一刻**注入一次。

**这条偏差的实际危害**：若将来 `codejudge-grafana-data` 卷被删除重建，Grafana 会用容器环境变量里的
**旧默认口令**初始化管理员 —— 口令悄悄退回 `codejudge`，而 `.env` 里的新值形同虚设，且没有任何报错。

### 修复

```bash
cd deploy/monitoring
docker-compose --env-file ../../.env -f docker-compose.monitoring.yml up -d --force-recreate grafana
```

### 验证

| 项 | 修复后 |
|---|---|
| 容器 `Config.Env` 口令长度 | **35**（= `.env` 长度，`两者一致=True`） |
| `.env` 口令登录 | **200** |
| 旧默认口令 `codejudge` 登录 | **401** |

---

## 四、敏感配置整改清单（6 项）

### 4.1 `judge-auth`：首个管理员口令的两层兜底 → 全部移除

**原状**（两处同时兜底 `123456`）：

```yaml
# judge-auth/src/main/resources/application.yml
init-password: ${CJ_ADMIN_INIT_PASSWORD:123456}
```

```java
// AdminBootstrapService.java
public static final String DEFAULT_INIT_PASSWORD = "123456";
@Value("${cj.admin-bootstrap.init-password:123456}") String initPassword
```

**危害**：漏配 `CJ_ADMIN_INIT_PASSWORD` 时，服务**照常启动、不报任何错**，并在首次引导时创建
一个口令为 `123456` 的管理员账号 —— 一个公开可猜的最高权限账号。

**改动**：

| 位置 | 现值 |
|---|---|
| yml | `init-password: ${CJ_ADMIN_INIT_PASSWORD:}`（空默认） |
| `AdminBootstrapService` | 常量**已删除**；字段重命名为 `configuredInitPassword` |
| 未配置时的行为 | **生成 24 位随机强口令**（CSPRNG + 去混淆字符集），**只写入 `.bootstrap-credentials`** |
| 配置但 < 8 位 | 启动时 WARN（保留可读性检查） |

**为什么是"随机生成"而不是"拒绝启动"**：引导是**自举**动作，无人工介入。若直接拒绝启动，
则一个漏配 `.env` 的全新部署将**完全无法初始化**。随机生成既保证口令不可预测，又不阻断部署 ——
口令通过既有的 `.bootstrap-credentials` 机制交付（该文件首登改密后自动删除）。

**口令不打印到日志**（日志会长期留存），只打印"去哪儿取"。

### 4.2 `judge-user`：重置口令兜底 → 移除并 fail-closed

**原状**：

```java
private static final String FALLBACK_DEFAULT_PASSWORD = "123456";
@Value("${CJ_USER_DEFAULT_PASSWORD:" + FALLBACK_DEFAULT_PASSWORD + "}")
private String defaultPassword;
```

**危害比 4.1 更大**：重置密码是把一个**已知字符串写进别人的账号**。若该字符串可预测，
等于给全站用户留了统一后门 —— 管理员每点一次"重置密码"，就多一个 `123456` 账号。

**改动**：常量删除；未配置时 `resetPassword` **抛 `BizIllegalException` 返回 400**，
错误信息明确指向"请在 `.env` 配置 `CJ_USER_DEFAULT_PASSWORD` 后重启"。

**为什么这里选择 fail-closed 而非随机生成**：重置是**人工触发**的运维动作，操作者需要知道
"该告诉用户什么口令"。随机生成则必须改动接口返回类型（`R<Void>` → `R<String>`）与前端展示，
改动面大且易漏；而"明确报错"符合 fail-closed 原则，代价只是管理员点一次就看到原因。
两者的取舍依据是**信息流向不同**，不是随意选择。

### 4.3 `deploy/monitoring/docker-compose.monitoring.yml`：Grafana 口令改为 fail-closed

**原状**：`${GRAFANA_ADMIN_PASSWORD:-<字面量默认口令>}`。

**危害**：漏写 `--env-file ../../.env` 时**静默退回**该字面量 —— Grafana 照常启动、
healthcheck 照样绿、没有任何报错，"监控栈起来了"这件事不再蕴含"口令已加固"。

**改动**：改为 `${GRAFANA_ADMIN_PASSWORD:?请在 .env 中配置 …}`。

**双向实测**：

| 启动方式 | 结果 |
|---|---|
| 带 `--env-file .env` | 正常解析，口令正确注入 |
| **不带** `--env-file` | 明确报错：`required variable GRAFANA_ADMIN_PASSWORD is missing a value: 请在 .env 中配置…` |

> ⚠️ **踩过的坑**：`scripts/preflight-check.py` 的 `_compose_default()` 对整文件做正则匹配、
> **不跳过注释行**。若在注释里原样写出旧的 `:-默认值` 表达式，会被当成真实默认值，**反而掩盖真实状态**。
> 本次注释已刻意回避该写法。

### 4.4 `scripts/preflight-check.py`：让 B4 能区分 fail-closed 与解析失败

4.3 的改动会让旧的 B4 判定产生**误导性 WARN**（"解析不到默认值，结论仅供参考"）。
新增 `_compose_is_fail_closed()`，把两种状态分开：

| 配置形态 | 探针结果 | B4 结论 |
|---|---|---|
| fail-closed | 401 | **PASS** —— 并注明"结构上已无默认口令" |
| fail-closed | 200 | **FAIL** —— 明确指出"容器仍是旧默认值，需重建"（附命令） |
| 有默认值 | 200 | FAIL —— `.env` 未覆盖 |
| 解析异常 | — | WARN —— 结论仅供参考 |

### 4.5 `.env.example`：模板里的真实弱口令 → 占位符 + 说明

**原状**：模板中直接写着可用的 `CJ_ADMIN_INIT_PASSWORD=123456` 与 `CJ_USER_DEFAULT_PASSWORD=123456`。

**危害**：模板是"照抄即用"的入口。把可用的弱口令写进模板，等于**诱导**用户直接使用 ——
`cp .env.example .env` 之后项目"能跑"，但管理员口令就是 `123456`。

**改动**：

| 键 | 现值 | 说明 |
|---|---|---|
| `CJ_ADMIN_INIT_PASSWORD` | **空** | 注释说明"留空 = 引导时生成随机强口令并写入凭据文件" |
| `CJ_USER_DEFAULT_PASSWORD` | **空** | 注释说明"留空 = 该接口 fail-closed 返 400" |
| `CJ_LLM_API_KEY` | 空 | 标注为敏感凭据 |
| `GRAFANA_ADMIN_PASSWORD` | 空 | 标注"compose 已 fail-closed，缺值拒绝启动" |

同时补齐了模板**缺失的 17 个键**（`.env` 100 键 → 模板 83 键）：沙箱 CPU/编译期进程数与超时、
seccomp 路径、worker 工作目录、进度推送开关、提交限流、网关登录限流、RAG 4 项、监控栈 3 个端口。
**模板缺项 = 新部署漏配**，这些虽非凭据，但直接影响"能否直接部署运行"。

### 4.6 `scripts/rotate-credentials.py`：两处过时描述

| 位置 | 原文 | 现文 |
|---|---|---|
| 凭据清单注释 | "⚠ 当前 `.env` 未定义，走 compose 默认值 codejudge" | "监控 compose 已 fail-closed，缺值直接拒绝启动" |
| 追加提示 | "此前它只存在于 compose 的默认值（codejudge）中" | 说明 fail-closed 语义 + 重建命令 |

另同步更新了 `sql/init.sql` 中"系统预设密码默认 123456"的注释。

---

## 五、设计原则：静默失效 → 明确行为

本轮所有改动的共同逻辑 —— **消除"不报错但已经是错"的状态**：

| 场景 | 原行为（静默） | 现行为（明确） |
|---|---|---|
| 漏配管理员初始口令 | 静默创建 `123456` 管理员 | 随机生成强口令 + 日志说明来源 |
| 漏配重置口令 | 静默写入 `123456` | 接口返回 400 + 原因 |
| 漏带 `--env-file` | 静默用默认 Grafana 口令 | **拒绝启动** + 中文报错 + 补救命令 |
| 容器环境变量滞后 | 无任何提示 | 预检 B4 显式指出"需重建"，附命令 |

原则：**宁可让问题在启动那一刻炸出来，也不要让它安静地活到生产环境。**

---

## 六、验证证据

| 验证项 | 命令 / 方法 | 结果 |
|---|---|---|
| 全量构建 | `mvn -B -DskipTests clean install` | **11 模块 BUILD SUCCESS** |
| 零硬编码自检 | `python scripts/check-hardcoded-defaults.py` | **FAIL=0 / WARN=0**（整改前为 FAIL=0 / WARN=2） |
| 8 服务健康 | 逐个 `/actuator/health` | **8/8 = 200** |
| Grafana 口令对齐 | 容器 Env vs `.env` + 登录实测 | 长度一致；新口令 **200** / 旧默认 **401** |
| fail-closed 生效性 | 带/不带 `--env-file` 各解析一次 | 正常注入 / **明确拒绝** |
| 凭据零泄漏 | `git grep` 已知口令值 + `check-ignore` | 零命中；`.env` 被忽略且零跟踪 |
| **"未配置→随机生成"分支** | 以空值起独立 `judge-auth` 实例（端口 19081） | 日志输出"将生成 24 位随机强口令并写入 `.bootstrap-credentials`"；实例 7.012s 正常启动；引导按预期跳过（管理员已存在）；**口令未出现在日志中** |
| 端到端验收 | `python scripts/verify-p6.py` | 见 §六.1 |
| 上线前预检 | `python scripts/preflight-check.py` | 见 §六.1 |

> 独立实例验证是必要的：该分支在正常环境**不会被触发**（`.env` 已配置），
> 不实测就只能算"静态审查通过"。实测暴露了一个环境陷阱 —— Git Bash 里直接 `java -jar` 时
> `java.io.tmpdir` 落在 `C:\Windows\` 导致 Tomcat 无法创建临时目录，需显式指定
> `-Djava.io.tmpdir`（`dev-start-backend.py` 已自行处理，手工启动时才需注意）。

### 六.1 端到端复跑结果

| 脚本 | 结果 | 对比本轮开始前 |
|---|---|---|
| `verify-p6.py` | **PASS=98 / FAIL=0** | 持平（中途一度 97/1，根因见 §六.2） |
| `preflight-check.py` | **FAIL=1 / MANUAL=6 / PASS=14 / WARN=4** | **PASS 13→14、WARN 5→4**；FAIL 仍仅 F1 |
| `verify-p1-login.py` | **41 通过 / 0 失败** | 持平 |

**预检改善的两项**：

- **B4 PASS**（结论比此前更精确）：`默认凭据登录被拒（401）；compose 已 fail-closed，结构上不再存在默认口令`
- **E3 由 WARN 转 PASS**：`模板敏感项全为占位符（your-xxx / 空值），无真实密钥泄露`
  —— 即 §4.5 的 `.env.example` 重写直接消除了该项告警

**剩余 4 个 WARN 均为与凭据无关的既有项**：B3（Grafana 匿名访问，本地刻意开启）、
C2（判题机未在线）、D2（沙箱 runtime 用 runc）、E4（告警阈值待生产重标）。

### 六.2 中途出现的回归：`verify-p6` 97/1 及其根因

把 Grafana 改成 fail-closed 后，`verify-p6` 从 98/0 掉到 **97/1**，失败项是 F 段的
`docker compose up -d（监控栈）`。**根因不是 fail-closed 本身**，而是**该脚本启动监控栈时
自己没带 `--env-file`**：

```python
subprocess.run(["docker", "compose", "-f", "docker-compose.monitoring.yml", "up", "-d"], cwd=mon)
```

即：**这个脚本过去能通过，恰恰因为 compose 的默认口令等于容器里的口令** ——
"验收全绿"建立在"默认值仍然有效"之上。这与 §4.3 描述的是同一个病，只是长在了测试代码里。

**处置：修脚本，不退让加固。** 已为 4 处调用点补上 `--env-file`：

| 文件 | 位置 |
|---|---|
| `scripts/verify-p6.py` | F 段启动监控栈（并改用绝对路径，去掉对 cwd 的隐式依赖） |
| `scripts/rotate-credentials.py` | ⑤ Grafana 生效命令 |
| `README.md` | 监控栈启动示例 |
| `docs/DEPLOYMENT.md` | §3.6 第六步 |

> 为什么只有监控栈需要显式 `--env-file`：`docker-compose` 只自动读 **cwd** 下的 `.env`。
> 项目根的 `docker-compose.yml` 与 `.env` 同目录 → 自动读到；
> `deploy/monitoring/` 下没有 `.env` → 必须 `--env-file ../../.env`。

修复后复跑 → **PASS=98 / FAIL=0**。

---

## 七、仍需人工提供 / 决策的项

| # | 项 | 状态 | 说明 |
|---|---|---|---|
| 1 | **告警外发通道** | ⬜ 待提供 | `ALERTMANAGER_CRITICAL_RECEIVER` 仍为 `null`，6 个 SMTP / Webhook 变量全空。**系统未提供任何 SMTP 凭据或机器人 Webhook，无法凭空生成** —— 这是当前唯一的功能性缺口：真出事时没有任何人收到通知（preflight **F1 = FAIL**）。填入任一通道即可转 PASS |
| 2 | **LLM API Key** | ⚠️ 建议轮换 | `.env` 中存有真实 Key（`CJ_LLM_ENABLED=false`，当前未启用）。该值曾出现在本机命令行与对话记录中，**建议在正式启用 AI 点评前到模型服务方后台轮换**，新值直接写入 `.env` 即可 |
| 3 | **生产环境差异项** | ⬜ 部署时处理 | 以下为开发环境取值，生产必须覆盖：`GF_AUTH_ANONYMOUS_ENABLED=true` → `false`；`CORS_ALLOWED_ORIGINS` 改为真实域名；基础设施口令全部重新生成（见 `LAUNCH-READINESS.md`） |

---

## 八、改动文件清单

| 文件 | 改动性质 |
|---|---|
| `.env.example` | 重写：6 处敏感值改占位/空值 + 补齐 17 个缺失键 + 说明 |
| `judge-auth/src/main/resources/application.yml` | 移除 `:123456` 兜底 |
| `judge-auth/.../AdminBootstrapService.java` | 删除弱口令常量；改为随机生成；自检改为报告来源 |
| `judge-user/.../UserService.java` | 删除弱口令常量；重置接口 fail-closed；自检改为报告可用性 |
| `deploy/monitoring/docker-compose.monitoring.yml` | Grafana 口令改 fail-closed + 注释说明 |
| `scripts/preflight-check.py` | 新增 `_compose_is_fail_closed()`；B4 四分支判定 |
| `scripts/verify-p6.py` | F 段补 `--env-file`（**修复 97/1 回归**，见 §六.2） |
| `scripts/rotate-credentials.py` | 2 处过时描述更正 + ⑤ 补 `--env-file` |
| `README.md`、`docs/DEPLOYMENT.md` | 监控栈启动命令补 `--env-file` |
| `sql/init.sql` | 管理员口令来源注释更正 |
| `docs/CONTEXT.md` §5.6 | 两项「新发现」更新为已修复状态 |
| `docs/DEPLOYMENT.md` §7 | 2 个检查项由待办改为已完成 |
| `docs/CONFIG-OPTIMIZATION-2026-09-22.md` | D-1 段落加"同日升级"指引 |

**未改动**：`.env`（本地凭据值本身已正确）；`docker-compose.yml`（MySQL/Redis/PG/MinIO 早已是 fail-closed 形式）。
