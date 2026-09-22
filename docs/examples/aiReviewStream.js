/**
 * CodeJudge AI 点评 · SSE 客户端（框架无关，可直接 import 进 Vue / React / 原生项目）
 * ----------------------------------------------------------------------------
 * 为什么不用 EventSource：
 *   1. EventSource 无法设置 Authorization 请求头 —— 本平台需要 JWT；
 *   2. EventSource 只能 GET，无法携带较长的追问文本；
 *   3. 无法在重连时自定义 Last-Event-ID（浏览器虽然会自动带，但只在它自己重连时，
 *      我们无法控制重试节奏与退避策略）。
 * 因此用 fetch + ReadableStream 手工解析 SSE。解析规则见 parseSseFrame 注释。
 *
 * 用法：
 *   import { streamReview } from './aiReviewStream.js';
 *
 *   const task = streamReview({
 *     baseUrl: 'http://localhost:9080',
 *     token,                       // JWT，不含 "Bearer "
 *     submissionId: 123,
 *     reviewType: 1,               // 1错误诊断 2主动点评 3相似题推荐
 *     question: '边界情况怎么改？',
 *     onStart:     (e) => { ... }, // { reviewId, degraded, model }
 *     onRetrieval: (e) => { ... }, // { content, sources }
 *     onDelta:     (e) => { ... }, // { content }  ← 增量片段
 *     onError:     (e) => { ... }, // { code, content } 业务错误（HTTP 仍是 200）
 *     onEnd:       (e) => { ... }, // { finishReason } STOP / DEGRADED / ERROR / BUSY / REPLAYED
 *   });
 *
 *   task.abort();                  // 中断（服务端会落库已生成部分）
 */

/** 事件类型常量（与后端 ReviewEventVO.type 一一对应） */
export const ReviewEventType = Object.freeze({
  START: 'START',
  RETRIEVAL: 'RETRIEVAL',
  DELTA: 'DELTA',
  ERROR: 'ERROR',
  END: 'END',
});

/**
 * 解析一个完整的 SSE 事件块（已按空行切分好）。
 * 规范要点：
 *   · 以 ':' 开头的是注释行（服务端心跳 `:ping`）—— 必须忽略，否则会被当成业务数据；
 *   · data 可多行，用 '\n' 拼接；
 *   · 冒号后的**第一个空格**要去掉（`data: x` → `x`）。
 */
function parseSseFrame(rawBlock) {
  if (!rawBlock) return null;
  const frame = { id: null, event: 'message', data: '', comments: [] };
  let hasField = false;
  for (const line of rawBlock.split(/\r\n|\n|\r/)) {
    if (!line) continue;
    if (line.startsWith(':')) {
      frame.comments.push(line.slice(1).trim());
      continue;
    }
    const colon = line.indexOf(':');
    const field = colon === -1 ? line : line.slice(0, colon);
    let value = colon === -1 ? '' : line.slice(colon + 1);
    if (value.startsWith(' ')) value = value.slice(1);
    if (field === 'id') { frame.id = value; hasField = true; }
    else if (field === 'event') { frame.event = value; hasField = true; }
    else if (field === 'data') { frame.data += (frame.data ? '\n' : '') + value; hasField = true; }
  }
  return hasField ? frame : null;
}

/**
 * 建立一次流式点评。
 * @param {object} opts 见文件头用法
 * @returns {{ abort: () => void, done: Promise<void> }}
 */
