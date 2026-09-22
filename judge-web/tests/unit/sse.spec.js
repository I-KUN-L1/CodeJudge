import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { parseSseFrame, streamReview, ReviewEventType } from '@/utils/sse';

/**
 * SSE 客户端测试。
 *
 * 这是前端自研的协议解析层（不用 EventSource，理由见 docs/P5-前端SSE接入说明.md §一），
 * 也是**唯一**一处"错了就会静默产出错内容"的地方：
 *   · 注释帧被当成 data → 空 delta 反复触发渲染；
 *   · 多字节汉字跨 chunk 截半 → 正文出现乱码（且只在特定网络分片下复现，极难查）；
 *   · 重连时不清累积缓冲 → 同一段点评内容翻倍。
 * 因此这里用**伪造 fetch**把分片喂进去，逐条锁死行为，而不是只测几个纯函数。
 */

/** 用字符串分片伪造一个 SSE 响应 */
function sseResponse(chunks, { contentType = 'text/event-stream', ok = true, status = 200 } = {}) {
  const encoder = new TextEncoder();
  let i = 0;
  return {
    ok,
    status,
    headers: { get: (k) => (String(k).toLowerCase() === 'content-type' ? contentType : null) },
    text: async () => chunks.join(''),
    body: {
      getReader: () => ({
        read: async () =>
          i < chunks.length
            ? { value: encoder.encode(chunks[i++]), done: false }
            : { value: undefined, done: true },
      }),
    },
  };
}

/** 用原始字节分片伪造响应 —— 用于构造"汉字被切成两半"的分片边界 */
function sseResponseBytes(byteChunks) {
  let i = 0;
  return {
    ok: true,
    status: 200,
    headers: { get: (k) => (String(k).toLowerCase() === 'content-type' ? 'text/event-stream' : null) },
    text: async () => '',
    body: {
      getReader: () => ({
        read: async () =>
          i < byteChunks.length
            ? { value: byteChunks[i++], done: false }
            : { value: undefined, done: true },
      }),
    },
  };
}

/** 一次请求都没发就断的 reader（read 直接抛 AbortError） */
function abortingResponse() {
  return {
    ok: true,
    status: 200,
    headers: { get: () => 'text/event-stream' },
    text: async () => '',
    body: {
      getReader: () => ({
        read: async () => {
          const e = new Error('The operation was aborted.');
          e.name = 'AbortError';
          throw e;
        },
      }),
    },
  };
}

const frame = (obj, id) => `${id ? `id: ${id}\n` : ''}data: ${JSON.stringify(obj)}\n\n`;

let fetchMock;

