# P5 交付报告 —— judge-ai(9087) + LLM 点评 + pgvector RAG + SSE 流式

> 交付日期：2026-09-21 ｜ 阶段：P5 ｜ 服务：`judge-ai` :9087（**纯响应式 / Netty**）
> 对应需求条款：见 `docs/PROMPT-ARCHIVE.md`（原文已丢失，§B 为唯一依据）

---

## 一、交付物清单

| 类别 | 文件 | 说明 |
|---|---|---|
| 模块 | `judge-ai/pom.xml` | 纯 WebFlux 模块，排除 servlet 容器；含取舍长注释 |
| 启动 | `AiApplication.java` | `@SpringBootApplication` + `@EnableFeignClients` |
| 配置 | `application.yml` | 端口 9087、PG `judge_ai`、**显式 `web-application-type: reactive`** |
| 配置类 | `LlmProperties` / `RagProperties` / `ReviewProperties` | `cj.llm.*` / `cj.rag.*` / `cj.review.*` |
| SSE 核心 | `ReviewService` | START→RETRIEVAL→DELTA×N→END、心跳、断线重连、并发配额、取消落库、结构化降级 |
| 上下文 | `ReviewContextService` | Feign 聚合提交/题目 + 归属鉴权 + 双路向量召回 |
| Prompt | `ReviewPromptBuilder` | 角色设定 + 输出结构 + 3 条硬约束 + RAG 分区注入 |
| LLM | `LlmClient` | 复用底座：WebClient 流式 / 非流式，OpenAI 兼容端点 |
| 向量化 | `EmbeddingService` | 复用底座：L2 归一化，降级打 ERROR 日志（防"静默伪向量"） |
| 切片 | `TextSplitter` | 复用底座：滑动窗口 |
| 记忆 | `service/memory/ChatMemory` + `RedisMessage` | 复用底座：Redis 会话记忆，key `judge:ai:memory:` |
| 知识服务 | `KnowledgeService` | 题目知识 + 历史点评**双路召回**，权威内容优先 |
| 仓储 | `KnowledgeVectorRepository` / `AiReviewRepository` | JdbcTemplate + PGvector 二进制绑定 |
| 接口 | `ReviewController` / `KnowledgeController` | SSE + 非流式 + 历史；知识库 CRUD |
| MQ | `mq/AiReviewMqConsumer` | `ai_review#REQUESTED` 异步预生成（默认关闭） |
| 安全 | `security/AiIdentity` + `AiAccessGuard` | WebFlux 版身份解析与入口守卫 |
| 数据库 | `deploy/pgvector/init.sql` | `ai_review.embedding vector(1024)` + HNSW + 幂等 ALTER |
| 内部契约 | `judge-api` `SubmissionReviewContextDTO` + `SubmissionClient#getReviewContext` | 点评输入侧 |
| 内部端点 | `judge-submission` `InternalSubmissionController#reviewContext` | `InternalOnlyGuard` 保护 + 隐藏用例遮蔽 |
| 前端示例 | `docs/examples/ai-review-sse.html` | 自包含单页：手工 SSE 解析 + 增量渲染 + 重连 |
| 前端模块 | `docs/examples/aiReviewStream.js` | 框架无关可复用封装 |
| 前端接入说明 | `docs/P5-前端SSE接入说明.md` | 协议、字段、渲染与重连策略 |
| 验收脚本 | `scripts/verify-p5.py` | 12 段断言（A–M） |
| 启动脚本 | `scripts/dev-start-backend.py` | 新增 `judge-ai: 9087` |

---

## 二、底座 zx-aigc 复用映射（逐类名）

