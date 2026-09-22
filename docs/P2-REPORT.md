# CodeJudge P2 阶段交付报告 —— 用户域 + 题目域

> 阶段：P2「judge-user 补齐 + judge-problem 新建」
> 日期：2026-09-20 ｜ 状态：**已完成，20 项断言实测通过**
> 规划依据：[PLAN.md](PLAN.md) §3.2 / §5 ｜ 结构基线：[P1-改造说明.md](P1-改造说明.md)

---

## 一、交付清单

| 交付物 | 路径 | 说明 |
|---|---|---|
| 题目服务模块 | `judge-problem/` | 新增，端口 **9083**，20 个源文件 |
| 表结构 | `sql/init.sql` 第 **197–290** 行 | `judge_problem` 5 表（**P1 已建，本阶段零 schema 变更**） |
| 种子数据 | `sql/seed.sql` 第 **61 行起** | 6 题（5 已发布 + 1 草稿）/ 17 用例（其中 11 隐藏）/ 12 条标签关联 |
| 字符集防呆 | `sql/init.sql`、`sql/seed.sql` 顶部 | 新增 `SET NAMES utf8mb4;`（修复中文双重编码，见 §八.1） |
| judge-user 补齐 | `judge-user/` | 3 个分页端点由「返回全量列表」改为真分页 |
| 验收脚本 | `scripts/verify-p2.py`（实现） + `verify-p2.sh`（薄封装） | 20 项断言，可重复执行 |
| 启动脚本 | `scripts/dev-start-backend.{sh,ps1}` | 服务清单加入 `judge-problem` |
| 缺陷修复 | `judge-common/.../WrapperResponseBodyAdvice.java` | actuator 端点不再被业务响应体包装（见 §八.2） |

**模块文件数**：judge-problem 20 个 Java 源文件（不算 pom/yml）。

---

## 二、两个模块的关联关系（重点）

### 2.1 职责边界

| 模块 | 端口 | 职责 | 数据 |
|---|---|---|---|
| **judge-user** | 9082 | 账号、密码（BCrypt）、角色、资料 | 库 `judge_user`：`user` / `user_detail` |
| **judge-problem** | 9083 | 题目、题面版本、测试用例、标签 | 库 `judge_problem`：`problem` / `problem_version` / `test_case` / `tag` / `problem_tag` |

### 2.2 唯一的直接关联：`problem.owner_id` → `judge_user.user.id`

```
judge_user 库                          judge_problem 库
┌──────────────────┐                  ┌─────────────────────────┐
│ user             │                  │ problem                 │
│  id (雪花)       │◄─────────────────│  owner_id  ─── 逻辑外键  │
│  type 1员工/2学员/3教师 │  归属校验依据   │  current_version_id ─┐  │
│  status 0禁用/1正常     │              └──────────────────────┼──┘
└──────────────────┘                                          │
                                                              ▼
                                        ┌───────────────────────────────┐
                                        │ problem_version (改题新增版本) │
                                        │  version_no, statement, ...   │
                                        └───────────────────────────────┘
                                        ┌───────────────────────────────┐
                                        │ test_case (is_hidden 区分隐藏) │
                                        │ tag ◄──problem_tag──► problem │
                                        └───────────────────────────────┘
```

**四条设计约定（都不是随意选择）：**

1. **跨库不建物理外键**。分库后 `problem` 与 `user` 不在同一库，MySQL 无法跨库约束；即使用同一库也不建，因为雪花 id + 分库架构下外键会带来跨服务写锁与迁移困难。`owner_id` 是**逻辑外键**，其正确性由两条保证：
   - **写路径**：建题时 `owner_id` 直接取 `UserContext.getUserId()`（网关鉴权后注入的身份），**不接受客户端传入**，因此不可能指向不存在的用户；
   - **读路径**：题目的归属校验只做 `owner_id == 当前用户` 的比较，不依赖被引用的 user 行是否存在。

2. **本阶段不用 Feign**。题目域的读写不需要用户域的实时数据（不展示教师姓名、不校验角色），因此 `judge-problem` 的依赖里**没有 openfeign**。角色判断走网关透传的 `role-info` 头（`UserContext.hasRole`），不需要回查用户表。P3 判题链路若需要「取提交人信息」，再按需在 `judge-api` 增加契约。

3. **角色模型贯穿两域**。`user.type`（1员工 / 2学员 / 3教师）经登录写入 JWT `role` claim → 网关解析后以 `role-info` 头透传 → `judge-problem` 的 `@RequireRole` 与可见性分支据此分流。两域对"角色"只有一份定义：`judge-common` 的 `UserRole` 枚举。

