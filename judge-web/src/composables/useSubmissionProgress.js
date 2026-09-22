import { onBeforeUnmount, reactive } from 'vue';
import { createWs } from '@/utils/ws';
import { isTerminalStatus } from '@/utils/format';

/**
 * 判题进度订阅（WebSocket）
 *
 * 协议事实（来自 judge-submission 的 SubmissionProgressWsHandler）：
 *   端点   ws://<网关>/ws/submissions/{submissionId}?token=<accessToken>
 *   信封   { type, topic, seq, full, ts, data }
 *   type   CONNECTED → SNAPSHOT → SUB_PROGRESS* → SUB_RESULT(终态)
 *
 * 两个"必须做"的事：
 *   1. **连接即推 SNAPSHOT**：判题可能在 1 秒内跑完，从"POST 提交"到"建立 WS"之间的
 *      进度必然错过。服务端会先推一条快照，若已终态则直接告知。因此不能只依赖 SUB_PROGRESS。
 *   2. **终态后立即关闭连接**：终态之后服务端不会再推该提交的任何消息，
 *      留着连接只会白占一个会话位（服务端有订阅数上限）。
 *
 * 安全提醒：进度消息里**不含任何用例输入/期望输出摘要**（后端刻意设计），
 * 所以进度面板只展示「第几个用例 / 结论 / 耗时」，要摘要必须走 REST 详情接口。
 */
export function useSubmissionProgress() {
  const state = reactive({
    connected: false,
    /** CONNECTED / SNAPSHOT / SUB_PROGRESS / SUB_RESULT 等最近一次消息类型 */
    lastType: '',
    status: '',
    verdict: '',
    score: null,
    timeMs: null,
    memoryKb: null,
    /** 当前阶段：JUDGING / COMPILED / CASE_DONE / FINISHED */
    stage: '',
    caseSeq: 0,
    totalCases: 0,
    passedCount: 0,
    /** 0~100 */
    progress: 0,
    message: '',
    terminal: false,
    seq: 0,
    error: '',
  });

  let conn = null;

  function reset() {
    Object.assign(state, {
      connected: false,
      lastType: '',
      status: '',
      verdict: '',
      score: null,
      timeMs: null,
      memoryKb: null,
      stage: '',
      caseSeq: 0,
      totalCases: 0,
      passedCount: 0,
      progress: 0,
      message: '',
      terminal: false,
      seq: 0,
      error: '',
    });
  }

  function close() {
    conn?.close();
    conn = null;
    state.connected = false;
  }

  function handle(envelope) {
    if (!envelope || typeof envelope !== 'object') return;
    state.lastType = envelope.type || '';
    // seq 单调递增：服务端会主动丢弃中间态，允许跳号；只挡"倒退"的过期消息
    if (typeof envelope.seq === 'number') {
      if (envelope.seq < state.seq) return;
      state.seq = envelope.seq;
    }

    const d = envelope.data || {};

    switch (envelope.type) {
      case 'CONNECTED':
        state.connected = true;
        state.message = d.message || '已连接判题进度通道';
        break;

      case 'SNAPSHOT':
        state.status = d.status || '';
        state.verdict = d.verdict || '';
        state.score = d.score ?? null;
        state.timeMs = d.timeMs ?? null;
        state.memoryKb = d.memoryKb ?? null;
        state.terminal = !!d.terminal;
        state.message = d.message || '';
        // 终态快照：进度直接置满，避免进度条停在中间让用户以为还在判
        if (state.terminal) {
          state.progress = 100;
          close();
        }
        break;

      case 'SUB_PROGRESS':
        state.status = state.status || 'JUDGING';
        state.stage = d.stage || '';
        state.caseSeq = d.caseSeq ?? state.caseSeq;
        state.totalCases = d.totalCases ?? state.totalCases;
        state.passedCount = d.passedCount ?? state.passedCount;
        state.verdict = d.verdict || '';
        state.timeMs = d.timeMs ?? state.timeMs;
        state.memoryKb = d.memoryKb ?? state.memoryKb;
        state.progress = d.progress ?? state.progress;
        state.message = d.message || '';
        break;

      case 'SUB_RESULT':
        state.status = d.status || 'SUCCESS';
        state.verdict = d.verdict || '';
        state.score = d.score ?? null;
        state.progress = 100;
        state.terminal = true;
        state.message = '判题完成';
        close(); // 终态后不再有推送，主动断开
        break;

      case 'ERROR':
        state.error = d.message || '订阅被拒绝';
        close();
        break;

      case 'PING':
      case 'PONG':
        break;

      default:
        // 未知类型一律忽略（服务端可灰度新增）
        break;
    }
  }

  /**
   * 订阅某个提交的判题进度。
   * @param {number|string} submissionId
   * @param {string} token access token
   * @param {{onTerminal?: Function}} [opts] 终态回调（用于刷新详情）
   */
  function subscribe(submissionId, token, opts = {}) {
    close();
    reset();
    if (!submissionId) return;

    conn = createWs({
      path: `/ws/submissions/${submissionId}`,
      token,
      onMessage: (env) => {
        handle(env);
        if (env?.type === 'SUB_RESULT' || env?.data?.terminal) {
          opts.onTerminal?.(state);
        }
      },
      onError: (ev) => {
        if (ev?.type === 'giveup') {
          state.error = '进度通道重连失败，请刷新页面查看最新结果';
        }
      },
    });
  }

  onBeforeUnmount(close);

  return { state, subscribe, close, reset, isTerminal: () => isTerminalStatus(state.status) };
}

export default useSubmissionProgress;