| 底座类（`com.zhixing.aigc`） | 本项目（`com.codejudge.ai`） | 改造点 |
|---|---|---|
| `service/LlmClient` | `service/LlmClient` | 配置前缀 `zx.*` → `cj.*`；mock 文案改判题域 |
| `service/EmbeddingService` | `service/EmbeddingService` | 同上；维度默认 1024 |
| `service/TextSplitter` | `service/TextSplitter` | 原样复用 |
| `service/KnowledgeService` | `service/KnowledgeService` | 单路 → **双路召回**（题目知识 + 历史点评），权威优先 |
| `service/KnowledgeVectorRepository` | `repository/KnowledgeVectorRepository` | 列 `course_id/lesson_id` → `problem_id/source_type` |
| `memory/ChatMemory` | `service/memory/ChatMemory` | key 前缀 `judge:ai:memory:`；会话粒度=提交 |
| `config/LlmProperties` / `RagProperties` | 同名 | 前缀 `cj.llm` / `cj.rag`，新增判题域字段 |
| `controller/ChatController`（SSE） | `controller/ReviewController` | `ChatEventVO` → `ReviewEventVO`，新增 RETRIEVAL |
| `service/ChatService`（SSE 编排） | `service/ReviewService` | 见 §三 四处改造 |
| `domain/ChatEventVO` | `domain/ReviewEventVO` | 3 类事件 → 5 类事件（+RETRIEVAL/ERROR） |
| `config/RoleGuardWebFilter` | `security/AiAccessGuard` | WebFilter → 可调用组件（见 §三.5） |
| `agent/AbstractAgent` + 4 个领域 Agent | `agent/ReviewPromptBuilder` | **不保留继承树**：本项目只有一个领域，一个实现类的抽象层是纯负债 |
| `domain/ChunkHit` / `KnowledgeChunk` | 同名 | 新增 `problemId` / `sourceType` |
| `domain/ChatSession` | *未移植* | 底座按 `userId` 建会话；点评按**提交**建会话（见 §三.6） |

**未复用 Spring AI VectorStore**（与底座一致）：底座是自研 `KnowledgeVectorRepository` + pgvector，引入 Spring AI 等于新增一套并行向量抽象，与"禁止重复开发"相悖。

---

## 三、关键设计决策（均为"为什么这样而不是那样"）

### 3.1 为什么 judge-ai 必须是**响应式栈**，且必须显式声明

底座 SSE 的返回类型是 `Flux<ServerSentEvent<...>>`，其序列化由 WebFlux 的 `ServerSentEventHttpMessageWriter` 负责 —— **Servlet 栈没有这个写处理器**。因此必须走 WebFlux。

但"排除 `spring-boot-starter-web`"**不足以**落到响应式：
`knife4j → springdoc-openapi-starter-webmvc-ui → spring-webmvc(DispatcherServlet)` 仍会进入类路径（已实测 `judge-contest` 成品 jar 中同时存在二者）。Spring Boot 的 `deduceFromClasspath()` 在 **DispatcherHandler 与 DispatcherServlet 同时存在**时判定为 SERVLET。

**已实测的静默失效模式**：`judge-ai` 成品 jar 中 `spring-webmvc` 存在、`jakarta.servlet` **完全缺失** → 类路径推断得出 `WebApplicationType.NONE` → 服务"启动成功"却**根本不监听 9087**，无任何报错。

因此在 `application.yml` **显式**写 `spring.main.web-application-type: reactive`，跳过推断。

代价：judge-ai 不提供 `/doc.html` 界面（springdoc-webmvc 的自动配置带 `@ConditionalOnWebApplication(SERVLET)`，已反编译确认，响应式下不生效）。注解与 `/v3/api-docs` 契约保留；网关本就不聚合各服务文档。

### 3.2 事件缓冲改为**整事件序列**（相对底座的缺陷修复）

底座只缓冲 DELTA 文本，导致重连回放的 id 体系与首次连接不一致（START 的 id 在重连中消失 → 前端永远拿不到 `reviewId`）。

本项目缓冲**完整事件**（含 `seq`），回放时按 `seq > lastEventId` 精确续传。按事件自带 seq 过滤而不是按下标 —— 缓冲做过 `trim` 后下标与 id 的对应关系整体偏移，按下标回放会"重放旧的、丢掉新的"，是断线重连最典型的隐性 bug。

### 3.3 阻塞 IO 显式切到 `boundedElastic`

装配上下文要调 **Feign + JDBC + Redis**，都是同步阻塞调用。WebFlux 的事件循环线程一旦被阻塞，整个服务的高并发能力直接归零（表现为"单条点评正常，10 条并发就全线超时"）。底座直接在事件循环上做同类操作。