4. **归属（owner）比角色（role）更细一层**。`@RequireRole(STAFF, TEACHER)` 只回答"你能不能建题"，`OwnerAccessGuard` 才回答"这道题是不是你的"。两者缺一都是越权：只有角色校验 → 任何教师能改任何人的题；只有归属校验 → 学员也能建题。**P2 验收的跨角色负例正是打在这条分界上。**

### 2.3 可见性矩阵（题目的三层隔离）

| 视角 | 题目列表 | 题面详情 | 可见样例（is_hidden=0）| 隐藏用例（is_hidden=1）|
|---|---|---|---|---|
| 学员 | 仅 `status=1` | 仅已发布 | ✅ | ❌ **字段为 null** |
| 他人教师 | `status=1` + 自己的 | 仅已发布；自己的草稿也可 | ✅ | ❌ **字段为 null** |
| 归属教师 | 同上 | ✅（含自己的草稿） | ✅ | ✅ |
| 管理员(STAFF) | 全部（含草稿/下线） | ✅ | ✅ | ✅ |

三处实现要点：
- 列表接口**完全不返回用例**（连样例也不返回）——列表页没有展示用例的需求，少查一张表就少一个泄露面；
- 「无权限」用 **null 而非空数组**表达 —— 空数组会被误读为"这题没有隐藏用例"，从而让越权者以为拿到了完整信息；
- 未发布题目对非归属者返回 **403 而非空详情** —— 否则"这道题存在"本身就是泄露。

---

## 三、数据库

### 3.1 建表语句的位置（唯一准）

建表语句**已在 P1 一次性写好**，本阶段**未改动 schema**。请以文件为准，本文不复制 DDL，避免多副本漂移：

| 库 | 位置 | 表 |
|---|---|---|
| `judge_user` | `sql/init.sql` 第 **146–196** 行 | `user`、`user_detail` |
| `judge_problem` | `sql/init.sql` 第 **197–290** 行 | `problem`、`problem_version`、`test_case`、`tag`、`problem_tag` |

为什么 P1 就把 5 张表全建了：MySQL 官方镜像的 `/docker-entrypoint-initdb.d` **只在数据卷为空时执行一次**，P2 再追加 DDL 对已初始化的卷不生效（详见 `sql/init.sql` 顶部注释）。

### 3.2 `problem` —— 题目主体（**不放题面正文**）

| 字段 | 类型 | 含义与约定 |
|---|---|---|
| `id` | BIGINT PK | 雪花 id（`IdType.ASSIGN_ID`），无 AUTO_INCREMENT |
| `title` | VARCHAR(255) | 题目标题，非空，服务层限制 ≤255 |
| `difficulty` | TINYINT | 难度 **1~5**，缺省 1 |
| `time_limit_ms` | INT | 默认 1000ms；**可被 `test_case.time_limit_ms` 单用例覆盖** |
| `memory_limit_mb` | INT | 默认 256MB；种子题 4003 特意压到 **32MB** 以复现 MLE |
| `status` | TINYINT | **0 草稿 / 1 已发布 / 2 已下线**。建题默认 0，避免半成品直接对学员可见 |
| `owner_id` | BIGINT | **归属教师 id**（逻辑外键 → `judge_user.user.id`），归属校验依据 |
| `current_version_id` | BIGINT | 指向当前生效的 `problem_version.id`（改题时切换） |
| `submit_count` | INT | 提交次数（冗余计数，供列表排序/通过率） |
| `accepted_count` | INT | 通过次数（冗余计数，P3 判题回写） |
| `create_time`/`update_time` | DATETIME | 由 `MyMetaObjectHandler` 自动填充 |
| `creater`/`updater` | BIGINT | 操作人（**底座不自动填充**，本模块显式写入） |
| `deleted` | TINYINT | 逻辑删除（`@TableLogic`，查询自动追加 `deleted=0`） |

索引：`idx_problem_status_difficulty(status, difficulty)`、`idx_problem_owner(owner_id)`

### 3.3 `problem_version` —— 题面版本（**改题即新增版本**）

| 字段 | 类型 | 含义 |
|---|---|---|
| `id` | BIGINT PK | 雪花 id |
| `problem_id` | BIGINT | → `problem.id` |
| `version_no` | INT | 版本号，从 1 递增；与 `problem_id` 组成 **唯一键 `uk_problem_version`** |
| `statement` | TEXT | 题面（Markdown） |
| `input_spec` | TEXT | 输入说明 |
| `output_spec` | TEXT | 输出说明 |
| `hint` | TEXT | 提示 / 样例说明 |
| `template_code` | **JSON** | 各语言模板代码，如 `{"java":"...","python":"..."}`；实体用 `JsonMapTypeHandler` 映射为 `Map<String,String>` |
| `created_by` | BIGINT | 本版本修改人 |

