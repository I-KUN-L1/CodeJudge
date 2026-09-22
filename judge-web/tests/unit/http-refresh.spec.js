import { describe, it, expect, beforeEach, vi } from 'vitest';

/**
 * 401 → 刷新 access token → 重放原请求。
 *
 * 为什么单独用一个文件并**手写 axios 替身**：
 *   这段逻辑的正确性完全体现在"调用了几次刷新接口"上。如果让 axios 走真实
 *   HTTP（哪怕用假适配器），并发 401 的时序就依赖真实事件循环，测出来的是
 *   "大概没问题"；这里直接把刷新接口换成一个可计数的 mock，
 *   才能断言"5 个并发 401 只打 1 次 refresh"这条**单飞**保证。
 */

const h = vi.hoisted(() => {
  const state = { reqFns: [], resOk: null, resErr: null };
  const instance = {
    defaults: {},
    interceptors: {
      request: { use: (fn) => state.reqFns.push(fn) },
      response: {
        use: (ok, err) => {
          state.resOk = ok;
          state.resErr = err;
        },
      },
    },
    // 重放原请求会走到这里
    request: null,
  };
  const axiosMock = { create: () => instance, request: null };
  return { state, instance, axiosMock };
});

vi.mock('axios', () => ({ default: h.axiosMock }));

// eslint-disable-next-line import/first
import { http, configureAuth } from '@/api/http';

const refreshOk = (token = 'NEW_TOKEN') => ({ data: { code: 200, msg: 'ok', data: { accessToken: token } } });

/** 构造 response 失败拦截器收到的错误对象 */
const unauthorized = (url = '/problems/page', extra = {}) => ({
  response: { status: 401, data: { code: 401, msg: '未登录' } },
  config: { url, headers: {}, ...extra },
});

beforeEach(() => {
  h.axiosMock.request = vi.fn();
  h.instance.request = vi.fn().mockResolvedValue('REPLAYED');
  configureAuth({ getToken: () => 'OLD_TOKEN', onExpired: () => {} });
});

describe('401 单飞刷新', () => {
  it('并发 4 个 401 只触发 1 次 refresh，且每个请求都被重放', async () => {
    h.axiosMock.request.mockResolvedValue(refreshOk());

    const errs = [
      h.state.resErr(unauthorized('/a')),
      h.state.resErr(unauthorized('/b')),
      h.state.resErr(unauthorized('/c')),
      h.state.resErr(unauthorized('/d')),
    ];
    const results = await Promise.all(errs);

    expect(h.axiosMock.request).toHaveBeenCalledTimes(1);
    expect(results).toEqual(['REPLAYED', 'REPLAYED', 'REPLAYED', 'REPLAYED']);
    expect(h.instance.request).toHaveBeenCalledTimes(4);
  });

  it('重放请求带上新 token，并打上 __retried 标记防止无限刷新', async () => {
    h.axiosMock.request.mockResolvedValue(refreshOk('FRESH'));

    await h.state.resErr(unauthorized('/a'));

    const replayed = h.instance.request.mock.calls[0][0];
    expect(replayed.headers.Authorization).toBe('Bearer FRESH');
    expect(replayed.__retried).toBe(true);
  });

  it('刷新成功后通知 store 更新本地 token', async () => {
    h.axiosMock.request.mockResolvedValue(refreshOk('FRESH'));
    const onExpired = vi.fn();
    configureAuth({ onExpired });

    await h.state.resErr(unauthorized('/a'));

    expect(onExpired).toHaveBeenCalledWith('refresh', 'FRESH');
  });

  it('刷新完成后释放单飞锁，下一次 401 会重新刷新', async () => {
    h.axiosMock.request.mockResolvedValue(refreshOk());

    await h.state.resErr(unauthorized('/a'));
    expect(h.axiosMock.request).toHaveBeenCalledTimes(1);

    // 锁未释放的话这里不会再刷新
    await h.state.resErr(unauthorized('/b'));
    expect(h.axiosMock.request).toHaveBeenCalledTimes(2);
  });
});

describe('不触发刷新的情形', () => {
  it('登录接口自身 401 直接抛出（密码错了就是错了，不能去刷新）', async () => {
    await expect(
      h.state.resErr({
        response: { status: 401, data: { code: 401, msg: '用户名或密码错误' } },
        config: { url: '/accounts/login', headers: {} },
      }),
    ).rejects.toMatchObject({ code: 401, message: '用户名或密码错误' });

    expect(h.axiosMock.request).not.toHaveBeenCalled();
  });

  it('refresh 接口自身的 401 不递归', async () => {
    await expect(
      h.state.resErr({
        response: { status: 401 },
        config: { url: '/accounts/refresh', headers: {} },
      }),
    ).rejects.toMatchObject({ code: 401 });

    expect(h.axiosMock.request).not.toHaveBeenCalled();
  });

  it('已重试过的请求再次 401 不再刷新（避免死循环）', async () => {
    await expect(
      h.state.resErr({
        response: { status: 401, data: { code: 401, msg: '仍然未授权' } },
        config: { url: '/a', headers: {}, __retried: true },
      }),
    ).rejects.toMatchObject({ code: 401, message: '仍然未授权' });

    expect(h.axiosMock.request).not.toHaveBeenCalled();
  });

  it('非 401 一律不刷新', async () => {
    await expect(
      h.state.resErr({
        response: { status: 403, data: { code: 403, msg: '无权限' } },
        config: { url: '/a', headers: {} },
      }),
    ).rejects.toMatchObject({ code: 403 });

    expect(h.axiosMock.request).not.toHaveBeenCalled();
  });
});

describe('刷新失败 = 登录态彻底失效', () => {
  it('refresh 请求抛异常时通知登出并抛统一文案', async () => {
    h.axiosMock.request.mockRejectedValue(new Error('boom'));
    const onExpired = vi.fn();
    configureAuth({ onExpired });

    await expect(h.state.resErr(unauthorized('/a'))).rejects.toMatchObject({
      code: 401,
      message: '登录已过期，请重新登录',
    });
    expect(onExpired).toHaveBeenCalledWith('expired');
    expect(h.instance.request).not.toHaveBeenCalled();
  });

  it('refresh 返回 code!=200 同样视为失效', async () => {
    h.axiosMock.request.mockResolvedValue({ data: { code: 401, msg: 'refresh token 已过期' } });
    const onExpired = vi.fn();
    configureAuth({ onExpired });

    await expect(h.state.resErr(unauthorized('/a'))).rejects.toMatchObject({ code: 401 });
    expect(onExpired).toHaveBeenCalledWith('expired');
  });

  it('refresh 返回 200 但缺 accessToken 也视为失效（防止把 undefined 写进 header）', async () => {
    h.axiosMock.request.mockResolvedValue({ data: { code: 200, msg: 'ok', data: {} } });
    const onExpired = vi.fn();
    configureAuth({ onExpired });

    await expect(h.state.resErr(unauthorized('/a'))).rejects.toMatchObject({ code: 401 });
    expect(onExpired).toHaveBeenCalledWith('expired');
  });
});

describe('拦截器注册', () => {
  it('实测 http 就是 axios.create() 的返回值（后续断言前提）', () => {
    expect(http).toBe(h.instance);
  });

  it('请求/响应拦截器各注册了一次', () => {
    expect(h.state.reqFns).toHaveLength(1);
    expect(typeof h.state.resOk).toBe('function');
    expect(typeof h.state.resErr).toBe('function');
  });
});