本项目做法：装配阶段 `Mono.fromCallable(...).subscribeOn(boundedElastic)`，生成末端 `publishOn(boundedElastic)`。

### 3.4 取消即落库

客户端断开时把已生成的部分内容按"中断"入库（`status=2` + `error_msg="客户端中断，已保存部分内容"`）。否则大量中途关闭页面的点评会永远停留在 `status=0 生成中`，既污染历史列表，也让"生成中"这个状态失去判别能力。

### 3.5 降级是**结构化输出**而不是占位串

LLM 未配置时仍返回：判题结论、逐用例表格、编译错误日志、RAG 命中的参考资料清单，并标注 `degraded=true`。而不是一句"AI 不可用" —— 后者对学员毫无价值，还会让人以为功能坏了。

降级路径同样**逐块下发**（48 字符/块），保持真实的流式手感。

### 3.6 会话粒度 = 提交，不是用户

`sessionId = review:{submissionId}`。点评是围绕一次提交展开的多轮追问（"那我的边界情况该怎么改？"），换一道题就应该是一段新记忆。按 `userId` 建会话会把不同题目的上下文搅在一起。

### 3.7 WebFlux 下**禁止**使用 `UserContext` / `InternalOnlyGuard` / `OwnerAccessGuard`

`UserContext` 是 ThreadLocal + Servlet 拦截器填充的，在 WebFlux 下**恒为 null**。而 judge-common 的两个 Guard 都以"无 `user-info` 头 ⇒ 判定为服务间 Feign 直连 ⇒ 放行"为规则。

**若在 WebFlux 里复用它们，所有外部请求都会被误判为内部调用而放行** —— 这是本阶段最危险的一处潜在越权。因此：

- 身份一律经 `AiIdentity.from(ServerHttpRequest)` 从请求头显式取；
- 归属校验在 `ReviewContextService#fetchOwnedSubmission` 内实现（三态：503 / 404 / 403）；
- `AiIdentity` 对畸形头**容错为 null（未登录）而不是抛异常** —— 否则"填一个畸形头即可让服务 500"是廉价的 DoS 面。

### 3.8 `RoleGuardWebFilter` → `AiAccessGuard`（组件而非 Filter）

Filter 按路径前缀拦截，容易把"明明允许学员访问"的 `/ai/review/stream` 一并挡掉。点评接口对学员开放，**只有知识库维护限特权角色**。改成分角色可调用的组件后，权限语义与业务语义同处可读，且可单测。

### 3.9 MQ 预生成默认关闭

`cj.review.mq-enabled=false`。理由：点评主路径是 SSE 实时生成，MQ 预生成是**增强项**；默认开启会让"服务能否启动"依赖 broker 可达性。关闭时 `RocketMQConsumerContainer` 发现无 `MqHandler` 后直接跳过启动，**不建立任何 MQ 连接**。

---

## 四、接口清单（入参 / 返回结构）

### 4.1 流式点评（主接口）

```
POST /ai/review/stream        Accept: text/event-stream        ← 前端首选
GET  /ai/review/stream?submissionId=&reviewType=&question=      ← curl/调试
Header: user-info（网关注入） / role-info（网关注入） / Last-Event-ID（重连时回填）
Body:   {"submissionId": 123, "reviewType": 1, "question": "边界怎么改？"}
```

响应 `text/event-stream;charset=UTF-8`：

```
id:0
event:message
data:{"type":"START","content":"","seq":0,"reviewId":17,"submissionId":123,"degraded":false,"model":"glm-4.5-air"}

id:1
event:message
data:{"type":"RETRIEVAL","content":"已检索到 2 条题目知识、1 条同题历史点评，开始生成点评",
      "sources":[{"sourceType":"KNOWLEDGE","title":"整型溢出的典型表现","score":0.87,"snippet":"..."}]}

id:2
event:message
data:{"type":"DELTA","content":"## 结论\n","seq":2}
…（DELTA × N）
id:N
event:message
data:{"type":"END","content":"","finishReason":"STOP"}
```

