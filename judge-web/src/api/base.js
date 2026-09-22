/**
 * 请求基址推导（REST / SSE / WebSocket 共用）
 *
 * 设计要点见 .env.example 的注释：
 *   1. 默认按**页面主机名**推导，而不是写死 localhost —— 保证 refresh cookie
 *      始终是 same-site（SameSite=Lax 在跨 site 的 XHR 上不带 cookie，
 *      会导致"打开页面正常、用一会儿被踢下线"这类难查问题）。
 *   2. 允许 VITE_API_BASE_URL 完全覆盖（生产外域部署、或改用 /api 同源代理）。
 */

const DEFAULT_GATEWAY_PORT = '9080';

function trimTrailingSlash(url) {
  return String(url).replace(/\/+$/, '');
}

/** REST 基址，如 http://localhost:9080 或 /api */
export const API_BASE = (() => {
  const configured = import.meta.env.VITE_API_BASE_URL;
  if (configured) {
    return trimTrailingSlash(configured);
  }
  // SSR / 单测（无 window）下退化为 localhost
  if (typeof window === 'undefined') {
    return `http://localhost:${DEFAULT_GATEWAY_PORT}`;
  }
  const { protocol, hostname } = window.location;
  const host = hostname || 'localhost';
  return `${protocol}//${host}:${DEFAULT_GATEWAY_PORT}`;
})();

/** WebSocket 基址，如 ws://localhost:9080 */
export const WS_BASE = (() => {
  const configured = import.meta.env.VITE_WS_BASE_URL;
  if (configured) {
    return trimTrailingSlash(configured);
  }
  if (API_BASE.startsWith('http')) {
    return trimTrailingSlash(API_BASE.replace(/^http/, 'ws'));
  }
  // 相对基址（/api 同源代理）时，用当前页面协议推导
  const { protocol, host } = window.location;
  return `${protocol === 'https:' ? 'wss' : 'ws'}://${host}`;
})();

/** 拼接完整 REST 地址 */
export function apiUrl(path) {
  const p = path.startsWith('/') ? path : `/${path}`;
  return `${API_BASE}${p}`;
}

/**
 * 拼接 WebSocket 地址（自动带 token，网关 WS 握手需要）。
 *
 * ⚠️ path 可能自带查询参数（如 `/ws/contests/1/rank?full=true`），
 * 因此必须判断已有 `?` 再用 `&` 续接 —— 写成 `${path}?token=` 会得到
 * `...?full=true?token=xxx`，服务端解析 query 时把整个 "full=true?token=xxx"
 * 当成 full 的值，表现为 `full` 失效（要么拒绝、要么永远订阅到公开榜）。
 */
export function wsUrl(path, token) {
  const p = path.startsWith('/') ? path : `/${path}`;
  if (!token) return `${WS_BASE}${p}`;
  const sep = p.includes('?') ? '&' : '?';
  return `${WS_BASE}${p}${sep}token=${encodeURIComponent(token)}`;
}
