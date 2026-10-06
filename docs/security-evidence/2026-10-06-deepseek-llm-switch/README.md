# LLM 上游切换：bigmodel → DeepSeek-V4.1-Flash（B2 关闭）——2026-10-06

状态：**✅ 切换完成并通过真实链路验证，上线前最后一项 B2（上游 429）就此关闭**。

## 变更（只动 gitignored `.env` 三行，零镜像重建）

| 项 | 旧 | 新 |
|---|---|---|
| `CJ_LLM_BASE_URL` | `https://open.bigmodel.cn/api/paas/v4` | `https://api.deepseek.com` |
| `CJ_LLM_MODEL` | `glm-4.5-air` | `deepseek-flash`（官方 ID = DeepSeek-V4.1-Flash，2026-09-10 发布） |
| `CJ_LLM_API_KEY` | 智谱 key | DeepSeek key（**用户亲手写入 .env**，任何命令/日志/上下文全程未出现其值——安全策略要求） |

`CHAT_PATH=chat/completions`、流式、temperature/top_p 均兼容未动。yml/Java 兜底默认仍指
bigmodel（.env 常置永不触发；如需清理属可选后续项）。

## 已知取舍（如实记录）

**RAG 向量化降级为伪向量**：DeepSeek 官方 API 无 embeddings 端点（搜索中唯一声称有的
`zh.deepseek-air.com.cn` 为冒名站点，已排除），judge-ai chat/embedding 共用单一 base-url，
切换后 embedding 404 → 走既有容错路径（日志可见 EmbeddingService WARN，按设计工作）。
AI 点评/聊天不受影响。恢复 RAG 语义的后续选项：① 代码加独立 embedding base-url 指向可用
embedding 服务（需重建 judge-ai 镜像）；② 本地 Ollama 挂 OpenAI 兼容 embedding。

## 验证证据（真实链路，非回环）

| 检查 | 结果 |
|---|---|
| 容器内配置 | `base=https://api.deepseek.com model=deepseek-flash`（judge-ai 重建后 healthy） |
| E2E 全链（fixture → 5 条链） | ✅ **5/5（20.7s）**，T4 AI 点评链路 **7.7s**（模板降级时仅 1.8s——流式真实生成耗时特征） |
| judge-ai 日志 | ✅ **零 chat 失败标记**（`流式调用失败`/429/401 全部缺席；仅 Embedding 404 降级 WARN=已披露取舍） |
| **DB 落库正文（决定性）** | `ai_review` 最新 2 条（16:32:47 / 16:33:12，恰为本次 E2E）：**`model=deepseek-flash`、`status=1`**、content 为真实题意分析（“用 long 承接输入规避 32 位溢出，4/4 用例”——非结构化模板） |
| tokens_in/out | 空值——流式调用未回传 usage（OpenAI 兼容流式需显式 `stream_options.include_usage`）；model+status+正文已足够定性，usage 回传列为可选后续优化 |

## 结论

- **B2（LLM 上游 429）关闭**：新上游真实出正文，登记册 TC-LLM-01 PASS。
- 上线前工程队列**全部清空**（U5/U6 收口、U8/U9 决策关闭、B2 关闭）。
- 提醒：embedding 降级状态下，RAG 命中资料不具语义（仅链路可跑通）——与点评正文无关。