| 事件 | 语义 | 关键字段 |
|---|---|---|
| `START` | 开始生成 | `reviewId` / `degraded` / `model` |
| `RETRIEVAL` | RAG 检索完成，**先于正文推送** | `content`（摘要）/ `sources[]` |
| `DELTA` | 增量正文 | `content` |
| `ERROR` | 失败（含越权/上下文缺失），**HTTP 仍 200** | `code`（401/403/404/429/503/500）/ `content` |
| `END` | 结束 | `finishReason`：`STOP` / `DEGRADED` / `ERROR` / `BUSY` / `REPLAYED` |

**为什么错误走 SSE 事件而非 HTTP 状态码**：装配阶段（Feign + JDBC + 向量检索）必然发生在首个事件之前，但一旦订阅开始，HTTP 头就已是 `200 OK + text/event-stream`，此后再改状态码无意义。这也是主流流式 API 的通行做法。

**两类错误的边界**（前端必须都处理）：
- **订阅前**抛异常（未登录）→ HTTP 200 + `application/json` + `{"code":401,...}`；
- **订阅后**失败（越权 403 / 不存在 404 / 上游不可用 503）→ `text/event-stream` + `ERROR` 事件。

### 4.2 其余接口

| 方法 | 路径 | 返回 | 权限 |
|---|---|---|---|
| POST | `/ai/review` | `R<ReviewVO>` | 登录；学员仅限自己的提交 |
| GET | `/ai/review/{submissionId}` | `R<List<ReviewVO>>` | 同上 |
| GET | `/ai/review/detail/{reviewId}` | `R<ReviewVO>` | 同上 |
| POST | `/ai/knowledge/upload` | `R<{problemId,sourceType,chunks,total}>` | **教师/管理员** |
| POST | `/ai/knowledge/search` | `R<List<{id,problemId,sourceType,title,score,content}>>` | **教师/管理员** |
| POST | `/ai/knowledge/preview` | `R<{chunkCount,chunks[]}>` | **教师/管理员** |
| GET | `/ai/knowledge/count` | `R<{knowledgeChunks,reviews}>` | **教师/管理员** |
| DELETE | `/ai/knowledge?problemId=` | `R<{problemId,total}>` | **教师/管理员** |

网关路由 `judge-ai: Path=/ai/**`，`metadata.response-timeout: 900000`（15 分钟，覆盖全局 30s 长连接超时）。

### 4.3 内部契约（点评输入侧）

```
GET /internal/submissions/{id}/review-context?maskHidden=true
    ← judge-ai 经 Feign 调用；InternalOnlyGuard 保护；网关无 /internal/** 路由（两道防线）
```

`SubmissionReviewContextDTO` 含：`submissionId` / `userId` / `problemId` / `contestId` / `language` / `code` / `status` / `verdict` / `score` / `timeMs` / `memoryKb` / `caseTotal` / `caseAcCount` / `caseSamples[]` / `compileInfo`。

**`maskHidden` 安全设计（关键）**：`CaseSample.outputDigest` / `stderrDigest` 对隐藏用例**按请求方角色**决定是否下发。学员触发点评时必须 `true`，否则学员只需问 AI"第 3 个用例期望输出是什么"即可绕过 P3 的 oracle 防护 —— 一条典型的"通过新增功能侧信道绕开既有安全控制"路径。

遮蔽在**请求上游时就传下去**，而不是拿到数据后本地擦除：数据一旦进入 judge-ai 的堆内存，就多了一处泄漏面。

---

## 五、数据模型（PostgreSQL `judge_ai`）

```sql
knowledge_chunk(id, problem_id, source_type, title, content, embedding vector(1024), create_time)
  · source_type: STATEMENT / EDITORIAL / ERROR_PATTERN / TAG_NOTE
  · HNSW (embedding vector_cosine_ops) + btree(problem_id, source_type)

ai_review(id, submission_id, user_id, problem_id, review_type, model, verdict, prompt_digest,
          content, embedding vector(1024), tokens_in, tokens_out, status, error_msg,
          create_time, update_time)
  · review_type: 1错误诊断 2主动点评 3相似题推荐
  · status: 0生成中 1完成 2失败
  · HNSW (embedding vector_cosine_ops) WHERE status=1 AND embedding IS NOT NULL  ← 部分索引
  · btree(submission_id), btree(user_id, create_time DESC)
```

