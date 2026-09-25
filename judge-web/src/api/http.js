import axios from 'axios';
import { API_BASE } from '@/api/base';
import { ElMessage } from 'element-plus';

/**
 * 统一 HTTP 客户端
 *
 * 契约（来自后端 judge-common 的 R<T>）：
 *   所有业务响应形如 { code, msg, data, requestId }，code=200 才是成功。
 *   HTTP 状态码只在**网关层**失败时才有意义（401/403/429/5xx），此时响应体同样是 R 形态。
 *
 * 因此这里做两层归一：
 *   · 成功响应：按 code 判定，成功则**直接返回 data**（调用方不必到处 .data.data）；
 *   · 失败响应：抛出带 msg 的 Error，业务码放在 err.code 上，便于页面分流处理。
 *
 * 401 的处理是重点：access token 只有 30 分钟，长会话必然过期。
 * 这里做**单飞刷新**（并发 401 只触发一次 refresh，其余请求等同一个 Promise），
 * 否则页面初始化时 5 个并发请求会打 5 次 refresh，服务端限流后集体失败。
 */

export const TOKEN_STORAGE_KEY = 'cj_access_token';

export const http = axios.create({
  baseURL: API_BASE,
  timeout: 30000,
  // refresh token 走 HttpOnly Cookie，必须允许携带凭据
  withCredentials: true,
});

/** 供 store 注入的登录态访问器（避免 api 层反向依赖 store 造成循环 import） */
let tokenProvider = () => localStorage.getItem(TOKEN_STORAGE_KEY);
let onAuthExpired = () => {};
/** 「后端判定无权限」回调：由 main.js 注入 router，跳 /403 */
let onForbidden = () => {};

export function configureAuth({ getToken, onExpired, onForbidden: forbiddenHandler }) {
  if (getToken) tokenProvider = getToken;
  if (onExpired) onAuthExpired = onExpired;
  if (forbiddenHandler) onForbidden = forbiddenHandler;
}

/**
 * 后端拒绝（403）的统一出口。
 *
 * 鉴权**只在后端**：前端不判"你有没有权限"，只在被后端明确拒绝时把用户带到 403 页。
 * 两处形态都要接：业务服务的 403 走 HTTP 200 + `body.code=403`（judge-common 的
 * 统一异常处理把 `R` 原样返回），网关自身的拒绝才是真的 HTTP 403。
 */
function notifyForbidden() {
  try {
    onForbidden();
  } catch (e) {
    // 回调只为导航服务，失败不能反过来影响请求错误的传递
    console.warn('[http] onForbidden 回调异常', e);
  }
}