**为什么单独一张版本表**：历史提交在重判时需要还原当时的题面与限制。若直接改 `problem` 上的题面，老提交就无法解释"当时看到的是什么题"。因此 `PUT /problems/{id}` 只要涉及题面字段，就追加 `version_no + 1` 并切换 `current_version_id`，老版本一行不动。

> ⚠️ `template_code` 是 JSON 列，实体必须标 `@TableName(autoResultMap = true)`，否则 MyBatis-Plus 不会生成带 TypeHandler 的 resultMap，查出来恒为 null。

### 3.4 `test_case` —— 测试用例

| 字段 | 类型 | 含义 |
|---|---|---|
| `id` | BIGINT PK | 雪花 id |
| `problem_id` | BIGINT | → `problem.id`（**注意：挂题目，不挂版本**） |
| `seq` | INT | 执行顺序，从 1 开始；与 `problem_id` 组成 **唯一键 `uk_test_case_seq`** |
| `stdin` | TEXT | 标准输入 |
| `expected_stdout` | TEXT | 期望输出 |
| `is_hidden` | TINYINT | **0 可见(样例) / 1 隐藏** —— 隔离的核心开关 |
| `score` | INT | 该用例分值（IOI 赛制计分） |
| `time_limit_ms` | INT | 用例级时间限制覆盖；**NULL 表示沿用题目限制** |
| `judge_mode` | TINYINT | 比对模式：**0 精确 / 1 浮点容差 / 2 特判** |

**为什么用例挂题目而不是挂版本**：改题面不应导致已调好的用例集失效，反之增加用例也不必升版本。代价是重判老提交时会用**新**用例集 —— 这一点已在 `TestCaseService#replaceAll` 的注释里写明。

### 3.5 `tag` / `problem_tag` —— 标签与关联

| `tag` 字段 | 含义 |
|---|---|
| `id` | 雪花 id |
| `name` | 标签名，**唯一键 `uk_tag_name`**（全局共享字典） |
| `type` | `ALGORITHM` / `SOURCE` / `DIFFICULTY_TAG` |

| `problem_tag` 字段 | 含义 |
|---|---|
| `id` | 雪花 id |
| `problem_id` / `tag_id` | 多对多关联，**唯一键 `uk_problem_tag(problem_id, tag_id)`** 防重复打标 |

### 3.6 种子数据（`sql/seed.sql` 第 61 行起）

固定 id 段位：`problem` 4001–4006 ｜ `problem_version` 4101–4106 ｜ `test_case` 4201–4217 ｜ `problem_tag` 4301–4312。

| 题目 | 难度 | 限制 | 覆盖结论 | 设计意图 |
|---|---|---|---|---|
| 4001 A + B Problem | 1 | 1000ms / 256MB | **AC / WA** | seq3 输入 `2147483647 2147483647`，用 `int` 相加溢出得负数 → WA |
| 4002 1 到 n 求和 | 2 | 1000ms / 256MB | **TLE** | n 可达 1e9，朴素 for 循环必超 1s；需 O(1) 公式 |
| 4003 大数组求和（内存受限） | 3 | 2000ms / **32MB** | **MLE** | n=1e7 时一次性读入数组即超 32MB；需边读边累加 |
| 4004 数组访问边界 | 3 | 1000ms / 256MB | **RE** | 含 n=1 与单元素用例，越界/除零直接非零退出 |
| 4005 最短路上机综合题 | 4 | 2000ms / 256MB | **CE** | CE 与题目内容无关，提交语法非法代码即在编译阶段触发 |
| 4006【草稿】可见性验证题 | 2 | — | — | **status=0**，用于验证"学员看不到未发布题目"，勿改成 1 |

---

## 四、代码结构（judge-problem，20 个源文件）

### 4.1 分层与命名（完全对齐 judge-user / judge-auth 的既有约定）