**维度 1024 的由来**：智谱 `embedding-3` 合法维度为 256/512/1024/2048（不含 1536）；pgvector 的 HNSW 对 `vector` 上限 2000 维，故 2048 维无法建索引。取交集 → 1024。

**幂等迁移**：`CREATE TABLE IF NOT EXISTS` 对已存在的表是空操作、不会补列。因此 `init.sql` 在 CREATE 之后额外显式 `ALTER TABLE ai_review ADD COLUMN IF NOT EXISTS embedding vector(1024);` —— 新库（列已在 CREATE 里）与旧数据卷（补列）都收敛到同一结构，无需人工判断该执行哪一段。

**为什么 embedding 与正文同表而不另建 `review_chunk`**：一条点评就是一个天然语义单元（不像题面需要切片），单独建表只多一张表、多一次 JOIN，换不来收益。切片只对"长文档"有意义。

**向量与正文分两步写**：向量化要调外部 Embedding 接口，失败率与耗时都不可控，而正文是用户可见的成果。分开后"Embedding 挂了"只影响后续的历史点评召回，不会让本次点评内容丢失。**降级内容不入索引**（全篇雷同的模板文档入库只会在检索时霸榜）。

---

## 六、安全设计汇总

| 风险 | 处置 |
|---|---|
| 学员点评他人提交 → 侧信道读代码 | `ReviewContextService#fetchOwnedSubmission` 强制归属校验，三态 503/404/403，**在任何检索之前**执行 |
| 学员借 AI 问出隐藏用例期望输出 | `maskHidden` 按角色下发，上游即遮蔽（见 §4.3） |
| Student 写入知识库 → 向全体学员的点评注入文本 | 知识库全部端点 `requirePrivileged`（间接提示注入入口） |
| WebFlux 下 Guard 恒判"内部调用"而放行 | 禁用 `UserContext`/`InternalOnlyGuard`/`OwnerAccessGuard`，改用 `AiIdentity`（见 §3.7） |
| 畸形身份头 → 500（廉价 DoS） | `AiIdentity` 容错为 null → 401 |
| 异常消息外泄内部 URL/主机名 | `safeMessage` 收敛；原始异常仅记日志 |
| 越权请求消耗 Embedding 配额 | 鉴权先于检索 |
| 自我介绍泄漏（AI 输出完整可提交题解） | `cj.review.allow-full-solution=false`，Prompt 明令"不给完整代码，只给关键片段（≤10 行）" |
| 模型编造用例数据 | Prompt 硬约束第 2 条 + `caseDigestMasked` 上下文 |

---

## 七、验证实证

### 7.1 编译（可复现）

```
mvn -o clean install -DskipTests
→ BUILD SUCCESS，11 模块全绿（judge-common / judge-api / judge-gateway / judge-auth /
  judge-user / judge-problem / judge-submission / judge-worker / judge-contest / judge-ai）
```

### 7.2 制品构成（`judge-ai/target/judge-ai.jar`，158 个 lib）

| 期望 | 实测 |
|---|---|
| `spring-webflux` + `reactor-netty` 在 | ✅ `spring-webflux-6.1.14` / `reactor-netty-http-1.1.23` |
| `jakarta.servlet` 不在 | ✅ **NONE** |
| `tomcat-embed-core` 不在 | ✅ 无（仅 `tomcat-embed-el`，来自 validation，无 Servlet 容器语义） |
| `pgvector` + `postgresql` 在 | ✅ `pgvector-0.1.5` / `postgresql-42.7.4` |
| `rocketmq-client` 在 | ✅ `4.9.7` |
| `mybatis` / `mysql` 不在 | ✅ 均 **NONE**（`MybatisConfig` 的 `@ConditionalOnClass` 因此不生效） |