beforeEach(() => {
  fetchMock = vi.fn();
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

/* ========================================================================== */
/* parseSseFrame —— 纯函数，先把协议细节钉死                                    */
/* ========================================================================== */

describe('parseSseFrame（单帧解析）', () => {
  it('空块返回 null（心跳注释块不能变成一条空事件）', () => {
    expect(parseSseFrame('')).toBeNull();
    expect(parseSseFrame(null)).toBeNull();
    expect(parseSseFrame(undefined)).toBeNull();
  });

  it('仅注释（服务端 15s 的 :ping 心跳）不构成事件', () => {
    // 关键：必须返回 null。若把心跳当成一条空 data 帧，客户端会反复触发"空 delta 渲染"
    expect(parseSseFrame(': ping')).toBeNull();
    expect(parseSseFrame(': keep-alive 15s')).toBeNull();
  });

  it('id / event / data 三个字段都能解出', () => {
    const f = parseSseFrame('id: 42\nevent: DELTA\ndata: {"a":1}');
    expect(f.id).toBe('42');
    expect(f.event).toBe('DELTA');
    expect(f.data).toBe('{"a":1}');
  });

  it('只有 id 没有 data 也算有效帧（用于推进断点续传游标）', () => {
    const f = parseSseFrame('id: 9');
    expect(f).not.toBeNull();
    expect(f.id).toBe('9');
    expect(f.data).toBe('');
  });

  it('冒号后仅去掉第一个空格（多余空格属于数据本身）', () => {
    expect(parseSseFrame('data: x').data).toBe('x');
    expect(parseSseFrame('data:x').data).toBe('x');
    expect(parseSseFrame('data:   x').data).toBe('  x');
  });

  it('多行 data 用 \\n 拼接（规范行为，不能覆盖成最后一行）', () => {
    expect(parseSseFrame('data: a\ndata: b\ndata: c').data).toBe('a\nb\nc');
  });

  it('兼容 CRLF 与 CR 换行', () => {
    expect(parseSseFrame('id: 3\r\ndata: hi').id).toBe('3');
    expect(parseSseFrame('id: 3\r\ndata: hi').data).toBe('hi');
    expect(parseSseFrame('data: a\rdata: b').data).toBe('a\nb');
  });

  it('无冒号的行按"字段名 + 空值"处理，未知字段被忽略且不影响有效性判定', () => {
    expect(parseSseFrame('retry')).toBeNull(); // 无有效字段
    expect(parseSseFrame('retry\ndata: ok').data).toBe('ok');
  });

  it('注释行不占用字段，夹杂在 data 之间也不干扰', () => {
    expect(parseSseFrame(': note\ndata: v').data).toBe('v');
  });
});

/* ========================================================================== */
/* streamReview —— 端到端（伪造 fetch）                                         */
/* ========================================================================== */

describe('streamReview（请求构造）', () => {
  it('POST 到 /ai/review/stream，带 Authorization 与 Accept，body 为查询体', async () => {
    fetchMock.mockResolvedValue(sseResponse([frame({ type: 'END' }, 1)]));

    const s = streamReview({
      baseUrl: 'http://gw:9080/',
      token: 'TK',
      submissionId: 77,
      reviewType: 2,
      question: '为什么超时？',
    });
    await s.done;

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0];
    // baseUrl 尾斜杠必须被归一，否则会请求到 //ai/review/stream
    expect(url).toBe('http://gw:9080/ai/review/stream');
    expect(init.method).toBe('POST');
    expect(init.headers.Authorization).toBe('Bearer TK');
    expect(init.headers.Accept).toBe('text/event-stream');
    expect(JSON.parse(init.body)).toEqual({
      submissionId: 77,
      reviewType: 2,
      question: '为什么超时？',
    });
  });
});

describe('streamReview（事件契约 START→RETRIEVAL→DELTA×N→END）', () => {
  it('按序回调，累积正文与 lastEventId 正确', async () => {
    fetchMock.mockResolvedValue(
      sseResponse([
        frame({ type: 'START', reviewId: 9001 }, 1),
        frame({ type: 'RETRIEVAL', chunks: 3 }, 2),
        frame({ type: 'DELTA', content: '你' }, 3),
        frame({ type: 'DELTA', content: '好' }, 4),
        frame({ type: 'END', status: 1 }, 5),
      ]),
    );

    const seen = { start: [], retrieval: [], delta: [], end: [], errors: [] };
    const s = streamReview({
      baseUrl: 'http://gw',
      token: 'T',
      submissionId: 1,
      onStart: (e) => seen.start.push(e),
      onRetrieval: (e) => seen.retrieval.push(e),
      onDelta: (e, acc) => seen.delta.push([e.content, acc]),
      onEnd: (e, acc) => seen.end.push([e.status, acc]),
      onError: (e) => seen.errors.push(e),
    });
    await s.done;

    expect(seen.start).toEqual([{ type: 'START', reviewId: 9001 }]);
    expect(seen.retrieval).toEqual([{ type: 'RETRIEVAL', chunks: 3 }]);
    expect(seen.delta).toEqual([
      ['你', '你'],
      ['好', '你好'],
    ]);
    expect(seen.end).toEqual([[1, '你好']]);
    expect(seen.errors).toEqual([]);
    expect(s.received()).toBe('你好');
    expect(s.lastEventId()).toBe(5);
  });

  it('心跳注释帧既不产生 delta，也不推进 lastEventId', async () => {
    fetchMock.mockResolvedValue(
      sseResponse([
        frame({ type: 'START' }, 1),
        ': ping\n\n',
        frame({ type: 'DELTA', content: 'x' }, 2),
        ': ping\n\n',
        frame({ type: 'END' }, 3),
      ]),
    );

    const deltas = [];
    const raws = [];
    const s = streamReview({
      baseUrl: 'http://gw',
      token: 'T',
      submissionId: 1,
      onDelta: (e, acc) => deltas.push(acc),
      onRawEvent: (f) => raws.push(f),
    });
    await s.done;

    expect(deltas).toEqual(['x']);
    // 原始帧回调里不应出现纯心跳块
    expect(raws.every((f) => f.data !== '')).toBe(true);
    expect(s.received()).toBe('x');
  });

  it('重复 START 会清空累积正文（重连时不产生"内容翻倍"）', async () => {
    fetchMock.mockResolvedValue(
      sseResponse([
        frame({ type: 'START' }, 1),
        frame({ type: 'DELTA', content: 'A' }, 2),
        frame({ type: 'START' }, 3),
        frame({ type: 'DELTA', content: 'B' }, 4),
        frame({ type: 'END' }, 5),
      ]),
    );

    let endAcc = null;
    const s = streamReview({
      baseUrl: 'http://gw',
      token: 'T',
      submissionId: 1,
      onEnd: (e, acc) => {
        endAcc = acc;
      },
    });
    await s.done;

    expect(endAcc).toBe('B');
    expect(s.received()).toBe('B');
  });

  it('data 不是合法 JSON 时静默跳过，但仍透出原始帧（不炸掉整条流）', async () => {
    fetchMock.mockResolvedValue(
      sseResponse(['data: 这不是JSON\n\n', frame({ type: 'END' }, 2)]),
    );

    const raws = [];
    const seen = [];
    let endCalled = false;
    const s = streamReview({
      baseUrl: 'http://gw',
      token: 'T',
      submissionId: 1,
      onRawEvent: (f) => raws.push(f),
      onDelta: (e) => seen.push(e),
      onEnd: () => {
        endCalled = true;
      },
    });
    await s.done;

    expect(raws.map((f) => f.data)).toEqual(['这不是JSON', '{"type":"END"}']);
    expect(seen).toEqual([]);
    expect(endCalled).toBe(true);
    expect(s.received()).toBe('');
  });

  it('ERROR 事件走回调而不是抛异常（"错误走事件不走状态码"的契约）', async () => {
    fetchMock.mockResolvedValue(
      sseResponse([frame({ type: 'ERROR', code: 503, msg: '上游不可用' }, 1)]),
    );

    const errors = [];
    const s = streamReview({
      baseUrl: 'http://gw',
      token: 'T',
      submissionId: 1,
      // 关掉自动重连，单独验证 ERROR 事件的分发语义
      reconnect: false,
      onError: (e) => errors.push(e),
    });
    // 注意：ERROR 是**业务事件**而非连接失败，客户端不因此抛异常，done 正常 resolve
    await s.done;

    expect(errors).toEqual([{ type: 'ERROR', code: 503, msg: '上游不可用' }]);
  });
});

describe('streamReview（分帧边界）', () => {
  it('一个 chunk 里塞多个事件块能全部解出', async () => {
    fetchMock.mockResolvedValue(
      sseResponse([
        frame({ type: 'DELTA', content: 'a' }, 1) +
          frame({ type: 'DELTA', content: 'b' }, 2) +
          frame({ type: 'END' }, 3),
      ]),
    );

    const deltas = [];
    const s = streamReview({
      baseUrl: 'http://gw',
      token: 'T',
      submissionId: 1,
      onDelta: (e, acc) => deltas.push(acc),
    });
    await s.done;

    expect(deltas).toEqual(['a', 'ab']);
  });

  it('一个事件块被拆到两个 chunk 也能拼回（缓冲跨 chunk）', async () => {
    const whole = frame({ type: 'DELTA', content: 'ab' }, 1);
    const cut = 'id: 1\ndata: {"type":"DE';
    fetchMock.mockResolvedValue(
      sseResponse([cut, whole.slice(cut.length) + frame({ type: 'END' }, 2)]),
    );

    const deltas = [];
    const s = streamReview({
      baseUrl: 'http://gw',
      token: 'T',
      submissionId: 1,
      onDelta: (e, acc) => deltas.push(acc),
    });
    await s.done;

    expect(deltas).toEqual(['ab']);
  });

  it('多字节汉字被切在字节中间时不解出乱码（TextDecoder stream:true 的作用）', async () => {
    const text = frame({ type: 'DELTA', content: '你好' }, 1) + frame({ type: 'END' }, 2);
    const bytes = new TextEncoder().encode(text);
    // 找到 '你' 的起始字节，在它中间（第 2 个字节）切开
    const idx = text.indexOf('你');
    const byteOffset = new TextEncoder().encode(text.slice(0, idx)).length;
    const splitAt = byteOffset + 2; // 落在 '你' 的 3 字节之内

    fetchMock.mockResolvedValue(
      sseResponseBytes([bytes.slice(0, splitAt), bytes.slice(splitAt)]),
    );

    let acc = null;
    const s = streamReview({
      baseUrl: 'http://gw',
      token: 'T',
      submissionId: 1,
      onEnd: (e, a) => {
        acc = a;
      },
    });
    await s.done;

    expect(acc).toBe('你好');
    expect(s.received()).toBe('你好');
  });
});

describe('streamReview（HTTP 层失败与断线重连）', () => {
  it('Content-Type 不是 SSE（订阅前失败）→ 回调 onHttpError 并直接结束，不重试', async () => {
    fetchMock.mockResolvedValue(
      sseResponse([JSON.stringify({ code: 401, msg: '未登录' })], {
        contentType: 'application/json',
        ok: true,
        status: 200, // 注意：HTTP 200 但内容是 JSON —— 判据必须是 Content-Type
      }),
    );

    const httpErrors = [];
    let opened = false;
    const s = streamReview({
      baseUrl: 'http://gw',
      token: 'T',
      submissionId: 1,
      onHttpError: (e) => httpErrors.push(e),
      onOpen: () => {
        opened = true;
      },
    });
    await s.done;

    expect(httpErrors).toEqual([{ code: 401, status: 200, message: '未登录' }]);
    expect(opened).toBe(false);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('响应体不是 JSON 时直接用原文（网关 502 之类）', async () => {
    fetchMock.mockResolvedValue(
      sseResponse(['<html>502 Bad Gateway</html>'], { contentType: 'text/html', status: 502, ok: false }),
    );

    const httpErrors = [];
    const s = streamReview({
      baseUrl: 'http://gw',
      token: 'T',
      submissionId: 1,
      onHttpError: (e) => httpErrors.push(e),
    });
    await s.done;

    expect(httpErrors[0].status).toBe(502);
    expect(httpErrors[0].message).toBe('<html>502 Bad Gateway</html>');
  });

  it('中途断流且有 lastEventId → 重连一次并携带 Last-Event-ID（只回放未送达事件）', async () => {
    fetchMock
      // 第一次：收到 id=7 的 DELTA 后连接被断开（没有 END）
      .mockResolvedValueOnce(sseResponse([frame({ type: 'START' }, 6), frame({ type: 'DELTA', content: 'A' }, 7)]))
      // 第二次：服务端回放剩余事件
      .mockResolvedValueOnce(
        sseResponse([frame({ type: 'START' }, 8), frame({ type: 'DELTA', content: 'B' }, 9), frame({ type: 'END' }, 10)]),
      );

    let endAcc = null;
    const s = streamReview({
      baseUrl: 'http://gw',
      token: 'T',
      submissionId: 1,
      onEnd: (e, acc) => {
        endAcc = acc;
      },
    });
    await s.done;

    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(fetchMock.mock.calls[0][1].headers['Last-Event-ID']).toBeUndefined();
    expect(fetchMock.mock.calls[1][1].headers['Last-Event-ID']).toBe('7');
    // 第二次的 START 会清空累积 → 最终正文只含回放内容，不会变成 'AB'
    expect(endAcc).toBe('B');
  });

  it('从未收到过 id 时不重连（避免无限重放同一个请求）', async () => {
    fetchMock.mockResolvedValue(
      // 无 id 的帧，且断流无 END
      sseResponse(['data: {"type":"DELTA","content":"x"}\n\n']),
    );

    const s = streamReview({ baseUrl: 'http://gw', token: 'T', submissionId: 1 });
    await s.done;

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(s.lastEventId()).toBeNull();
  });

  it('maxRetries 用尽即停止重试', async () => {
    fetchMock.mockResolvedValue(sseResponse([frame({ type: 'DELTA', content: 'x' }, 1)]));

    const s = streamReview({
      baseUrl: 'http://gw',
      token: 'T',
      submissionId: 1,
      maxRetries: 1,
    });
    await s.done;

    expect(fetchMock).toHaveBeenCalledTimes(2); // 首次 + 1 次重试
  });

  it('reconnect:false 时断流直接结束', async () => {
    fetchMock.mockResolvedValue(sseResponse([frame({ type: 'DELTA', content: 'x' }, 1)]));

    const s = streamReview({
      baseUrl: 'http://gw',
      token: 'T',
      submissionId: 1,
      reconnect: false,
    });
    await s.done;

    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('用户主动 abort 时 done 静默 resolve，不当作错误上报', async () => {
    fetchMock.mockResolvedValue(abortingResponse());

    const httpErrors = [];
    const s = streamReview({
      baseUrl: 'http://gw',
      token: 'T',
      submissionId: 1,
      onHttpError: (e) => httpErrors.push(e),
    });
    s.abort();
    await s.done;

    expect(httpErrors).toEqual([]);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('fetch 自身抛网络异常时归一为 code:0 的 onHttpError', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'));

    const httpErrors = [];
    const s = streamReview({
      baseUrl: 'http://gw',
      token: 'T',
      submissionId: 1,
      onHttpError: (e) => httpErrors.push(e),
    });
    await s.done;

    expect(httpErrors).toHaveLength(1);
    expect(httpErrors[0].code).toBe(0);
    expect(httpErrors[0].message).toContain('Failed to fetch');
  });
});

describe('ReviewEventType', () => {
  it('事件名与后端 judge-ai 契约一致且被冻结', () => {
    expect(ReviewEventType).toEqual({
      START: 'START',
      RETRIEVAL: 'RETRIEVAL',
      DELTA: 'DELTA',
      ERROR: 'ERROR',
      END: 'END',
    });
    expect(Object.isFrozen(ReviewEventType)).toBe(true);
    expect(() => {
      ReviewEventType.NEW = 'X';
    }).toThrow();
  });
});
