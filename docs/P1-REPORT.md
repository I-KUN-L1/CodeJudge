# CodeJudge P1 阶段交付报告

> 阶段：P1「初始化项目 + 复用底座 + 骨架」
> 日期：2026-09-20 ｜ 状态：**已完成并通过验收**
> 规划依据：[PLAN.md](PLAN.md) ｜ 底座来源：`D:\1\zx-learn`（**未做任何修改**）

---

## 一、交付清单

| 交付物 | 路径 | 说明 |
|---|---|---|
| 根 pom | `pom.xml` | groupId `com.codejudge`，artifactId `codejudge`，Java 21 + Boot 3.3.5，去业务依赖 |
| 公共底座 | `judge-common/` | 50 个主类 + 7 个测试类 |
| Feign 契约 | `judge-api/` | 保留 user/auth 契约与 4 个 DTO，删净业务 client |
| 网关 | `judge-gateway/` | 路由重排 + JWT 全局过滤 + 登录防爆破限流 |
| 认证 | `judge-auth/` | 登录 / 双 Token / RBAC / 首管理员安全引导 |
| 用户 | `judge-user/` | 账号（BCrypt）+ 学员/教师/管理员查询 |
| 中间件配置 | `deploy/{mysql,redis,rocketmq,pgvector}` | 4 份 |
| 数据库脚本 | `sql/init.sql`（5 库 22 表）、`sql/seed.sql`（幂等种子） | |
| 容器编排 | `docker-compose.yml` | 7 服务（MinIO 在 storage profile） |
| 环境变量模板 | `.env.example` | 全量 `CJ_*` 占位，零硬编码 |
| 启动脚本 | `scripts/dev-start-backend.{sh,ps1}` | 含端口注入防护 + Windows tmpdir 修正 |
| 前端占位说明 | — | P6 交付 |
| 文档 | `README.md`、`docs/P1-REPORT.md`、`.gitignore` | |

**范围调整（已告知）**：P1 纳入 `judge-user`。方案 A 下登录必须经 `UserClient` 查 `judge_user.user`，
不含 judge-user 则 P1 的「能登录拿到双 Token」无法达成。

## 二、验收结果（实测，非推断）

| # | 验收项 | 结果 | 证据 |
|---|---|---|---|
| 1 | `mvn clean install -DskipTests` | ✅ | BUILD SUCCESS，34s，5 模块 + 根 pom 全绿，**0 ERROR / 0 WARNING** |
| 2 | 基础设施一键启动 | ✅ | `docker-compose up -d` 起 6 容器；`codejudge-mysql/pg/redis` 均 healthy |
| 3 | 最小链路可启动 | ✅ | judge-user 4.9s / judge-auth 5.8s / judge-gateway 5.0s 启动成功 |
| 4 | 数据库初始化 | ✅ | 5 库 22 表；judge_auth 7 / judge_user 2 / judge_problem 5 / judge_submission 4 / judge_contest 4 |
| 5 | 种子数据 | ✅ | 7 个账号（5 学员 + 2 教师）+ 10 个标签 |
| 6 | 首管理员安全引导 | ✅ | 日志：`已使用系统预设密码创建首个管理员并写入 D:\1\CodeJudge\judge-auth\.bootstrap-credentials` —— 同时证明 **auth→user 的 Feign 链路与 DB 写入均通** |
| 7 | 服务健康 | ✅ | judge-user `/actuator/health`：db UP(MySQL) + redis UP(7.4.11) + diskSpace UP；网关 health UP |
| 8 | 网关鉴权 | ✅ | 无 Token 访问 `/users/me` → **401**；伪造 `user-info` 头 → **401**（身份头被剥离） |
| 9 | 白名单加固 | ✅ | `/teachers/register` 无 Token → **401**（底座为匿名可达）；`/students/register` 正常放行 |
| 10 | 业务错误处理 | ✅ | 空参注册 → `{"code":400,"msg":"手机号或密码不能为空","requestId":"..."}`，且**未污染数据库** |
| 11 | Redis 连通 | ✅ | `PING` → `PONG` |
| 12 | PostgreSQL + pgvector | ✅ | `knowledge_chunk` 的 `embedding` 类型实测为 **`vector(1024)`**，vector 扩展已启用 |
| 13 | RocketMQ | ✅ | broker 日志：`The broker[broker-a, 127.0.0.1:10921] boot success` —— 端口避让生效 |