### 7.3 运行时契约实测（**真实起服务**，非静态检查）

`python scripts/dev-start-backend.py judge-ai --wait` →

```
Netty started on port 9087 (http)
Started AiApplication in 5.925 seconds
监听：TCP 0.0.0.0:9087 LISTENING
```

| # | 用例 | 期望 | 实测 |
|---|---|---|---|
| 1 | 无身份头 `GET /ai/review/stream?submissionId=1` | 401 | ✅ `HTTP 200 application/json` `{"code":401,"msg":"请先登录后再使用 AI 点评"}` |
| 2 | 携带合法身份头、上游 judge-submission 不可用 | SSE ERROR 事件 | ✅ `HTTP 200 text/event-stream` → `ERROR{code:503,"无法读取该提交的判题信息，请稍后重试"}` + `END{finishReason:"ERROR"}` |
| 3 | `POST /ai/review/stream` + JSON body | 同上 | ✅ 与 #2 完全一致（GET/POST 同一逻辑） |
| 4 | 非流式 `POST /ai/review` | JSON 业务码 | ✅ `{"code":503,"msg":"无法读取该提交的判题信息，请稍后重试"}` |
| 5 | 学员角色访问 `/ai/knowledge/count` | 403 | ✅ `{"code":403,"msg":"该操作仅限教师或管理员"}` |
| 6 | **畸形身份头** `user-info: abc` | 401（**不得 500**） | ✅ `{"code":401,...}` |
| 7 | 缺失 `submissionId` | 结构化 ERROR 400 | ✅ `ERROR{code:400,"submissionId 不能为空"}` |
| 8 | 事件帧格式 | `id:` / `event:message` / `data:{json}` | ✅ 与前端解析器一致 |

**#2/#3 的意义**：这两条在**没有任何基础设施**（无 PG / 无 Redis / 无 judge-submission）的条件下，完整跑通了"Netty → WebFlux 控制器 → 身份解析 → boundedElastic 上 Feign 失败 → 异常转 ERROR 事件 → SSE 编码 → 分块写出"的全链路。也就是说 §4.1 定义的 SSE 契约与错误降级契约是**实测成立**的，不是纸面设计。

### 7.4 验收脚本自测

- `scripts/verify-p5.py` 语法编译通过；
- 其 SSE 解析器用 #2 的**真实报文**回灌自测：正确识别心跳块（`comments=['ping']`）、提取 `id`/`event`/`data`、`data` 可 JSON 解析。

### 7.5 全链路验收（已执行，**PASS=46 FAIL=0 SKIP=0**）

环境恢复（Docker Desktop 29.7.2 可用）后完整执行。

**前置：PG schema 幂等迁移**

Docker volume 已存在时 `docker-entrypoint-initdb.d` **不会重跑**，`ai_review.embedding` 列会缺失。
`deploy/pgvector/init.sql` 全程使用 `IF NOT EXISTS`，把它对运行中的库重放一次即可收敛：

```bash
docker exec -i codejudge-pg psql -U postgres -d judge_ai -v ON_ERROR_STOP=1 < deploy/pgvector/init.sql
```

实测**连续执行两次均 `EXIT=0`**（第二次全部为 `NOTICE ... already exists, skipping`），
即「新库 / 已有旧卷 / 重复执行」三态收敛到同一结构，无需人工判断该跑哪一段。

**执行序列**

```bash
docker-compose up -d                       # 4 容器（mysql/redis/postgres/rocketmq）
python scripts/dev-start-backend.py --wait # 8 服务，约 30s 全部 UP
python scripts/verify-p5.py                # A–M 段，约 30s（降级模式；接真实 LLM 会更久）
```

**分段结果**

