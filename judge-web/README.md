# judge-web · CodeJudge 前端

Vue 3 + Vite 单页应用。覆盖面：登录/注册、题库与在线判题、提交记录与判题详情（含 WebSocket 实时进度）、
竞赛与实时榜单（含 WebSocket 推送）、AI 代码点评（SSE 流式）、教师工作台（建题/用例/竞赛/知识库）、
管理端（用户/标签/判题集群/系统监控）。

---

## 一、快速开始

```bash
cd judge-web
npm install
npm run dev          # http://localhost:5174（strictPort，端口被占直接失败而不是漂移到 5175）
```

生产构建：

```bash
npm run build        # 产物在 judge-web/dist
npm run preview      # 本地预览产物
```

> 需要后端已启动（网关 `:9080` 起）。启动方式见仓库根 `README.md`。

演示账号：学员 `13900000001` / 密码 `123456`。

---

## 二、请求地址策略（改之前请先读这段）

前端**默认直连网关**（`http://<当前页面主机名>:9080`），而不是走 Vite 代理。两个理由：

1. **网关的 CORS 是已支持的一等路径**。`allowedOriginPatterns` 同时覆盖 `localhost` 与 `127.0.0.1`
   且端口通配（见 `judge-gateway/application.yml` 的踩坑注释）。绕过它，生产部署（前后端分域）
   这条路径就从未被验证过。
2. **SSE 与 WebSocket 都是长连接**，经代理多一层缓冲/超时，出问题无法区分"是代理还是后端"。

基址推导刻意不用写死的 `localhost`，而是取 `window.location.hostname`：

- 页面开在 `http://localhost:5174` → 接口 `http://localhost:9080`
- 页面开在 `http://127.0.0.1:5174` → 接口 `http://127.0.0.1:9080`

原因是 refresh token 走 HttpOnly Cookie 且 `SameSite=Lax`。若页面在 `127.0.0.1` 而接口写死 `localhost`，
两者属于**不同 site**，XHR 不会带上该 cookie，表现为"能登录、过一会儿被踢下线"。

需要同源代理时，设 `VITE_API_BASE_URL=/api`（`vite.config.js` 里已配好 `/api` → `:9080` 的代理，
含 `ws: true`）。

完整变量说明见 [`.env.example`](./.env.example)。

---

## 三、目录结构

```
judge-web/
├── index.html
├── vite.config.js          构建/代理/分包配置
├── .env.example
└── src/
    ├── main.js             挂载入口；主题与登录态在挂载路由**之前**初始化
    ├── App.vue             仅承载 <router-view/>
    ├── api/
    │   ├── base.js         基址推导 + wsUrl（含"路径已带 ? 时用 & 续接"的处理）
    │   ├── http.js         axios 实例：R<T> 解包、错误归一、401 单飞刷新
    │   └── index.js        全部接口封装 + actuator 探测
    ├── stores/
    │   ├── user.js         登录态、角色判定、loadProfile（仅 401 才清登录态）
    │   └── theme.js        暗色/亮色，写入 localStorage，挂载前生效避免 FOUC
    ├── router/index.js     路由表 + 守卫（public / 需登录 / 角色白名单）
    ├── layouts/AppLayout.vue  顶栏、角色化导航、移动端抽屉
    ├── components/
    │   ├── CodeEditor.vue      行号 + Tab 缩进 + 高亮的轻量编辑器（方案取舍见文件头注释）
    │   ├── MarkdownView.vue    Markdown 渲染（DOMPurify 消毒）
    │   ├── VerdictTag.vue      判题结论标签
    │   ├── EChartPanel.vue     ECharts 容器（主题切换重建实例）
    │   └── AiReviewPanel.vue   AI 点评 SSE 面板
    ├── composables/
    │   ├── useSubmissionProgress.js   判题进度 WS 订阅
    │   └── useContestRank.js          竞赛榜单 WS 订阅（封榜语义）
    ├── utils/
    │   ├── sse.js          SSE 客户端（fetch + ReadableStream 手工解析）
    │   ├── ws.js           WebSocket 封装（指数退避重连）
    │   ├── highlight.js    highlight.js 按需注册（全量引入会多 1MB）
    │   ├── markdown.js     markdown-it + DOMPurify
    │   ├── prometheus.js   /actuator/prometheus 文本解析
    │   └── format.js       枚举→中文、时间、体积、罚时等格式化
    ├── styles/main.css     主题变量 + 响应式基线
    └── views/              页面（含 teacher/ 与 admin/ 子目录）
```

---

## 四、几个关键实现细节

### 4.1 客户端不能到处 `.data.data`

