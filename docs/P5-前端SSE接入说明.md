# P5 前端接入说明 —— AI 代码点评的 SSE 流式调用

> 交付文件：`docs/examples/ai-review-sse.html`（可直接双击打开的完整示例）、
> `docs/examples/aiReviewStream.js`（框架无关可复用模块）、`docs/P5-REPORT.md`（后端契约全貌）

---

## 一、为什么**不能**用 `EventSource`

`EventSource` 是浏览器原生的 SSE 客户端，但本平台的接口它调不了：

| 限制 | 后果 |
|---|---|
| **无法设置请求头** | 无法携带 `Authorization: Bearer <JWT>`。把 token 塞进查询串会进 access log、浏览器历史、Referer |
| **只能发起 GET** | 追问文本（可能数百字）只能塞 URL，受长度限制且有编码问题 |
| **无法指定 `Last-Event-ID`** | 浏览器自动重连时确实会带，但重试节奏与退避策略完全不可控，也无法在"我们判断该重连"时主动带 |
| **只能收不能发** | 无法在同一连接上改变参数 |

因此生产前端统一使用 **`fetch` + `ReadableStream` + `TextDecoder` 手工解析 SSE**。
这不是"绕路"：SSE 只是"HTTP 分块响应 + 一种文本分帧格式"，手工解析约 40 行，
且换来对请求头、重试、取消的完全控制。`docs/examples/aiReviewStream.js` 已封装好。

---

## 二、最小可用代码（复制即用）

```js
import { streamReview } from './aiReviewStream.js';

let acc = '';   // 累积正文

const task = streamReview({
  baseUrl: 'http://localhost:9080',      // 经网关；直连则 http://localhost:9087
  token,                                 // 登录返回的 accessToken（不含 "Bearer "）
  submissionId: 123,
  reviewType: 1,                         // 1错误诊断 2主动点评 3相似题推荐
  question: '',                          // 首轮留空；追问时填新问题

  onOpen:      ()      => setStatus('已连接，等待首个事件…'),

  onStart:     (e)     => {              // e: { reviewId, degraded, model }
    acc = '';
    if (e.degraded) showBadge('降级模式（非 AI 生成）');
  },

  onRetrieval: (e)     => {              // e: { content, sources[] }
    // 检索完成**先于正文**推送 —— 可在正文出现前就把"依据"展示出来
    renderSources(e.sources);
  },

  onDelta:     (e, full) => {            // e: { content }   full: 累积后的完整正文
    acc = full;
    renderMarkdown(acc + '<光标>');       // 增量渲染（见 §四）
  },

  onError:     (e)     => {              // e: { code, content }  ← HTTP 仍是 200！
    showError(`点评失败（${e.code}）：${e.content}`);
  },

  onEnd:       (e)     => {              // e: { finishReason }
    renderMarkdown(acc);                 // 去掉光标
    setStatus('完成：' + e.finishReason);
  },

  onHttpError: (e)     => {              // 网关层拒绝（401/403/429…），非 SSE 协议
    showError(`请求被拒绝（${e.status}）：${e.message}`);
  },
});

// 用户点"中断"
stopButton.onclick = () => task.abort();
```

---

## 三、协议要点（前端必须知道的三件事）

### 3.1 有两类错误，必须分别处理

```
① 订阅前失败（未登录）
   HTTP 200 + Content-Type: application/json
   {"code":401,"msg":"请先登录后再使用 AI 点评","data":null}

② 订阅后失败（越权 403 / 提交不存在 404 / 上游不可用 503）
   HTTP 200 + Content-Type: text/event-stream
   id:0 / data:{"type":"ERROR","code":503,"content":"无法读取该提交的判题信息，请稍后重试"}
   id:1 / data:{"type":"END","finishReason":"ERROR"}
```

**判据是 `Content-Type`，不是 `resp.ok`** —— 两种情况的 HTTP 状态码都是 200。
`aiReviewStream.js` 已按此分流：非 `text/event-stream` 走 `onHttpError`，否则走 SSE 事件。

为什么后端这么设计：装配阶段（Feign + JDBC + 向量检索）必然在首个事件之前发生，
但一旦订阅开始，HTTP 头就已是 `200 + text/event-stream`，此后再改状态码已无意义。
这也是主流流式 API 的通行做法。

### 3.2 事件序列（严格按序）

```
START → RETRIEVAL → DELTA × N → END
```

| 事件 | 何时到 | 前端该做什么 |
|---|---|---|
| `START` | 立即 | 清空正文、初始化 UI；`degraded=true` 时显示醒目提示（避免把模板文案误当 AI 结论）；记下 `reviewId` 供后续跳转 |
| `RETRIEVAL` | 检索完成后、正文之前 | 展示参考来源（`sources[]`），让用户知道"AI 依据了什么" |
| `DELTA` | 生成期间持续 | 累积 `content` 并渲染 |
| `ERROR` | 任何失败 | 显示 `code` + `content`；注意区分 403/404（用户可行动）与 500/503（稍后重试） |
| `END` | 结束 | 去掉光标；`finishReason` 语义见下 |

`finishReason` 取值：