| 段 | 覆盖 | 结果 |
|---|---|---|
| A | 响应式栈（Netty 9087）+ 健康（含 PG 连通） | 3/3 |
| B | 入口鉴权：无身份头 / 畸形头 / 半身份头 → 401（**不得 500**） | 3/3 |
| C | 知识库角色权限：学员 403 / 教师 200 | 2/2 |
| D | pgvector：切片预览、入库、`replace` 幂等、向量检索命中、相似度区间、计数 | 7/7 |
| E | SSE 契约：`START→RETRIEVAL→DELTA×17→END`、reviewId、model、增量正文 810 字符 | 9/9 |
| F | `seq` / SSE `id` 严格单调（样本 20） | 2/2 |
| G | 降级路径：`degraded=true` + `finishReason=DEGRADED` + 结构化正文 + 不返 5xx | 4/4 |
| H | 落库与历史：`status=1`、正文一致、外键回填、历史可列 | 4/4 |
| I | 越权负例：点评他人 / 查他人历史 / 查他人详情 → ERROR 403 或 403 | 3/3 |
| J | `Last-Event-ID` 断线重连：只回放 `seq > N`、以 `REPLAYED` 收尾 | 3/3 |
| K | **网关端到端**：经 9080 的 `/ai/**` 完成完整流式点评（SSE 透传） | 2/2 |
| L | 边界：`submissionId` 缺失 → `ERROR{400}`；不存在 → `ERROR{503}`（非 5xx 崩溃） | 2/2 |
| M | 非流式：返回完整正文 + 落库 `status=1` | 2/2 |

### 7.6 ⚠️ 验收捕获的缺陷（1 个核心 bug + 3 处脚本缺陷）

#### 核心 bug（真缺陷，会造成**静默数据损坏**）

`AiReviewRepository.insert` 原用 `Statement.RETURN_GENERATED_KEYS` + `KeyHolder.getKey()` 取自增主键。

**PG 驱动在该模式下把「整行所有列」都作为返回的 key**，`getKey()` 直接抛：

```
InvalidDataAccessApiUsageException: The getKey method should only be used when a single key is returned.
The current key entry contains multiple keys: [{id=2, submission_id=..., user_id=..., ...}]
```

（MySQL 驱动只返回自增主键列 —— 这是**典型的 MySQL→PG 迁移陷阱**。原代码的注释还写着
「用 KeyHolder 而不是 RETURNING，是为了让同一份代码在 PG 与其它库上都成立」，
这个未经验证的可移植性假设正是 bug 的成因。）

**后果链（全程不抛错、只在 DB 里留痕）：**

1. `INSERT` 其实**成功了** —— 记录确实写进了 `ai_review`；
2. 但 `persistPending` 捕获异常后按设计「降级为无记录」→ 返回 `null`；
3. `START` 事件因此 `reviewId=null`（客户端拿不到 id，无法跳历史页）；
4. `persistSuccess(null, ...)` 首行 `if (reviewId == null) return;` → **正文永不落库**；
5. 记录**永久停在 `status=0`（"生成中"僵尸行）**，且非流式接口 `findById(null)` → **返回空 body**。

**修复**：改用 PG 惯用的 `INSERT ... RETURNING id` + `queryForObject(sql, Long.class, args...)`，
并把成因写进注释。修复后实测 `reviewId=11`、`status=1`、正文 810 字符、非流式 `id=13 status=1`。

> **为什么编译、静态审视、单元测试都发现不了**：类型正确、SQL 正确、逻辑分支正确，
> 只有真实连上 PG 执行一次才暴露。这是端到端验收不可被替代的直接证据。

#### 脚本侧 3 处缺陷（均已修正）

| # | 缺陷 | 现象 | 修正 |
|---|---|---|---|
| 1 | 直连 9087 时未注入网关身份头 | C / D / E–J / L / M 共 25 项 FAIL，全部 401 | 从登录 JWT 的 `userId` / `roleId` claim 还原 `user-info` / `role-info`（与网关同源） |
| 2 | 走网关的调用误用了直连头 | `E1` 提交返回 401，E 段整段 SKIP | 明确区分两套头：`/submissions` 用 `Authorization`，直连段用身份头 |
| 3 | **3 处空集「假绿」** | `F1`/`F2`/`G1`/`G4` 在**零事件**时仍报 PASS | 加样本数前置（`len(seqs) >= 2`、`bool(start)` 等） |

