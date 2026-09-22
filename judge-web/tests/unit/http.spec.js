import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { http, normalizeError, configureAuth, TOKEN_STORAGE_KEY } from '@/api/http';

/**
 * 统一 HTTP 客户端测试（异常归一 + 双层拦截器）。
 *
 * 这里锁的是**后端 R<T> 合约的前端半边**：`code === 200` 才是成功，且成功时要
 * 直接把 data 摊平给调用方。一旦这条被改坏，表现是全站页面拿到 `{code,msg,data}`
 * 却当数据用 —— 页面能打开、字段全是 undefined，属于最难定位的一类问题。
 */

/** 装一个假适配器，绕过真实网络但保留完整拦截器链 */
function useAdapter(impl) {
  http.defaults.adapter = impl;
}

/** 回显请求头，用于验证请求拦截器 */
const echoHeadersAdapter = (config) =>
  Promise.resolve({ data: { code: 200, data: config.headers }, status: 200, statusText: 'OK', headers: {}, config });

/** 构造一个"HTTP 层失败"的错误，走 response 失败拦截器 */
function httpError(status, body, url = '/x') {
  const e = new Error(`Request failed with status code ${status}`);
  e.config = { url, headers: {} };
  e.response = { status, data: body, headers: {}, config: e.config };
  return e;
}

const readAuth = (headers) => headers?.Authorization ?? headers?.get?.('Authorization');

beforeEach(() => {
  configureAuth({ getToken: () => null, onExpired: () => {} });
});

afterEach(() => {
  http.defaults.adapter = undefined;
});

/* ========================================================================== */
/* normalizeError                                                              */
/* ========================================================================== */

describe('normalizeError（把任意异常归一为 {code, msg}）', () => {
  it('响应体是 R 形态时，业务码优先于 HTTP 状态码', () => {
    const e = normalizeError({ response: { status: 200, data: { code: 5001, msg: '题目不存在' } } });
    expect(e.message).toBe('题目不存在');
    expect(e.code).toBe(5001);
    expect(e.httpStatus).toBe(200);
    expect(e.__normalized).toBe(true);
  });

  it('只有 message 字段而没有 code 时，退回 HTTP 状态码', () => {
    const e = normalizeError({ response: { status: 400, data: { message: '参数错误' } } });
    expect(e.message).toBe('参数错误');
    expect(e.code).toBe(400);
  });

  it('响应体是纯文本时直接用原文（网关 502 之类）', () => {
    const e = normalizeError({ response: { status: 502, data: 'Bad Gateway' } });
    expect(e.message).toBe('Bad Gateway');
    expect(e.code).toBe(502);
  });

  it('空响应体按状态码给出可读兜底文案', () => {
    expect(normalizeError({ response: { status: 401 } }).message).toBe('未登录或登录已过期');
    expect(normalizeError({ response: { status: 403 } }).message).toBe('没有权限执行该操作');
    expect(normalizeError({ response: { status: 429 } }).message).toBe('操作过于频繁，请稍后再试');
    expect(normalizeError({ response: { status: 500 } }).message).toBe('服务端异常，请稍后重试');
    expect(normalizeError({ response: { status: 503 } }).message).toBe('服务端异常，请稍后重试');
  });

  it('请求超时单独识别（ECONNABORTED）', () => {
    const e = normalizeError({ code: 'ECONNABORTED', message: 'timeout of 30000ms exceeded' });
    expect(e.message).toBe('请求超时，请检查后端服务是否已启动');
  });

  it('完全连不上网关时提示默认地址（而不是抛一句 Failed to fetch）', () => {
    const e = normalizeError(new TypeError('Network Error'));
    expect(e.code).toBe(0);
    expect(e.message).toContain('无法连接网关');
  });

  it('已归一的对象重复归一返回同一实例（拦截器可能被叠两次）', () => {
    const once = normalizeError({ response: { status: 403 } });
    expect(normalizeError(once)).toBe(once);
  });

  it('R 形态但 code 为 200 而 HTTP 非 2xx 时仍以业务码为准', () => {
    // 网关 4xx/5xx 也可能裹着 R；此处不应把 500 当成业务成功
    const e = normalizeError({ response: { status: 500, data: { code: 200, msg: '其实成功了' } } });
    expect(e.code).toBe(200);
    expect(e.message).toBe('其实成功了');
  });
});

/* ========================================================================== */
/* 请求拦截器                                                                   */
/* ========================================================================== */