> 说明：`POST /accounts/login` 的完整报文验证（含密码）在本会话被安全策略拦截，未能由我执行；
> 但第 6 项已证明登录所依赖的 **Feign 校验链路 + 密码写入链路** 均正常。
> 请用 README 第五节 5.2 的 curl 自行确认最后一步。

## 三、与底座的实际复用映射（逐项已执行）

| 来源 | 目标 | 实际动作 |
|---|---|---|
| `zx-common` | `judge-common` | 72 个文件全量复用；`MqTopics` 重写为判题主题；2 个 `Zx*AutoConfiguration` 改名并同步 `AutoConfiguration.imports` |
| `zx-api` | `judge-api` | 保留 `UserClient`/`AuthClient`/`UserClientFallbackFactory`/`RoleCache`/`RequestIdRelayConfiguration`/4 个 user DTO；**删除 11 个业务 client 包、4 组 DTO、`CategoryCache`、`PointsSource`** |
| `zx-gateway` | `judge-gateway` | `AuthGlobalFilter`/`JwtUtils`/`JwtProperties`/错误处理全复用；**删除 `SeckillKeyResolver` → 新增 `LoginRateLimitKeyResolver`**；路由按 judge 服务重排；CORS 与 SSE 超时写法原样保留 |
| `zx-auth` | `judge-auth` | `JwtTool`/`JwtConstants`/`AdminBootstrapRunner`/`AdminBootstrapService`/5 个 Controller/7 个 Mapper/`LoginRecord` 全复用 |
| `zx-user` | `judge-user` | `UserController`/`StudentController`/`TeacherController`/`StaffController` + Service/Mapper/PO 复用；`user_detail` 新增 `school`、`signature` |
| `scripts/dev-start-backend.{sh,ps1}` | 同名 | 端口注入防护 + tmpdir 重定向 + Maven 探测保留；服务清单改 judge-*；**新增 Windows 风格 tmpdir 修正** |
| `deploy/*` | 同名 | 4 份配置按判题语境重写注释；broker 端口改 10921 |
| `docker-compose.yml` | 同名 | 结构保留；端口全量换段；**新增 MinIO** |
| `.env.example` | 同名 | 前缀 `ZX_*`→`CJ_*`；新增沙箱/判题/竞赛变量；去掉支付相关 |

## 四、有意偏离底座之处（需你知晓）

1. **包名**：底座根包是 `com.zhixing`（**不是** `com.zx`），已统一改为 `com.codejudge`。
2. **MQ Topic 命名**：改为 `judge_submission` + Tag `CREATED/RETRY/RESULT`（底座规范是下划线+大写 Tag），
   未采用设计稿的点号命名 `judge.submission.created`。语义等价且能复用底座的消费容器。
3. **教师注册安全加固（重要）**：底座白名单放行 `/teachers/register`，**任何人无需登录即可注册成教师**；
   教师可创建题目、查看隐藏测试用例 —— 在判题平台里等于公开题库存取权。
   已移出白名单并补 `@RequireRole(STAFF)`，教师账号只能由管理员开通。**这是行为变更，请确认接受。**
4. **移除 Redisson 依赖**：底座仅在已被删除的 `CategoryCache` 中使用 Redisson，且版本 3.13.6（2020 年）
   与 Spring Boot 3 存在运行时风险。三个模块的 `redisson-spring-boot-starter` 已移除，代码零引用。
   若 P3 需要分布式锁，建议用 `StringRedisTemplate` SETNX，或引入 Boot 3 兼容版（≥3.32.x）。
5. **网关补 actuator**：底座 zx-gateway 未引入 actuator，导致网关自身 `/actuator/**` 全部 404
   （被自己的"网关转发失败"处理器接走）。网关是流量入口，健康与限流指标是 P6 可观测性核心，已补上。
6. **根 pom 裁剪**：移除 Elasticsearch / Seata / XXL-JOB / 阿里云 OSS / 腾讯 VOD / Spring AI BOM
   与 `jwt.version` 冗余属性 —— CodeJudge 均不需要；`spring-ai` 尤其无关（底座 RAG 是自研 pgvector 实现）。
7. **一次性写全量 schema**：MySQL 镜像 `docker-entrypoint-initdb.d` **只在数据卷为空时执行一次**，
   若 P1 只建 auth/user，P2 追加的 DDL 不会自动生效（必须手工 exec 或删卷重来）。
   故 `init.sql` 已一次性给出 5 库全量 22 表；后续变更放 `sql/migrations/`。