export function streamReview(opts) {
  const {
    baseUrl = '',
    token,
    submissionId,
    reviewType = 1,
    question = null,
    reconnect = true,        // 断线时是否自动用 Last-Event-ID 续传
    maxRetries = 1,
    onStart, onRetrieval, onDelta, onError, onEnd, onOpen, onRawEvent, onHttpError,
  } = opts;

  const controller = new AbortController();
  let lastEventId = null;    // 已收到的最大事件 id
  let acc = '';              // 正文累积
  let retries = 0;
  let ended = false;

  /** 单次连接；返回 true 表示正常结束（收到 END） */
  async function once(isRetry) {
    const headers = {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      Authorization: `Bearer ${token}`,
    };
    if (isRetry && lastEventId !== null) headers['Last-Event-ID'] = String(lastEventId);

    const resp = await fetch(`${baseUrl.replace(/\/+$/, '')}/ai/review/stream`, {
      method: 'POST',
      headers,
      body: JSON.stringify({ submissionId, reviewType, question }),
      signal: controller.signal,
    });

    // 网关层拒绝（401/403/429…）不会是 text/event-stream，必须单独识别，
    // 否则会把「没登录」误报成「AI 生成失败」。
    const ctype = resp.headers.get('content-type') || '';
    if (!resp.ok || !ctype.includes('text/event-stream')) {
      const text = await resp.text().catch(() => '');
      let msg = text, code = resp.status;
      try { const j = JSON.parse(text); msg = j.msg || j.message || text; code = j.code || resp.status; } catch (_) {}
      if (onHttpError) onHttpError({ code, status: resp.status, message: msg });
      return true;   // HTTP 层已明确失败，不再重试
    }

    if (onOpen) onOpen();

    const reader = resp.body.getReader();
    const decoder = new TextDecoder('utf-8');
    let buffer = '';
    let localEnd = false;

    const dispatch = (frame) => {
      if (frame.id !== null && frame.id !== '') lastEventId = Number(frame.id);
      if (onRawEvent) onRawEvent(frame);
      if (!frame.data) return;                       // 纯心跳块
      let vo;
      try { vo = JSON.parse(frame.data); } catch (_) { return; }

      switch (vo.type) {
        case ReviewEventType.START:
          acc = '';
          ended = false;
          if (onStart) onStart(vo);
          break;
        case ReviewEventType.RETRIEVAL:
          if (onRetrieval) onRetrieval(vo);
          break;
        case ReviewEventType.DELTA:
          acc += vo.content || '';
          if (onDelta) onDelta(vo, acc);             // 第二参：累积正文，便于直接渲染
          break;
        case ReviewEventType.ERROR:
          if (onError) onError(vo);
          break;
        case ReviewEventType.END:
          localEnd = true;
          ended = true;
          if (onEnd) onEnd(vo, acc);
          break;
      }
    };

    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      // stream:true —— 中文按 UTF-8 最多 3 字节，跨 chunk 时必须保留半个字符
      buffer += decoder.decode(value, { stream: true });
      let idx;
      while ((idx = buffer.search(/\r\n\r\n|\n\n|\r\r/)) !== -1) {
        const raw = buffer.slice(0, idx);
        const sep = /^\r\n\r\n/.test(buffer.slice(idx)) ? 4 : 2;
        buffer = buffer.slice(idx + sep);
        const frame = parseSseFrame(raw);
        if (frame) dispatch(frame);
      }
    }
    buffer += decoder.decode();
    for (const raw of buffer.split(/\r\n\r\n|\n\n/)) {
      const frame = parseSseFrame(raw);
      if (frame) dispatch(frame);
    }
    return localEnd;
  }

  const done = (async () => {
    for (;;) {
      let ok;
      try {
        ok = await once(retries > 0);
      } catch (e) {
        if (e.name === 'AbortError') return;          // 用户主动中断
        ok = false;
        if (onHttpError) onHttpError({ code: 0, status: 0, message: e.message || String(e) });
      }
      if (ok || ended) return;
      if (!reconnect || retries >= maxRetries || lastEventId === null) return;
      retries++;
      // 退避重连：携带 Last-Event-ID，服务端只回放未送达事件，不重新生成（省 token、不重复内容）
      await new Promise(r => setTimeout(r, 600 * retries));
    }
  })();

  return { abort: () => controller.abort(), done, received: () => acc, lastEventId: () => lastEventId };
}

export default streamReview;