describe('请求拦截器（注入 Authorization）', () => {
  it('从注入的 tokenProvider 取 token 并加 Bearer 前缀', async () => {
    configureAuth({ getToken: () => 'TOK123' });
    useAdapter(echoHeadersAdapter);

    const headers = await http.get('/problems/page');
    expect(readAuth(headers)).toBe('Bearer TOK123');
  });

  it('无 token 时不写 Authorization 头（匿名请求交给后端 401）', async () => {
    useAdapter(echoHeadersAdapter);

    const headers = await http.get('/problems/page');
    expect(readAuth(headers)).toBeFalsy();
  });

  it('调用方显式传入的 Authorization 不被覆盖', async () => {
    configureAuth({ getToken: () => 'TOK123' });
    useAdapter(echoHeadersAdapter);

    const headers = await http.get('/x', { headers: { Authorization: 'Bearer MANUAL' } });
    expect(readAuth(headers)).toBe('Bearer MANUAL');
  });

  it('token 为空字符串时不注入（localStorage 里可能留空串）', async () => {
    configureAuth({ getToken: () => '' });
    useAdapter(echoHeadersAdapter);

    const headers = await http.get('/x');
    expect(readAuth(headers)).toBeFalsy();
  });
});

/* ========================================================================== */
/* 响应拦截器                                                                   */
/* ========================================================================== */

describe('响应拦截器（成功路径）', () => {
  it('code=200 时直接把 data 摊平返回', async () => {
    useAdapter(() =>
      Promise.resolve({
        data: { code: 200, msg: 'ok', data: { list: [1, 2], total: 2 }, requestId: 'r1' },
        status: 200,
        statusText: 'OK',
        headers: {},
        config: {},
      }),
    );

    await expect(http.get('/problems/page')).resolves.toEqual({ list: [1, 2], total: 2 });
  });

  it('code=200 但 data 为 null 时返回 null（不是 undefined）', async () => {
    useAdapter(() =>
      Promise.resolve({
        data: { code: 200, msg: 'ok', data: null },
        status: 200,
        statusText: 'OK',
        headers: {},
        config: {},
      }),
    );

    await expect(http.get('/x')).resolves.toBeNull();
  });

  it('非 R 包装的响应原样返回（actuator、文件流）', async () => {
    useAdapter(() =>
      Promise.resolve({ data: [1, 2, 3], status: 200, statusText: 'OK', headers: {}, config: {} }),
    );

    await expect(http.get('/actuator/prometheus')).resolves.toEqual([1, 2, 3]);
  });

  it('字符串响应体不当作 R（不因 typeof object 判断失败而丢掉内容）', async () => {
    useAdapter(() =>
      Promise.resolve({ data: 'plain text', status: 200, statusText: 'OK', headers: {}, config: {} }),
    );

    await expect(http.get('/x')).resolves.toBe('plain text');
  });
});

describe('响应拦截器（业务码失败）', () => {
  it('HTTP 200 但 code!=200 时按失败抛出，携带业务码与文案', async () => {
    useAdapter(() =>
      Promise.resolve({
        data: { code: 403, msg: '无权访问该题目', data: null },
        status: 200,
        statusText: 'OK',
        headers: {},
        config: {},
      }),
    );

    await expect(http.get('/problems/1')).rejects.toMatchObject({
      code: 403,
      message: '无权访问该题目',
    });
  });

  it('业务码失败但缺 msg 时给出兜底文案', async () => {
    useAdapter(() =>
      Promise.resolve({ data: { code: 500, data: null }, status: 200, statusText: 'OK', headers: {}, config: {} }),
    );

    await expect(http.get('/x')).rejects.toMatchObject({ code: 500, message: '请求失败' });
  });
});

describe('响应拦截器（HTTP 层失败）', () => {
  it('非 401 的 HTTP 错误直接归一后抛出，不触发刷新', async () => {
    useAdapter((config) => Promise.reject(httpError(403, { code: 403, msg: '网关拒绝' }, config.url)));

    await expect(http.get('/x')).rejects.toMatchObject({ code: 403, message: '网关拒绝' });
  });

  it('网络异常归一为 code=0', async () => {
    useAdapter(() => Promise.reject(new TypeError('Network Error')));

    await expect(http.get('/x')).rejects.toMatchObject({ code: 0 });
  });
});

describe('模块导出', () => {
  it('token 存储键与 store 约定一致', () => {
    expect(TOKEN_STORAGE_KEY).toBe('cj_access_token');
  });

  it('http 实例的关键默认值（30s 超时、允许携带 refresh cookie）', () => {
    expect(http.defaults.timeout).toBe(30000);
    expect(http.defaults.withCredentials).toBe(true);
  });
});