```
judge-problem/src/main/java/com/codejudge/problem/
├── ProblemApplication.java              @SpringBootApplication + @MapperScan("com.codejudge.problem.mapper")
├── config/                              （本阶段无需自定义配置，走 judge-common 自动装配）
├── controller/
│   ├── ProblemController.java           /problems
│   ├── TestCaseController.java          /test-cases
│   └── TagController.java               /tags
├── domain/
│   ├── dto/
│   │   ├── ProblemFormDTO.java          建题/改题表单（主体 + 题面两组字段）
│   │   ├── ProblemQuery.java            列表查询条件（extends PageQuery）
│   │   ├── TestCaseFormDTO.java         用例表单
│   │   └── TagFormDTO.java              标签表单
│   ├── po/
│   │   ├── Problem.java                 extends BasePO
│   │   ├── ProblemVersion.java          autoResultMap=true（JSON 列）
│   │   ├── TestCase.java                extends BasePO
│   │   ├── Tag.java                     extends BasePO
│   │   └── ProblemTag.java              extends BasePO
│   └── vo/
│       ├── ProblemVO.java               列表项（静态 fill() 供子类复用）
│       ├── ProblemDetailVO.java         extends ProblemVO，含题面 + 分级用例
│       ├── ProblemVersionVO.java        版本历史
│       ├── TestCaseVO.java              用例视图
│       └── TagVO.java                   标签视图
├── mapper/
│   ├── ProblemMapper.java               BaseMapper
│   ├── ProblemVersionMapper.java        BaseMapper
│   ├── TestCaseMapper.java              + 2 个物理删除方法
│   ├── TagMapper.java                   + 1 个物理删除方法
│   └── ProblemTagMapper.java            + 2 个物理删除方法
└── service/
    ├── ProblemService.java              题目主体 + 可见性 + 归属校验
    ├── TestCaseService.java             用例 + 归属校验
    └── TagService.java                  标签字典
```

### 4.2 关键实现选择

| 选择 | 理由 |
|---|---|
| `ProblemService` 直连 `TestCaseMapper` 而不注入 `TestCaseService` | 详情接口要装配用例；若互相注入会形成循环依赖。归属校验方法留在 `ProblemService` 供 `TestCaseService` 调用，依赖单向 |
| `putIfAbsent` 式的 `isOwnerOrStaff()` 布尔方法与 `getOwnedProblem()` 抛异常方法并存 | 详情接口需要"要不要下发隐藏用例"的**判断**而非中断；写接口需要**中断**。两种语义不能合并 |
| `ProblemVO.fill()` 泛型静态方法 | 让 `ProblemDetailVO` 复用同一份字段映射，避免 12 行 setter 在两处漂移 |
| 列表页批量装配标签（1 次关联查询 + 1 次标签查询） | 逐题查询会造成 N+1 |
| 建题默认 `status=0`（草稿） | 防止"标题刚写完就发布"的半成品对学员可见 |

---

## 五、统一响应封装与 Knife4j 注解

### 5.1 统一响应（沿用底座，未新造）

- **`R<T>`**：`{code, msg, data, requestId}`。Controller 返回 `R.ok(...)` / `R.ok()`。
- **`WrapperResponseBodyAdvice`**：即使 Controller 漏写 `R`，也会自动包装；`@NoWrapper` 标注的方法除外（本模块无内部 Feign 端点，故未使用）。
- **`PageDTO<T>`**：`{total, pages, list}`，由 `PageDTO.of(page, converter)` 从 MyBatis-Plus `Page` 转换。
- **`PageQuery`**：`{pageNo, pageSize, sortBy, isAsc}`，`toMpPage(defaultSortBy, defaultAsc)` 转 MP Page。
- **异常**：`BadRequestException`(400) / `ForbiddenException`(403) / `UnauthorizedException`(401) / `BizIllegalException`(1001) 全部由 `CommonExceptionAdvice` 统一转成 `R.error(code, msg)`。

### 5.2 Knife4j 注解

方法级 `@Operation(summary = "...")`，与 `judge-auth` 的 `AccountController` 风格一致。

> **一致性说明**：P1 的 `judge-user` 控制器**没有**加 `@Operation`（也不带类级 `@Tag`）。本阶段给 judge-problem 的 15 个端点全部加了 `@Operation`，并顺手给 judge-user 三个分页端点补上。**类级 `@Tag` 目前全项目都没用** —— 未加是为了不在 P2 引入与 P1 不一致的风格。若希望统一加 `@Tag` 分组，属一次性小改，请告知。
>
> 文档入口：`http://localhost:9083/doc.html`（直连）或 `http://localhost:9080/doc.html`（网关；网关不聚合下游文档，这是底座行为）。

---

## 六、接口清单与 curl 示例

### 6.0 准备：取 token

```bash
# 教师登录（种子账号：teacher001 / 123456）
TOKEN=$(curl -s -X POST http://localhost:9080/accounts/login \
  -H 'Content-Type: application/json' \
  -d '{"cellPhone":"13900000011","password":"123456"}' \
  | python -c "import sys,json;print(json.load(sys.stdin)['data']['accessToken'])")
echo "$TOKEN"

# 学员 token（student001 / 123456），用于对比可见性
STOKEN=$(curl -s -X POST http://localhost:9080/accounts/login \
  -H 'Content-Type: application/json' \
  -d '{"cellPhone":"13900000001","password":"123456"}' \
  | python -c "import sys,json;print(json.load(sys.stdin)['data']['accessToken'])")
```