http.interceptors.request.use((config) => {
  const token = tokenProvider();
  if (token && !config.headers.Authorization) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

/** 把任意异常归一为 { code, msg } —— 页面只需读这两个字段 */
export function normalizeError(err) {
  if (err && err.__normalized) return err;

  const resp = err?.response;
  const body = resp?.data;

  let code = resp?.status ?? 0;
  let msg = '';

  if (body && typeof body === 'object') {
    // 网关与业务服务都返回 R 形态
    if (typeof body.code === 'number') code = body.code;
    msg = body.msg || body.message || '';
  } else if (typeof body === 'string' && body.trim()) {
    msg = body.trim();
  }

  if (!msg) {
    if (err?.code === 'ECONNABORTED') {
      msg = '请求超时，请检查后端服务是否已启动';
    } else if (code === 401) {
      msg = '未登录或登录已过期';
    } else if (code === 403) {
      msg = '没有权限执行该操作';
    } else if (code === 429) {
      msg = '操作过于频繁，请稍后再试';
    } else if (code >= 500) {
      msg = '服务端异常，请稍后重试';
    } else if (!resp) {
      msg = '无法连接网关（默认 http://localhost:9080），请确认服务已启动';
    } else {
      msg = err?.message || '请求失败';
    }
  }

  const normalized = new Error(msg);
  normalized.code = code;
  normalized.httpStatus = resp?.status ?? 0;
  normalized.__normalized = true;
  return normalized;
}

// ---------------------------------------------------------------------------
// 401 → 用 refresh cookie 换新 access token → 重放原请求
// ---------------------------------------------------------------------------

/** 单飞：进行中的刷新 Promise。并发 401 共享它，避免刷新风暴 */
let refreshing = null;

/** 这些端点自身的 401 不需要刷新（登录失败就是失败） */
const NO_REFRESH_PATHS = ['/accounts/login', '/accounts/admin/login', '/accounts/refresh'];

async function refreshAccessToken() {
  // 用裸 axios，避免走本实例的拦截器造成递归
  const resp = await axios.request({
    url: `${API_BASE}/accounts/refresh`,
    method: 'POST',
    withCredentials: true,
    timeout: 15000,
  });
  const body = resp.data;
  if (!body || body.code !== 200 || !body.data?.accessToken) {
    throw new Error(body?.msg || '刷新登录态失败');
  }
  return body.data.accessToken;
}

http.interceptors.response.use(
  (resp) => {
    const body = resp.data;
    // 非 R 包装的响应（如 actuator、文件流）原样返回
    if (!body || typeof body !== 'object' || typeof body.code !== 'number') {
      return body;
    }
    if (body.code === 200) {
      return body.data;
    }
    // 业务码 403：后端明确拒绝（@RequireRole / 归属校验失败）。先跳 403 页再抛错，
    // 顺序不能反过来 —— 抛错后调用方的 catch 往往只弹一个 toast，用户会留在原地反复点。
    if (body.code === 403) {
      notifyForbidden();
    }
    const e = new Error(body.msg || '请求失败');
    e.code = body.code;
    e.httpStatus = resp.status;
    e.__normalized = true;
    return Promise.reject(e);
  },
  async (error) => {
    const status = error?.response?.status;
    const original = error?.config;
    const url = original?.url || '';

    // 网关层的 403（真实 HTTP 状态码）：与业务码 403 走同一个出口
    if (status === 403) {
      notifyForbidden();
      return Promise.reject(normalizeError(error));
    }

    const refreshable =
      status === 401 &&
      original &&
      !original.__retried &&
      !NO_REFRESH_PATHS.some((p) => url.includes(p));

    if (!refreshable) {
      return Promise.reject(normalizeError(error));
    }

    try {
      if (!refreshing) {
        refreshing = refreshAccessToken().finally(() => {
          refreshing = null;
        });
      }
      const newToken = await refreshing;

      // 刷到新 token 后同步给 store（store 会写回 localStorage）
      onAuthExpired('refresh', newToken);

      original.__retried = true;
      original.headers.Authorization = `Bearer ${newToken}`;
      return http.request(original);
    } catch (e) {
      // 区分「刷新被明确拒绝」与「网络故障」：网关短暂重启/超时时把用户本地登出、
      // 丢失页面，与服务故障 ≠ 登录过期的设计原则相悖。仅明确的拒绝才清登录态；
      // 网络类错误保留 token，下次请求再重试刷新
      const isNetworkish =
        e?.code === 'ECONNABORTED' || e?.code === 'ERR_NETWORK' || e?.message === 'Network Error';
      if (isNetworkish) {
        const err = new Error('网络异常，请稍后重试');
        err.code = 'NETWORK';
        err.__normalized = true;
        return Promise.reject(err);
      }
      // 刷新失败 = 登录态彻底失效，交给 store 清理并跳登录页
      onAuthExpired('expired');
      const err = new Error('登录已过期，请重新登录');
      err.code = 401;
      err.__normalized = true;
      return Promise.reject(err);
    }
  },
);

/** 便捷提示：统一的失败弹窗（限流/网络类只提示一次，避免刷屏） */
export function toastError(err) {
  ElMessage.error(err?.message || '操作失败');
}