| 值 | 含义 | 前端建议 |
|---|---|---|
| `STOP` | 正常完成 | 正常展示 |
| `DEGRADED` | LLM 未配置，返回结构化模板内容 | 显示"非 AI 生成"角标 |
| `ERROR` | 生成中途失败（正文可能不完整） | 显示部分内容 + 错误提示 |
| `BUSY` | 服务端并发连接数超限 | 提示"当前点评人数较多，请稍后再试" |
| `REPLAYED` | 这是断线重连回放的结束标记 | 正常收尾即可 |

### 3.3 心跳是注释行，不是业务数据

生成期间服务端每 15 秒发一次 `:ping`（SSE 注释行）：

```
:ping

```

它的作用是防止中间代理/网关按空闲超时掐断连接，**没有任何业务含义**。
解析器必须忽略以 `:` 开头的行 —— 否则会被当成一条空的 data 帧，
可能触发"空 delta 渲染"之类的诡异问题。

---

## 四、增量渲染：为什么推荐"全量重渲染"而不是"追加"

Markdown 是**上下文相关**的：`## 标题` 只有在它独占一行时才成立；
表格需要"表头 + 分隔行 + 数据行"三行齐备；围栏代码块需要闭合的 ```。

如果按 DELTA 边收边"追加 DOM"，很容易出现：收到 `##` 时以为是标题就插了 `<h2>`，
下一片 `## 结论` 才到 —— 结果页面上多出一个空的 `<h2>`。

**可靠做法**：每收到一片 DELTA，就把**累积正文**整体重新渲染一次 Markdown。
代价是 O(n²) 的渲染量，但点评正文通常 1–3 KB，现代浏览器下完全无感。
若正文极长，用 `requestAnimationFrame` 节流到 60fps 即可，仍不必做增量 DOM。

`docs/examples/ai-review-sse.html` 用的是这个策略（含一个内联的极简 Markdown 渲染器，
覆盖标题/列表/表格/代码块/引用/行内代码）。**生产项目请换成 marked / markdown-it + DOMPurify**，
示例内联实现只是为了"双击即可运行、不依赖任何 CDN"。

### 打字机光标

渲染时为正文末尾追加一个闪烁光标元素，收到 `END` 后移除。
这是纯 UI 细节，但对"流式感"的感知影响很大。

---

## 五、断线重连：`Last-Event-ID` 语义

服务端把每个事件写入 Redis 缓冲（`judge:ai:sse:review:{submissionId}`，保留最近 200 条），
并给每个事件分配单调递增的 `seq`（与 SSE 的 `id` 一致）。

**重连时把已收到的最大 id 放进 `Last-Event-ID` 头**：

```
Last-Event-ID: 37
```

服务端行为：

- 只回放 `seq > 37` 的事件 —— **不重新生成**（省 token，且前端不会看到重复内容）；
- 回放结束后补一个 `END`，`finishReason = REPLAYED`；
- 若缓冲已过期（超 TTL 或服务重启），返回 `END`，`finishReason = REPLAYED_EXPIRED`，
  前端应提示用户重新发起。

⚠️ 注意：**不要为了"保险"而清空 `lastEventId`**。用户在生成中途点"中断"后想继续时，
保留它才能续传；只有"重新发起一次新点评"时才清空。

`aiReviewStream.js` 默认开启自动重连（1 次，退避 600ms）。重连次数不宜多 ——
真正的问题（鉴权过期、服务重启）重试不会自愈，徒增无效请求。

---

## 六、并发、超时与网关

| 项 | 值 | 说明 |
|---|---|---|
| 网关响应超时 | **900 秒** | 覆盖全局 30s（`judge-ai` 路由 `metadata.response-timeout: 900000`）；SSE 是长连接 |
| 服务端心跳 | 15 秒 | `cj.rag.heartbeat-seconds` |
| 服务端并发上限 | 200 | `CJ_RAG_MAX_STREAMS`；超限返回 `ERROR{429}` + `END{BUSY}` |
| 首 token 等待 | 视模型 | 深度思考型模型可能 5–20 秒，UI 需有"检索中/生成中"状态，否则用户会以为卡死 |

**CORS**：网关已配置 `allowedOriginPatterns`，同时覆盖 `localhost` 与 `127.0.0.1`
（端口通配）。这是踩过的坑 —— 若白名单只有 `http://localhost:5174`，
用户以 `http://127.0.0.1:5174` 打开时，GET 正常但带 `Origin` 的 POST 会被 CORS 403，
表现为"页面打得开、一点点评就失败"。

---

## 七、验收自查清单

前端联调时按这个顺序排查：

1. **401** → token 是否放在 `Authorization: Bearer` 里？网关是否已配置该路由？
2. **403** → 学员只能点评**自己的**提交（`submission.userId` 必须等于当前登录用户）。
3. **Content-Type 不是 `text/event-stream`** → 说明请求在进入 SSE 之前就失败了，读响应体里的 `msg`。
4. **一个事件都没收到，连接直接关闭** → 检查是否有中间代理做了响应缓冲（需关闭 nginx 的 `proxy_buffering`）。
5. **收到正文但乱码** → `TextDecoder` 必须用 `decode(value, { stream: true })`；中文按 UTF-8 最多 3 字节，跨 chunk 时不设 `stream` 会把汉字截成两半。
6. **渲染出重复内容** → `acc` 没有在 `START` 时清空（重连时尤其容易漏）。
7. **收不到 `END`** → 大概率是中间层超时截断（检查网关 `metadata.response-timeout` 是否生效）。