> **注意 `/problems/**` 不在网关白名单内**，所有请求都必须带 token。
>
> **本地直连调试**（跳过网关，直接打 9083）时用身份头替代 token —— 这是 `scripts/verify-p2.sh` 采用的方式：
> ```bash
> # 教师(3) 身份
> curl -s http://localhost:9083/problems/4001 -H 'user-info: 2101' -H 'role-info: 3'
> ```
> ⚠️ 该方式**仅在本地直连时有效**：经网关时这两个头会被 `AuthGlobalFilter` 剥离，外部无法伪造。

### 6.1 题目（`/problems`）

| # | 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|---|
| 1 | POST | `/problems` | 教师 / 管理员 | 建题（含题面 v1 与模板代码） |
| 2 | PUT | `/problems/{id}` | 归属教师 / 管理员 | 改题（题面变更时生成新版本） |
| 3 | PUT | `/problems/{id}/status/{status}` | 归属教师 / 管理员 | 状态变更（0/1/2） |
| 4 | DELETE | `/problems/{id}` | 归属教师 / 管理员 | 删除题目 |
| 5 | GET | `/problems/page` | 登录 | 分页（可见范围按角色收敛） |
| 6 | GET | `/problems/{id}` | 登录 | 详情（用例按视角分级） |
| 7 | GET | `/problems/{id}/versions` | 归属教师 / 管理员 | 题面版本历史 |
| 8 | GET | `/problems/{id}/test-cases` | 归属教师 / 管理员 | 全部用例（含隐藏） |
| 9 | POST | `/problems/{id}/test-cases` | 归属教师 / 管理员 | 批量新增用例 |
| 10 | PUT | `/problems/{id}/test-cases` | 归属教师 / 管理员 | 全量替换用例集 |

```bash
# ---- 1. 建题（建完默认是草稿 status=0）----
curl -s -X POST http://localhost:9080/problems \
  -H "authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{
        "title": "二叉树的最大深度",
        "difficulty": 2,
        "timeLimitMs": 1000,
        "memoryLimitMb": 256,
        "tagIds": [3001, 3006],
        "statement": "给定一棵二叉树，输出它的最大深度。",
        "inputSpec": "第一行 n，随后 n 行描述节点。",
        "outputSpec": "一行，一个整数。",
        "hint": "递归或层序遍历均可。",
        "templateCode": {"java": "class Solution {}", "python": "def solve(): pass"}
      }'
# → {"code":200,"msg":"OK","data":<新建题目id>,...}

# ---- 2. 改题（只传题面字段 → 会生成新版本 versionNo+1）----
curl -s -X PUT http://localhost:9080/problems/4001 \
  -H "authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"statement": "【更新】给定两个整数 a、b，输出 a + b。"}'

# ---- 3. 发布（草稿 → 已发布）----
curl -s -X PUT http://localhost:9080/problems/4001/status/1 \
  -H "authorization: Bearer $TOKEN"

# ---- 4. 删除 ----
curl -s -X DELETE http://localhost:9080/problems/4001 \
  -H "authorization: Bearer $TOKEN"

# ---- 5. 分页（关键字 + 难度 + 标签 + 只看我的）----
curl -s -G http://localhost:9080/problems/page \
  -H "authorization: Bearer $TOKEN" \
  --data-urlencode 'pageNo=1' --data-urlencode 'pageSize=10' \
  --data-urlencode 'keyword=求和' --data-urlencode 'difficulty=2' \
  --data-urlencode 'tagId=3010' --data-urlencode 'onlyMine=true'
# 学员 token 调同一接口：只返回 status=1；传 status=0 会被忽略
curl -s 'http://localhost:9080/problems/page?pageSize=50' -H "authorization: Bearer $STOKEN"

# ---- 6. 详情（学员视角：testCases 恒为 null）----
curl -s http://localhost:9080/problems/4001 -H "authorization: Bearer $STOKEN"
# 归属教师视角：testCases 有值，hiddenCaseCount=2
curl -s http://localhost:9080/problems/4001 -H "authorization: Bearer $TOKEN"

# ---- 7. 版本历史 ----
curl -s http://localhost:9080/problems/4001/versions -H "authorization: Bearer $TOKEN"

# ---- 8. 列出全部用例（含隐藏）----
curl -s http://localhost:9080/problems/4001/test-cases -H "authorization: Bearer $TOKEN"

# ---- 9. 批量新增用例（seq 留空自动续编）----
curl -s -X POST http://localhost:9080/problems/4001/test-cases \
  -H "authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '[
        {"stdin":"5 7","expectedStdout":"12","isHidden":0,"score":10},
        {"stdin":"999999999 1","expectedStdout":"1000000000","isHidden":1,"score":50,"timeLimitMs":500}
      ]'
# → {"code":200,"data":2}

# ---- 10. 全量替换用例集（破坏性：先清空再写入）----
curl -s -X PUT http://localhost:9080/problems/4001/test-cases \
  -H "authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '[{"stdin":"1 2","expectedStdout":"3","isHidden":0,"score":100}]'
```

