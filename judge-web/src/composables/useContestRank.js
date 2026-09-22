import { computed, onBeforeUnmount, reactive, ref } from 'vue';
import { createWs } from '@/utils/ws';

/**
 * 竞赛榜单订阅（WebSocket）
 *
 * 协议事实（来自 judge-contest 的 ContestRankWsHandler / ContestRankPusher）：
 *   端点   ws://<网关>/ws/contests/{contestId}/rank[?full=true]&token=<accessToken>
 *   信封   { type, topic, seq, full, ts, data }
 *   type   CONNECTED → SNAPSHOT(ContestRankVO) → RANK_UPDATE(ContestRankVO)* / CONTEST_STATUS
 *
 * 封榜语义（两个字段务必分清，别混用）：
 *   · data.frozen   —— **本视图是否为冻结榜**。封榜期间公众视图恒为 true；
 *   · data.fullView —— **本响应是否绕过了封榜**（仅教师/管理员可得）；
 *   · data.inFreezeWindow —— 与视图无关的客观事实："现在是否处于封榜时段"。
 *   前端用 frozen 决定是否显示"封榜中"水印，用 fullView 决定是否提示"你正在看实时榜"。
 *
 * full=true 只对教师/管理员开放：非特权角色请求会被服务端以 ERROR 关闭连接
 * （fail-closed，而不是静默降级成公开榜）。
 */
export function useContestRank() {
  const rank = ref(null);
  const status = reactive({
    connected: false,
    phase: '',
    message: '',
    error: '',
    seq: 0,
  });

  let conn = null;
  /** 是否用户显式要求全量视图（教师/管理员） */
  let wantFull = false;

  const isFrozenView = computed(() => !!rank.value?.frozen);
  const inFreezeWindow = computed(() => !!rank.value?.inFreezeWindow);
  const isFullView = computed(() => !!rank.value?.fullView);

  function close() {
    conn?.close();
    conn = null;
    status.connected = false;
  }

  function handle(envelope) {
    if (!envelope || typeof envelope !== 'object') return;
    if (typeof envelope.seq === 'number') {
      // 同主题 seq 单调递增；RANK_UPDATE 会丢弃中间态，允许跳号
      if (envelope.seq < status.seq && envelope.type === 'RANK_UPDATE') return;
      status.seq = envelope.seq;
    }

    switch (envelope.type) {
      case 'CONNECTED':
        status.connected = true;
        break;

      case 'SNAPSHOT':
      case 'RANK_UPDATE':
        // 两者 data 都是完整 ContestRankVO —— 直接整体替换，不要做增量合并：
        // 榜单是"全量快照"语义，合并会留下已掉出 top-N 的幽灵行。
        if (envelope.data && typeof envelope.data === 'object' && 'entries' in envelope.data) {
          rank.value = { ...envelope.data, fullView: envelope.full ?? envelope.data.fullView };
        }
        break;

      case 'CONTEST_STATUS':
        status.phase = envelope.data?.phase || '';
        status.message = envelope.data?.message || '';
        break;

      case 'ERROR':
        status.error = envelope.data?.message || '榜单订阅被拒绝';
        close();
        break;

      default:
        break;
    }
  }

  /**
   * @param {number|string} contestId
   * @param {string} token
   * @param {{full?: boolean}} [opts] full=true 仅教师/管理员可传
   */
  function subscribe(contestId, token, opts = {}) {
    close();
    rank.value = null;
    status.connected = false;
    status.error = '';
    status.phase = '';
    wantFull = !!opts.full;
    if (!contestId) return;

    const path = `/ws/contests/${contestId}/rank${wantFull ? '?full=true' : ''}`;
    conn = createWs({
      path,
      token,
      onMessage: handle,
    });
  }

  onBeforeUnmount(close);

  return { rank, status, subscribe, close, isFrozenView, inFreezeWindow, isFullView };
}

export default useContestRank;