后端所有业务响应是 `R<T>` = `{code, msg, data, requestId}`。`api/http.js` 的响应拦截器按 `code`
判定，成功**直接返回 `data`**，因此页面里写的是 `const page = await problemApi.page(...)` 而不是
`res.data.data`。失败统一抛带 `code` 与 `message` 的 Error。

### 4.2 401 单飞刷新

access token 只有 30 分钟。并发请求同时 401 时，如果每个都去调 `/accounts/refresh`，
会打出一串刷新请求并撞上登录限流。`http.js` 用一个共享 Promise 做**单飞**：只有第一个 401
触发刷新，其余等待同一个结果，然后各自重放原请求（`__retried` 标记防循环）。

### 4.3 为什么 AI 点评不用 `EventSource`

无法设置 `Authorization` 头、只能 GET（追问文本塞不下 URL）、无法自控 `Last-Event-ID`。
因此用 `fetch` + `ReadableStream` 手工解析，实现在 `utils/sse.js`。

两个必须知道的契约点（详见 `docs/P5-前端SSE接入说明.md`）：

- **两类错误的 HTTP 状态码都是 200**，判据是 `Content-Type`：
  订阅前失败（未登录）= `application/json`；订阅后失败（越权/上游不可用）= `text/event-stream` + `ERROR` 事件。
- **增量渲染用"全量重渲染"而不是追加 DOM**。Markdown 是上下文相关的（`## 标题` 只有独占一行才成立），
  边收边追加会产出空标题等碎裂结构。正文通常 1–3 KB，重渲染代价无感。

### 4.4 封榜的两个字段别混用

榜单视图里 `frozen`（本视图是否为冻结榜）、`fullView`（本响应是否绕过封榜）、
`inFreezeWindow`（当前是否处于封榜时段，与视图无关）语义不同。前端用 `frozen` 决定是否打"封榜中"水印，
用 `fullView` 决定是否提示"你正在看实时榜"。混用会导致"管理员以为自己在看冻结榜"这类误判。

### 4.5 模板代码的键名是小写

题库存储的 `template_code` JSON 键名是 `java` / `cpp` / `python` / `go`（小写），
而提交接口要求的 `language` 是**大写**枚举名（`JAVA` / `CPP` / `PYTHON` / `GO`）。
前端用 `templateCode[language.toLowerCase()]` 取值，缺失时退化到内置骨架模板，
保证编辑器永远不会是空白。

### 4.6 代码编辑器为什么不是 Monaco

Monaco 打包后体积大（数十 MB 量级）且与 Vite 的 worker 配置有额外集成成本。
这里用"透明 textarea 叠加 highlight.js 高亮层"：体积≈0（复用已注册的高亮语言）、
含行号/Tab 缩进/滚动同步。

**不具备**的能力（明确说明，不假装有）：代码补全、语法诊断、多光标。
若后续需要，替换 `components/CodeEditor.vue` 即可 —— 父组件的 `v-model + language` 契约不变。

---

## 五、响应式

- 断点：`1100px`（题目详情由双栏转单栏）、`900px`（顶栏导航收进抽屉、筛选器全宽）、`640px`（内边距与字号收紧）。
- 宽表格不做列压缩，而是外层 `overflow-x: auto` 横向滚动（`.cj-scroll-x`），
  否则在窄屏上列会被压成不可读。
- 次要列用 `.cj-hide-sm` 在 900px 以下隐藏（ID、时间、内存等）。

---

## 六、排障

| 现象 | 排查方向 |
|---|---|
| 页面能开，一操作就 403（空响应体 + `Vary: Origin`） | 网关 CORS 白名单没覆盖当前来源。检查是否用了非 5174 端口，或把 `CORS_ALLOWED_ORIGINS` 改成了 `*`（与 `allow-credentials: true` 冲突，浏览器会拒绝） |
| 登录后过一会儿被踢下线 | refresh cookie 未带上。确认页面与接口**同 site**（都用 localhost 或都用 127.0.0.1） |
| 所有请求失败，提示"无法连接网关" | 网关 `:9080` 未启动。`python scripts/dev-start-backend.py --wait` |
| AI 点评一直转圈 | 首 token 可能要 5–20 秒（深度思考型模型）；若 `CJ_LLM_ENABLED=false` 会走降级路径返回模板内容，面板会显示"降级模式"角标 |
| 系统监控页指标全空 | 服务未暴露 `/actuator/prometheus`。P6 已为 8 个服务统一开启（含 `micrometer-registry-prometheus` 依赖） |
| 榜单 WS 连不上 | 竞赛 id 是否正确；非特权账号传 `full=true` 会被服务端以 `ERROR` 关闭连接（fail-closed，不是静默降级） |