### 6.2 用例（`/test-cases`）

| # | 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|---|
| 11 | PUT | `/test-cases/{caseId}` | 归属教师 / 管理员 | 修改单个用例（仅覆盖非空字段） |
| 12 | DELETE | `/test-cases/{caseId}` | 归属教师 / 管理员 | 删除单个用例（物理删除，序号可复用） |

```bash
# ---- 11. 改用例（把隐藏改成可见）----
curl -s -X PUT http://localhost:9080/test-cases/4203 \
  -H "authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"isHidden":0,"score":20}'

# ---- 12. 删用例 ----
curl -s -X DELETE http://localhost:9080/test-cases/4217 \
  -H "authorization: Bearer $TOKEN"
```

### 6.3 标签（`/tags`）

| # | 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|---|
| 13 | GET | `/tags` | 登录 | 标签列表（可按 type 过滤） |
| 14 | POST | `/tags` | 管理员 | 新建标签 |
| 15 | DELETE | `/tags/{id}` | 管理员 | 删除标签（被题目引用时拒绝） |

```bash
# ---- 13. 标签列表 ----
curl -s 'http://localhost:9080/tags?type=ALGORITHM' -H "authorization: Bearer $TOKEN"

# ---- 14. 新建标签 ----
curl -s -X POST http://localhost:9080/tags \
  -H "authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"并查集","type":"ALGORITHM"}'

# ---- 15. 删除标签（若已被引用会返回「该标签已被 N 道题目使用」）----
curl -s -X DELETE http://localhost:9080/tags/3001 -H "authorization: Bearer $TOKEN"
```

### 6.4 judge-user 变更（3 个端点）

```bash
# 学员分页（原本返回全量 List，P2 改为真分页 + 关键字）
curl -s -G http://localhost:9080/students/page \
  -H "authorization: Bearer $TOKEN" \
  --data-urlencode 'pageNo=1' --data-urlencode 'pageSize=10' --data-urlencode 'keyword=演示'
# → {"code":200,"data":{"total":5,"pages":1,"list":[...]}}

# 教师分页
curl -s 'http://localhost:9080/teachers/page?pageSize=10' -H "authorization: Bearer $TOKEN"

# 员工分页
curl -s 'http://localhost:9080/staffs/page?pageSize=10' -H "authorization: Bearer $TOKEN"
```

### 6.5 已有接口（P1 交付，本阶段未改）

`POST /accounts/login`、`/accounts/admin/login`、`/accounts/refresh`、`/accounts/logout`、`/accounts/password/first-change`、`GET /users/me`、`/users/{id}`、`/users/page`、`/users/checkCellphone`、`POST /students/register`、`POST /teachers/register`(管理员) 等 —— 详见 `README.md`。

---

## 七、验收实证（实测，非推断）

| 项 | 结果 |
|---|---|
| `mvn clean install -DskipTests` | ✅ BUILD SUCCESS，42s，EXIT 0，6 模块全绿 |
| judge-problem(9083) 启动 | ✅ `/actuator/health` → `{"status":"UP","components":{"db":{"status":"UP","database":"MySQL"},...}}` |
| 种子灌入 + 幂等重放 | ✅ 首次与重放后均为 `6 题 / 17 用例 / 12 标签关联` |
| `template_code` JSON 合法性 | ✅ `JSON_VALID=1`，`JSON_KEYS` 正确解析出 cpp/java/python |
| 中文编码 | ✅ `CHAR_LENGTH`：最短路上机综合题=8、数组=2、演示学员一=5、管理员=3 |

### 20 项断言（全部 PASS）