**第 3 类最危险**：`all()` 对空序列返回 `True`，于是「SSE 完全没跑起来」被误报成「单调性正确」。
若没有这次真实执行，这些**假绿**会把一个全链路失效的版本装饰成全绿。
判定原则：**任何 `all(...)` / `not any(...)` 形式的断言，都必须同时断言样本非空。**

### 7.7 验收未覆盖的部分（明确声明）

- **未接真实 LLM 推理**：`.env` 中 `CJ_LLM_ENABLED=false`，故 G 段断言的是**降级分支**（已全绿）。
  接真实 `CJ_LLM_API_KEY` 后应重跑一次，届时 G 段会切换到「非降级」分支断言。
- **未压测**：SSE 并发上限（`CJ_RAG_MAX_STREAMS=200`）、大流量下的 Redis 缓冲体积未测，归 P6。
- **点评质量**未做人工评估（属效果评估，非功能验收）。

---

## 八、有意偏差与 TODO

### 有意偏差

1. **不保留 Agent 继承树**（底座有 `AbstractAgent` + `RouteAgent` + 4 个领域 Agent）。本项目只有一个领域，一个实现类的抽象层是纯负债。
2. **`ChatSession` 未移植**，改用按提交粒度的 `ChatMemory` 会话（§3.6）。
3. **知识库端点限特权角色**（底座 `KnowledgeController` 的权限更宽松）。理由见 §六。
4. **事件缓冲内容与底座不同**（§3.2）。
5. **`ai_review` 与 `knowledge_chunk` 同库**（PG `judge_ai`），与其余 5 个 MySQL 库分离。理由：向量检索依赖 pgvector，跨库 JOIN 不可行。

### TODO

| # | 项 | 触发条件 |
|---|---|---|
| 1 | **接真实 LLM 后复跑验收**（G 段会切换到「非降级」分支断言） | 配置 `CJ_LLM_API_KEY` 时 |
| 2 | 切片量上万时改**部分索引**或"先 HNSW 取大 K 再应用层过滤" | 知识库规模增长 |
| 3 | MQ 预生成启用 + judge-submission 在 RESULT 时投递 `ai_review#REQUESTED` | 需要"进详情页即有点评"的体验时 |
| 4 | SSE 并发上限（`CJ_RAG_MAX_STREAMS`，默认 200）压测 | P6 |
| 5 | LLM token 用量落库（`tokens_in/out` 目前恒 null） | 接入真实计费时 |
| 6 | 点评质量评估（人工打分 / 回归集） | P6 |
| 7 | `docs/PROMPT-ARCHIVE.md` §A 回填总提示词原文 | 需要重新比对需求时 |

### 已知未做（明确声明，避免误判为遗漏）

- ~~未跑 LLM 真实推理~~ → **降级路径已实测通过**（§7.5 G 段）；真实推理待配置 `CJ_LLM_API_KEY` 后复跑。
- ~~未跑 `Last-Event-ID` 回放~~ → **已实测通过**（§7.5 J 段，含 `REPLAYED` 收尾）。
- ~~未跑知识上传/检索~~ → **已实测通过**（§7.5 D 段，含 `replace` 幂等与 HNSW 命中）。

---

## 九、当前状态

| 项 | 状态 |
|---|---|
| 代码 | ✅ 完成，11 模块编译绿 |
| 响应式栈 | ✅ 实测确认（Netty 9087，无 Tomcat） |
| SSE 契约 | ✅ 实测通过（增量、心跳、错误降级、断线回放） |
| **全链路验收** | ✅ **PASS=46 / FAIL=0 / SKIP=0**（§7.5） |
| 落库链路 | ✅ 实测通过（§7.6 核心 bug 修复后） |
| 前端示例 | ✅ 交付（HTML + JS 模块 + 接入说明） |

**结论**：P5 已**通过端到端验收**。

验收过程捕获并修复了 **1 个会造成静默数据损坏的核心缺陷**（PG 自增主键回填失败，
详见 §7.6）—— 该缺陷编译通过、静态审视无异常、SQL 本身正确，**只有真实连 PG 执行才暴露**。
这正是端到端验收不可被替代的直接证据。剩余 TODO 均为增强项，不阻塞 P5 收口。
