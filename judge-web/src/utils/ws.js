import { wsUrl } from '@/api/base';

/**
 * WebSocket 封装（判题进度 / 竞赛榜单）
 *
 * 三个必须处理的现实问题：
 *  1. **鉴权只能走查询参数**：浏览器的 WebSocket API 无法设置请求头，
 *     因此网关在 `/ws/**` 路径上放宽为 `?token=<accessToken>`（见 AuthGlobalFilter）。
 *  2. **必须自己实现重连**：WS 没有内建重试。服务重启、网关超时（900s）都会断开。
 *  3. **必须先卸载旧连接再建新的**：切换题目/竞赛时若不 close，
 *     旧连接的回调仍会往新页面的状态里写数据（典型"数据串台"）。
 *
 * @param {object} opts
 * @param {string} opts.path      以 /ws 开头的路径，如 /ws/submissions/123
 * @param {Function} opts.onMessage 收到的业务 JSON（已 parse）
 * @param {Function} [opts.onOpen]
 * @param {Function} [opts.onClose]
 * @param {Function} [opts.onError]
 * @param {number} [opts.maxRetries=5] 重连次数上限
 * @returns {{close: Function, isOpen: Function}}
 */
export function createWs({ path, token, onMessage, onOpen, onClose, onError, maxRetries = 5 }) {
  let socket = null;
  let retries = 0;
  let closedByUser = false;
  let reconnectTimer = null;

  function connect() {
    if (closedByUser) return;
    socket = new WebSocket(wsUrl(path, token));

    socket.onopen = (ev) => {
      retries = 0; // 连上即重置退避，避免"偶发断一次之后永久变慢"
      if (onOpen) onOpen(ev);
    };

    socket.onmessage = (ev) => {
      if (!ev.data) return;
      let payload = ev.data;
      try {
        payload = JSON.parse(ev.data);
      } catch {
        // 服务端若发纯文本（如 pong），原样交给回调
      }
      if (onMessage) onMessage(payload);
    };

    socket.onerror = (ev) => {
      if (onError) onError(ev);
    };

    socket.onclose = (ev) => {
      if (onClose) onClose(ev);
      if (closedByUser) return;
      if (retries >= maxRetries) {
        if (onError) onError({ type: 'giveup', retries });
        return;
      }
      retries++;
      // 指数退避（1s, 2s, 4s, 8s, 16s，上限 16s）。
      // 不加退避会在服务端宕机时变成"每秒重连一次的自我 DDoS"。
      const delay = Math.min(1000 * 2 ** (retries - 1), 16000);
      reconnectTimer = setTimeout(connect, delay);
    };
  }

  connect();

  return {
    close() {
      closedByUser = true;
      if (reconnectTimer) {
        clearTimeout(reconnectTimer);
        reconnectTimer = null;
      }
      if (socket) {
        socket.onclose = null; // 主动关闭不触发重连
        socket.close();
      }
    },
    isOpen: () => socket?.readyState === WebSocket.OPEN,
  };
}

export default createWs;