| # | 断言 | 实测 |
|---|---|---|
| 1 | 学员列表总数（5 已发布 + 1 草稿 → 只见 5） | 5 ✅ |
| 2 | 列表中不含草稿题 4006 | False(不在列表中) ✅ |
| 3 | 学员详情 samples 条数 | 2 ✅ |
| 4 | 学员详情 `testCases` 为 null | null ✅ |
| 5 | 学员详情 `hiddenCaseCount` 为 null | null ✅ |
| 6 | 归属教师详情 `testCases` 条数（含隐藏） | 4 ✅ |
| 7 | 归属教师 `hiddenCaseCount` | 2 ✅ |
| 8 | 学员访问草稿题 → 403 | 403 ✅ |
| 9 | **非归属教师访问他人草稿 → 403** | 403 ✅ |
| 10 | 归属教师访问自己的草稿 → 200 | 200 ✅ |
| 11 | **教师改他人题目 → 403**（越权关键负例） | 403 ✅ |
| 12 | 学员改题目 → 403（角色门槛） | 403 ✅ |
| 13 | 学员建题 → 403 | 403 ✅ |
| 14 | 归属教师改自己的题 → 200 | 200 ✅ |
| 15 | 改题后生成新版本（versionNo ≠ 1） | 递增 ✅ |
| 16 | 题面为新版本内容 | 一致 ✅ |
| 17 | 教师列表可见总数 | 5 ✅ |
| 18 | 标签条数 | 10 ✅ |
| 19 | 学员建标签 → 403 | 403 ✅ |
| 20 | 题目标题中文长度（防双重编码回归） | 8 ✅ |

**通过 20 项，失败 0 项。**

复现命令：
```bash
python scripts/verify-p2.py            # 实现（纯标准库，不依赖 curl）
bash  scripts/verify-p2.sh             # 薄封装，供 bash 环境
BASE=http://localhost:9083 python scripts/verify-p2.py   # 覆盖目标地址
```

> **为什么验收脚本是 `.py` 而不是 `.sh`**：本机沙箱**无法执行任何 `.sh` 文件** —— 执行 `.sh` 会被路由到 `wsl.exe`，而 `wsl.exe` 在本机程序黑名单中，命令会被直接中止（实测连一行 `echo` 的脚本都跑不起来）。改用纯标准库 Python 后不再依赖外部程序，可稳定执行；`.sh` 保留为薄封装，供普通 Git Bash 环境使用。这个限制同样影响 `scripts/dev-start-backend.sh`（本阶段 `judge-problem` 是用 `java -jar` 直接起的）。

---

## 八、本轮发现并修复的缺陷

### 八.1 【重要】中文种子数据被双重编码（影响 P1 已有数据）

**现象**：`problem.title` 的「最短路上机综合题」是 8 个字符，库里 `CHAR_LENGTH` 返回 **24**（8×3 字节）。

**根因**：容器内 `docker exec <ctr> mysql < file.sql` 载入脚本时，若 `LANG` 未设置，MySQL 客户端的 `character_set_client` 会退化为 **latin1**。脚本文件是 UTF-8，其字节被服务端按 latin1 解释后再转存进 utf8mb4 列 → 双重编码。
证据：同一张 `user` 表里，**运行时经 JDBC 写入的「管理员」是正确的**（`CHAR_LENGTH=3`），而脚本灌入的「演示学员一」是坏的（`15`）—— 说明问题在**载入方式**，不在应用代码或 JDBC 配置。

**影响范围**：P1 灌入的所有中文种子（学员/教师姓名、`user_detail.intro`、10 个标签名），以及 `sql/init.sql` 里所有表 `COMMENT`。

**处置**：
1. 在 `sql/init.sql` 与 `sql/seed.sql` **顶部各加 `SET NAMES utf8mb4;`** —— 让脚本自身免疫载入方式，不依赖调用者记得加 `--default-character-set`。已验证：**不带** `--default-character-set` 参数载入后中文正确。
2. 清理被污染的种子行（**仅种子固定 id 段，未触碰运行时生成的管理员账号**）后重新载入，已全部校验通过。
3. 在验收脚本中加入"中文长度"断言（第 20 项），防止回归。

### 八.2 【重要】actuator 端点被业务响应体包装

**现象**：`GET /actuator/health` 返回 `{"code":200,"msg":"OK","data":{"status":"UP",...}}`，而不是 actuator 的原生契约 `{"status":"UP",...}`。

**根因**：`WrapperResponseBodyAdvice.beforeBodyWrite` 只跳过了 springdoc 路径，没有跳过 `/actuator`。

**影响（都会在 P6 集中爆发）**：
1. Docker/K8s 的 healthcheck 按 `{"status":"UP"}` 解析会**直接判定不健康**；
2. Prometheus 抓取 `/actuator/prometheus` 拿到的是被包了一层的 JSON，**文本 exposition 格式被破坏，指标全部采不到**；
3. Spring Boot Admin / 各类探针同样无法识别。

**处置**：`path.startsWith("/actuator")` 时跳过包装。actuator 是面向机器的运维接口，不属于业务响应契约。

> ⚠️ 这是 **judge-common 层的公共改动**，影响全部 5 个 Servlet 服务（gateway 是 WebFlux，本就未注册该 advice）。