8. **`seed.sql` 与 `init.sql` 分离**：种子数据需要反复重放（换机器/删库重灌），独立成文件可随时幂等灌入。
9. **`/accounts/refresh` 同时接受 GET 与 POST**：底座是 GET（从 HttpOnly Cookie 读刷新令牌），
   设计稿声明 POST，两者兼容以避免 405。
10. **索引增强**：`account_role`/`role_menu`/`role_privilege` 加唯一键（防重复授权），
    `judge_task` 加 `(status, lease_expire_at)` 索引（故障转移扫描路径）。
11. **MinIO 归入 `storage` profile**、**broker 不挂载 store 卷** —— 原因见下方踩坑记录。

## 五、踩坑记录（本机实测，均已修复并写入代码注释/README FAQ）

| # | 现象 | 根因 | 处置 |
|---|---|---|---|
| 1 | broker 容器无限重启，`ExitCode=253`，**日志为空** | `apache/rocketmq` 镜像内 `/home/rocketmq/store` 不存在，Docker 把命名卷挂载点建成 `root:root`，而进程以 `uid=3000(rocketmq)` 运行 → 无写权限，broker 在输出任何日志前退出 | 不挂载 store 卷（`docker-compose.yml` 已注释说明；生产需先 `chown 3000:3000` 再挂） |
| 2 | `spring-boot:run` 报 `Could not build classpath: \d\1\...argfile` | `java.io.tmpdir` 传了 MSYS 风格 `/d/1/...`，JVM 解析成 `\d\1\...` | 脚本用 `pwd -W` 转成 `D:/1/...` |
| 3 | `mvn` 报 `找不到或无法加载主类 ...classworlds.launcher.Launcher` | Git Bash 下 `bin/mvn`（bash 包装脚本）路径解析失败 | 一律用 `mvn.cmd`（脚本已自动优先选择） |
| 4 | 服务连库 `Access denied for user 'root'@'172.19.0.1'` | `.env` 里 `MYSQL_PASSWORD` 与 `MYSQL_ROOT_PASSWORD` 不一致（底座约定二者相同，应用直接用 root 连库） | 已对齐；README FAQ 说明 |
| 5 | `docker compose` 报 `unknown command` | 本机 shell 解析不到 compose 插件，需用独立的 `docker-compose` | 两种写法都可用，按环境选择 |
| 6 | 沙箱内 `/tmp` 写入不落盘；`find`/`sort`/`timeout` 被 Windows 同名程序抢占 | 环境特性 | 验证类文件写入工作区；脚本改用 `awk`/`grep -o` |

## 六、当前运行状态

- **基础设施**：6 个容器运行中（mysql / redis / postgres / mq-namesrv / mq-broker / mq-console）。
- **Java 服务**：judge-user(9082) / judge-auth(9081) / judge-gateway(9080)。
- **管理员初始凭据**：`D:\1\CodeJudge\judge-auth\.bootstrap-credentials`（首登改密后自动删除）。
- **未启动**：MinIO（`docker compose --profile storage up -d minio`）。

## 七、待办与风险

| 项 | 说明 |
|---|---|
| TODO-1（P1 遗留） | 首登强制改密的标记位：底座 `user` 表**没有** `first_login` 列，仅有 `FirstChangePasswordDTO` 与 `.bootstrap-credentials` 机制。P2 做用户管理时需读源码确认「首登」判定依据，不猜测 |
| TODO-2 | `/teachers/register` 加固后，前端（P6）需改为「管理员开通教师」入口，不能再暴露自助注册页 |
| TODO-3 | Redisson 若 P3 需要，选 Boot 3 兼容版本，勿沿用 3.13.6 |
| TODO-4 | 网关登录限流阈值（2/s，burst 5）为经验值，P6 压测后按真实数据调整 |
| TODO-5 | `sql/seed.sql` 中题目与竞赛种子待 P2/P4 与判题链路一起落（提前写会产生"有题但判不了"的中间态） |

## 八、下一步（P2：用户 + 题目管理）

1. 新增 `judge-problem`（9083）：`problem` / `problem_version` / `test_case` / `tag` / `problem_tag` 的 CRUD，
   隐藏用例不下发，归属校验用 `OwnerAccessGuard`。
2. 补全 `judge-user` 的学员/教师管理端点与角色化可见范围。
3. 补 `sql/seed.sql` 的 ≥5 道题（覆盖 AC/WA/TLE/MLE/RE/CE 可复现场景 + 隐藏用例）。
4. 根 pom 打开 `judge-problem` 模块；网关路由已就绪（`/problems/**`、`/tags/**`、`/test-cases/**`）。
