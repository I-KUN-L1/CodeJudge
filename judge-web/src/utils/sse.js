import { API_BASE } from '@/api/base';

/**
 * AI 点评 SSE 客户端（框架无关）
 * ---------------------------------------------------------------------------
 * 为什么不用 EventSource —— 见 docs/P5-前端SSE接入说明.md §一：
 *   ① 无法设置 Authorization 头（本平台必须带 JWT）；
 *   ② 只能 GET，追问文本塞不下 URL；
 *   ③ 无法自主控制 Last-Event-ID 与重试节奏。
 * 因此用 fetch + ReadableStream 手工解析。SSE 本质是"HTTP 分块响应 + 文本分帧"，
 * 解析规则不复杂，换来的是对请求头、重试、取消的完全控制。
 *
 * 本文件与 docs/examples/aiReviewStream.js 同源，差别仅在于 baseUrl 默认取网关地址。
 */

export const ReviewEventType = Object.freeze({
  START: 'START',
  RETRIEVAL: 'RETRIEVAL',
  DELTA: 'DELTA',
  ERROR: 'ERROR',
  END: 'END',
});

/**
 * 解析一个完整 SSE 事件块（已按空行切分）。
 * 规范要点：
 *   · 以 ':' 开头的是注释行（服务端 15s 一次的 `:ping` 心跳）—— 必须忽略，
 *     否则会被当成一条空 data 帧，触发"空 delta 渲染"之类的问题；
 *   · data 可多行，用 '\n' 拼接；
 *   · 冒号后的**第一个空格**要去掉（`data: x` → `x`）。
 */
export function parseSseFrame(rawBlock) {
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
    if (field === 'id') {
      frame.id = value;
      hasField = true;
    } else if (field === 'event') {
      frame.event = value;
      hasField = true;
    } else if (field === 'data') {
      frame.data += (frame.data ? '\n' : '') + value;
      hasField = true;
    }
  }
  return hasField ? frame : null;
}

/**
 * 建立一次流式点评。
 *
 * @param {object} opts
 * @param {string} [opts.baseUrl]     网关地址，默认取 VITE_API_BASE_URL / 自动推导值
 * @param {string}  opts.token        access token（不含 "Bearer "）
 * @param {number}  opts.submissionId
 * @param {number}  [opts.reviewType] 1错误诊断 2主动点评 3相似题推荐
 * @param {string}  [opts.question]   追问内容
 * @returns {{abort: Function, done: Promise<void>, received: Function, lastEventId: Function}}
 */
export function streamReview(opts) {
  const {
    baseUrl = API_BASE,
    token,
    submissionId,
    reviewType = 1,
    question = null,
    reconnect = true,
    maxRetries = 1,
    onOpen,
    onStart,
    onRetrieval,
    onDelta,
    onError,
    onEnd,
    onRawEvent,
    onHttpError,
  } = opts;

  const controller = new AbortController();
  let lastEventId = null; // 已收到的最大事件 id（用于断线续传）
  let acc = ''; // 正文累积
  let retries = 0;
  let ended = false;

  /** 单次连接；返回 true 表示已正常结束（收到 END 或 HTTP 层明确失败） */
  async function once(isRetry) {
    const headers = {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      Authorization: `Bearer ${token}`,
    };
    if (isRetry && lastEventId !== null) {
      headers['Last-Event-ID'] = String(lastEventId);
    }

    const resp = await fetch(`${String(baseUrl).replace(/\/+$/, '')}/ai/review/stream`, {
      method: 'POST',
      headers,
      body: JSON.stringify({ submissionId, reviewType, question }),
      signal: controller.signal,
    });

    // ⚠️ 判据是 Content-Type，不是 resp.ok ——
    // "订阅前失败（未登录）"与"订阅后失败（越权/上游不可用）"的 HTTP 状态码**都是 200**。
    // 前者是 application/json，后者才是 text/event-stream。
    const ctype = resp.headers.get('content-type') || '';
    if (!resp.ok || !ctype.includes('text/event-stream')) {
      const text = await resp.text().catch(() => '');
      let msg = text;
      let code = resp.status;
      try {
        const j = JSON.parse(text);
        msg = j.msg || j.message || text;
        code = j.code || resp.status;
      } catch {
        /* 响应体不是 JSON，直接用原文 */
      }
      if (onHttpError) onHttpError({ code, status: resp.status, message: msg });
      return true; // HTTP 层已明确失败，不再重试
    }

    if (onOpen) onOpen();

    const reader = resp.body.getReader();
    const decoder = new TextDecoder('utf-8');
    let buffer = '';
    let localEnd = false;

    const dispatch = (frame) => {
      if (frame.id !== null && frame.id !== '') lastEventId = Number(frame.id);
      if (onRawEvent) onRawEvent(frame);
      if (!frame.data) return; // 纯心跳块
      let vo;
      try {
        vo = JSON.parse(frame.data);
      } catch {
        return;
      }
      switch (vo.type) {
        case ReviewEventType.START:
          // 每次 START 都要清空累积正文：重连时会再收到一次 START，
          // 不清就会出现"内容翻倍"（P5 接入说明 §七.6 记的正是这个坑）
          acc = '';
          ended = false;
          if (onStart) onStart(vo);
          break;
        case ReviewEventType.RETRIEVAL:
          if (onRetrieval) onRetrieval(vo);
          break;
        case ReviewEventType.DELTA:
          acc += vo.content || '';
          if (onDelta) onDelta(vo, acc);
          break;
        case ReviewEventType.ERROR:
          if (onError) onError(vo);
          break;
        case ReviewEventType.END:
          localEnd = true;
          ended = true;
          if (onEnd) onEnd(vo, acc);
          break;
        default:
          break;
      }
    };

    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      // stream:true 必须加 —— 中文按 UTF-8 最多 3 字节，跨 chunk 时不设会把汉字截半
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
        if (e.name === 'AbortError') return; // 用户主动中断
        ok = false;
        if (onHttpError) {
          onHttpError({ code: 0, status: 0, message: e.message || String(e) });
        }
      }
      if (ok || ended) return;
      if (!reconnect || retries >= maxRetries || lastEventId === null) return;
      retries++;
      // 退避重连：携带 Last-Event-ID，服务端只回放未送达事件（省 token、不重复内容）。
      // 重试次数刻意设得很小 —— 鉴权过期/服务重启这类问题重试不会自愈，只会堆无效请求。
      await new Promise((r) => setTimeout(r, 600 * retries));
    }
  })();

  return {
    abort: () => controller.abort(),
    done,
    received: () => acc,
    lastEventId: () => lastEventId,
  };
}

export default streamReview;