### 八.3 唯一键不含 `deleted`，逻辑删除会锁死 seq / 标签复用

**背景**：`test_case`、`problem_tag`、`tag` 的唯一键分别是 `(problem_id, seq)`、`(problem_id, tag_id)`、`(name)`，**都不含 `deleted` 列**。

**如果按 MyBatis-Plus 默认逻辑删除**：`deleteById` 只把 `deleted` 置 1 而**保留行**，之后：
- 同一题目再用同一 `seq` 新增用例 → 撞 `uk_test_case_seq`，报「数据已存在」；
- 取消某标签后想重新打上 → 撞 `uk_problem_tag`，**再也打不回去**；
- 删除标签后新建同名标签 → 撞 `uk_tag_name`。

**处置**：这三张表的删除一律走**物理删除**（`TestCaseMapper.deletePhysicallyByProblemId/ById`、`ProblemTagMapper.deletePhysicallyByProblemId/ByTagId`、`TagMapper.deletePhysicallyById`），并在每个方法的 javadoc 里写明原因，避免后人"顺手改成 deleteById"。
`problem` / `problem_version` 仍用逻辑删除 —— 它们的 id 是雪花、永不复用，不存在键复用问题。

### 八.4 清理 2 处 zx-learn 业务注释残留

| 位置 | 问题 |
|---|---|
| `OwnerAccessGuard` 类注释 | 背景举例是 `/learning-records/users/**`、`/question-results/users/**`（学情 / 答题记录业务），违反"不带入教育业务代码"约束 → 已改为通用描述 |
| P1 已修 3 处（`Constant` 课程常量、`MqHandler` 注释、`InternalOnlyGuardTest` 注释） | 见 [P1-改造说明.md](P1-改造说明.md) §八 |

---

## 九、有意偏差与待确认

| # | 项 | 说明 |
|---|---|---|
| 1 | **`/students/page` 等 3 个端点返回类型变更** | 由 `R<List<UserVO>>` 改为 `R<PageDTO<UserVO>>`。这是**破坏性接口变更**（P1 交付的接口名是 `/page` 却返回全量列表，属实现缺陷）。当前无前端（P6 交付），故此刻改成本最低。**若你要求向后兼容，请告知，可加 `/list` 旧端点保留。** |
| 2 | **建题默认草稿（status=0）** | PLAN 未明确默认值。选择 0（草稿）是安全默认：避免未完成题面对学员可见。若希望"建完即发布"，改 `ProblemService.createProblem` 一处即可 |
| 3 | **`tagIds` 的 null / 空数组语义不同** | `null` = 不改动标签，`[]` = 清空标签。JSON 里两者可区分，但**易被前端忽略**，已在 DTO 注释中写明 |
| 4 | **用例删除是物理删除** | 依赖 `judge_result.case_id` 的审计能力会受影响（P3 起）。若需要保留删除历史，应改为"软删 + 唯一键加 `deleted` 列"的 schema 变更 |
| 5 | **`replaceAll` 是破坏性接口** | 重判历史提交会按新用例集判定。已在 javadoc 与本文档标注 |
| 6 | **`judge-problem` 未引入 Redis / openfeign** | 本阶段确实不用。`judge:problem:hot` 热门榜是 P4/P6 的事，届时再补 `data-redis` |
| 7 | **观察：网关注释与实际白名单不一致** | `AuthGlobalFilter.optionalIdentity` 的注释举例「`/problems/page` 这类端点既要匿名可浏览…」，但 `JwtProperties.excludePaths` 里**并没有** `/problems/page` —— 即当前经网关访问题目列表**必须登录**。属注释先行、配置未跟上的小不一致，已在此记录，未擅改（涉及匿名可见的产品决策） |

---

## 十、下一步（P3：提交 + MQ + worker + 沙箱）

1. 新增 `judge-submission`(9084)：提交落库 + 唯一索引幂等 + MQ 投递 + 结果查询。
2. 新增 `judge-worker`(9085)：MQ 消费、沙箱执行、用例逐跑、结果回写、心跳、故障转移。
3. 沙箱镜像 4 个：`judge-java21` / `judge-python3.12` / `judge-gcc13` / `judge-go1.22`。
4. `judge-api` 增加 problem/submission 契约（worker 需读取题目限制与用例）。
5. 六种 verdict 用本阶段的种子题逐条复现：4001→AC/WA、4002→TLE、4003→MLE、4004→RE、4005→CE。

**P3 前置检查（本阶段已就绪）**：题目与用例数据齐备（17 条用例、11 条隐藏）、`problem.time_limit_ms` / `memory_limit_mb` / `test_case.time_limit_ms` 三层限制已可读取、`judge_mode` 已预留比对模式字段。
