<template>
  <div class="cj-page">
    <!-- ================= 竞赛信息 ================= -->
    <div class="cj-card">
      <div class="cj-card__head">
        <div class="cj-row">
          <el-button link :icon="ArrowLeft" @click="router.back()">返回</el-button>
          <span class="cj-title">{{ detail?.title || `竞赛 #${contestId}` }}</span>
          <el-tag v-if="detail" size="small" :type="contestStatusTagType(detail.status)" effect="light">
            {{ contestStatusLabel(detail.status) }}
          </el-tag>
          <el-tag v-if="detail?.rule" size="small" effect="plain">{{ detail.rule }}</el-tag>
          <el-tag v-if="isFrozenView" size="small" type="danger" effect="dark">封榜中（冻结榜）</el-tag>
          <el-tag v-if="isFullView" size="small" type="warning" effect="dark">实时全量视图</el-tag>
        </div>

        <div class="cj-row">
          <el-button
            v-if="detail && !detail.myRegistered && detail.status !== 2"
            type="primary"
            size="small"
            :loading="registering"
            @click="onRegister"
          >
            报名
          </el-button>
          <el-tag v-else-if="detail?.myRegistered" size="small" type="success" effect="plain">已报名</el-tag>

          <el-button size="small" :icon="Refresh" @click="reloadAll">刷新</el-button>
        </div>
      </div>

      <div class="cj-card__body" v-if="detail">
        <el-descriptions :column="descColumns" border size="small">
          <el-descriptions-item label="开始">{{ fmtTime(detail.startTime) }}</el-descriptions-item>
          <el-descriptions-item label="结束">{{ fmtTime(detail.endTime) }}</el-descriptions-item>
          <el-descriptions-item label="封榜时刻">
            <span :class="detail.freezeAt ? '' : 'cj-dim'">{{ detail.freezeAt ? fmtTime(detail.freezeAt) : '不封榜' }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="罚时规则">每错 {{ detail.penaltyMinutes ?? 20 }} 分钟</el-descriptions-item>
          <el-descriptions-item label="题目数">{{ detail.problemCount ?? 0 }}</el-descriptions-item>
          <el-descriptions-item label="报名人数">{{ detail.registerCount ?? 0 }}</el-descriptions-item>
          <el-descriptions-item label="榜单版本">
            <span class="cj-mono">{{ rank?.version ?? '—' }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="WS 状态">
            <el-tag size="small" :type="wsStatus.connected ? 'success' : 'info'" effect="plain">
              {{ wsStatus.connected ? '已连接' : '未连接' }}
            </el-tag>
          </el-descriptions-item>
        </el-descriptions>

        <div v-if="detail.description" class="desc cj-sub">{{ detail.description }}</div>
      </div>
    </div>

    <!-- ================= 管理操作（contest:manage） ================= -->
    <div v-if="detail" v-perm="'contest:manage'" class="cj-card">
      <div class="cj-card__head">
        <span>赛务操作</span>
        <span class="cj-dim">手动封榜 / 终榜重建 / 快照留档</span>
      </div>
      <div class="cj-card__body">
        <div class="cj-row">
          <!-- 卡片本身已按能力码渲染，此处无需再判一次；原来的 :disabled 是重复门槛 -->
          <el-checkbox v-model="fullView" @change="resubscribe">
            full=true 订阅实时全量榜（绕开封榜）
          </el-checkbox>

          <div class="cj-spacer" />

          <el-button size="small" :loading="freezing" @click="onFreeze">手动封榜</el-button>
          <el-button size="small" :loading="rebuilding" @click="onRebuild">终榜重建</el-button>
          <el-button size="small" @click="loadSnapshots">查看快照</el-button>
        </div>

        <el-table
          v-if="snapshots.length"
          :data="snapshots"
          size="small"
          style="margin-top: 12px"
          :show-header="true"
        >
          <el-table-column label="类型" width="130">
            <template #default="{ row }">
              <el-tag size="small" effect="plain">{{ row.type }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="快照时刻" width="200" align="right">
            <template #default="{ row }">
              <span class="cj-num-dim">{{ fmtTime(row.snapshotAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="JSON 字节数" width="140" align="right">
            <template #default="{ row }">
              <span class="cj-num cj-dim">{{ row.size }}</span>
            </template>
          </el-table-column>
        </el-table>

        <el-alert
          v-if="rebuildReport"
          type="success"
          :closable="false"
          show-icon
          style="margin-top: 12px"
          :title="`重建完成：${JSON.stringify(rebuildReport)}`"
        />
      </div>
    </div>

    <!-- ================= 榜单 ================= -->
    <div class="cj-card">
      <div class="cj-card__head">
        <span>排行榜</span>
        <span class="cj-dim">
          共 {{ rank?.totalParticipants ?? 0 }} 人参赛
          <template v-if="rank?.updatedAt"> · 更新于 {{ fmtTime(rank.updatedAt) }}</template>
        </span>
      </div>

      <div class="cj-card__body">
        <el-alert
          v-if="wsStatus.error"
          type="warning"
          :closable="false"
          show-icon
          style="margin-bottom: 12px"
          :title="wsStatus.error"
        />

        <!-- 封榜提示：区分"客观在封榜时段"与"我这个视图被冻结"，
             避免管理员把 full 视图也误读成冻结榜 -->
        <el-alert
          v-if="inFreezeWindow && !isFullView"
          type="error"
          :closable="false"
          show-icon
          style="margin-bottom: 12px"
          title="封榜期：公开榜单已冻结在封榜时刻，名次不再变化；教师/管理员可勾选 full 查看实时榜"
        />

        <template v-if="labels.length">
          <div class="chart-wrap">
            <EChartPanel :option="chartOption" />
          </div>
        </template>

        <div class="cj-scroll-x">
          <el-table
            v-loading="!rank"
            :data="rank?.entries || []"
            size="small"
            :empty-text="rank ? '暂无榜单数据（可能还没有人提交）' : '加载中…'"
            :row-class-name="rowClass"
          >
            <el-table-column label="#" width="66" align="center" fixed>
              <template #default="{ row }">
                <span class="rank-no" :class="medalClass(row.rank)">{{ row.rank }}</span>
              </template>
            </el-table-column>

            <el-table-column label="参赛者" min-width="140" fixed>
              <template #default="{ row }">
                <span :class="{ 'is-me': row.userId === myUserId }">{{ row.userName || `#${row.userId}` }}</span>
              </template>
            </el-table-column>

            <el-table-column label="解决 / 得分" width="118" align="right">
              <template #default="{ row }">
                <!-- 两行同一条右边缘：主值（权重）与副说明（总分/过题数） -->
                <div class="cj-cell-stack cj-cell-stack--end">
                  <span class="cj-cell-stack__v cj-num">{{ row.weight }}</span>
                  <span class="cj-cell-stack__sub">
                    {{ rank?.rule === 'IOI' ? `总分 ${row.totalScore}` : `过 ${row.solvedCount} 题` }}
                  </span>
                </div>
              </template>
            </el-table-column>

            <el-table-column label="罚时" width="110" align="right">
              <template #default="{ row }">
                <span class="cj-num">{{ fmtPenalty(row.penaltySeconds) }}</span>
              </template>
            </el-table-column>

            <!-- 各题状态：ICPC 记法，表头即题号（A/B/C…） -->
            <el-table-column
              v-for="label in labels"
              :key="label"
              :label="label"
              width="66"
              align="center"
            >
              <template #default="{ row }">
                <span class="cj-mono" :class="icpcCellClass(row.problemStatus?.[label])">
                  {{ row.problemStatus?.[label] || '·' }}
                </span>
              </template>
            </el-table-column>
          </el-table>
        </div>

        <!-- 我的名次：榜行不在 top-N 里时，单独显示一行 -->
        <div v-if="rank?.myEntry && !inTopList" class="my-rank">
          <span class="cj-dim">我的名次</span>
          <span class="rank-no">{{ rank.myRank }}</span>
          <span>{{ rank.myEntry.userName }}</span>
          <span class="v-ac">{{ rank.myEntry.weight }}</span>
          <span class="cj-mono cj-dim">罚时 {{ fmtPenalty(rank.myEntry.penaltySeconds) }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { ArrowLeft, Refresh } from '@element-plus/icons-vue';
import EChartPanel from '@/components/EChartPanel.vue';
import { contestApi } from '@/api';
import { useUserStore } from '@/stores/user';
import { useContestRank } from '@/composables/useContestRank';
import {
  contestStatusLabel,
  contestStatusTagType,
  fmtPenalty,
  fmtTime,
  icpcCellClass,
} from '@/utils/format';

const route = useRoute();
const router = useRouter();
const user = useUserStore();
const { rank, status: wsStatus, subscribe, isFrozenView, inFreezeWindow, isFullView } = useContestRank();

/**
 * 路由里的竞赛 id —— **必须保持字符串，绝不能 `Number()`**。
 *
 * 竞赛 id 是 19 位雪花号（`2102698982248611841`），远超 JS 的安全整数上限
 * `Number.MAX_SAFE_INTEGER = 9007199254740991`（约 9.0e15）。`Number()` 会把它
 * 静默截断到最近的 2 的幂的倍数上：
 *
 *     Number('2102698982248611841') === 2102698982248611800   // 差 41
 *
 * 后端为此专门在 judge-common 里配了 `SafeLongSerializer`，把 Long 序列化成
 * **字符串**下发 —— 精度原本是保住的，是这里主动把它毁掉了。截断后两个症状同源：
 *
 *   ① `GET /contests/2102698982248611800` → ContestService 查不到 → 404
 *      `竞赛不存在：2102698982248611800`（界面弹出该提示）；
 *   ② WebSocket 订阅 `/ws/contests/2102698982248611800/rank` 被
 *      ContestRankWsHandler 以 `fail()` 关闭 → `rank` 永远是 null →
 *      榜单表格的 `v-loading="!rank"` 就一直转，也就是"一直停留在加载界面"。
 *
 * `useContestRank.subscribe()` 的签名本来就是 `{number|string}`，直接传字符串即可。
 */
const contestId = computed(() => route.params.id);
const detail = ref(null);
const snapshots = ref([]);
const rebuildReport = ref(null);
const registering = ref(false);
const freezing = ref(false);
const rebuilding = ref(false);
const fullView = ref(false);

const myUserId = computed(() => user.profile?.id);
const descColumns = computed(() => (window.innerWidth < 720 ? 1 : 4));

const labels = computed(() => rank.value?.labels || []);
const inTopList = computed(() =>
  (rank.value?.entries || []).some((e) => e.userId === myUserId.value),
);

const chartOption = computed(() => {
  const entries = rank.value?.entries || [];
  if (!labels.value.length || !entries.length) return {};
  // 各题通过人次：从每行的 problemStatus 里数"以 + 开头"的格子
  const acByLabel = labels.value.map((label) => ({
    label,
    count: entries.filter((e) => (e.problemStatus?.[label] || '').startsWith('+')).length,
  }));
  return {
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: 34, right: 16, top: 26, bottom: 26, containLabel: true },
    xAxis: { type: 'category', data: acByLabel.map((d) => d.label) },
    yAxis: { type: 'value', name: '通过人次', minInterval: 1 },
    series: [
      {
        type: 'bar',
        data: acByLabel.map((d) => d.count),
        barMaxWidth: 34,
        // 不写 itemStyle.color：系列色由 EChartPanel 从 CSS 变量 --chart-1 注入
        label: { show: true, position: 'top' },
      },
    ],
  };
});

function medalClass(rankNo) {
  if (rankNo === 1) return 'rank-no--gold';
  if (rankNo === 2) return 'rank-no--silver';
  if (rankNo === 3) return 'rank-no--bronze';
  return '';
}

function rowClass({ row }) {
  return row.userId === myUserId.value ? 'is-my-row' : '';
}

async function loadDetail() {
  try {
    detail.value = await contestApi.detail(contestId.value);
  } catch (e) {
    ElMessage.error(e.message || '加载竞赛详情失败');
  }
}

function resubscribe() {
  subscribe(contestId.value, user.accessToken, { full: fullView.value });
}

async function reloadAll() {
  await loadDetail();
  resubscribe();
}

async function onRegister() {
  registering.value = true;
  try {
    await contestApi.register(contestId.value);
    ElMessage.success('报名成功');
    await loadDetail();
  } catch (e) {
    ElMessage.error(e.message || '报名失败');
  } finally {
    registering.value = false;
  }
}

async function onFreeze() {
  freezing.value = true;
  try {
    const done = await contestApi.freeze(contestId.value);
    ElMessage.success(done ? '已封榜' : '已处于封榜状态（幂等）');
    await loadDetail();
  } catch (e) {
    ElMessage.error(e.message || '封榜失败');
  } finally {
    freezing.value = false;
  }
}

async function onRebuild() {
  rebuilding.value = true;
  try {
    rebuildReport.value = await contestApi.rebuild(contestId.value, false);
    ElMessage.success('终榜重建完成');
    await loadDetail();
  } catch (e) {
    ElMessage.error(e.message || '重建失败');
  } finally {
    rebuilding.value = false;
  }
}

async function loadSnapshots() {
  try {
    snapshots.value = (await contestApi.snapshots(contestId.value)) || [];
    if (!snapshots.value.length) ElMessage.info('暂无快照（尚未封榜或未结束）');
  } catch (e) {
    ElMessage.error(e.message || '加载快照失败');
  }
}

onMounted(() => {
  loadDetail();
  // 默认订阅公开榜；教师/管理员可勾选 full 切到实时全量视图
  subscribe(contestId.value, user.accessToken, { full: false });
});

// 同路由不同竞赛的跳转会复用组件实例：不重载详情、不重订阅 WS 就一直显示上一场比赛
watch(() => route.params.id, (nv, ov) => {
  if (nv && nv !== ov) {
    loadDetail();
    subscribe(route.params.id, user.accessToken, { full: false });
  }
});

onBeforeUnmount(() => undefined); // 断连由 composable 的 onBeforeUnmount 统一处理
</script>

<style scoped>
.desc {
  margin-top: var(--sp-3);
  line-height: var(--lh-read);
}

.chart-wrap {
  margin-bottom: var(--sp-4);
}

/* 名次徽标：定宽等高，1 位与 2 位名次的圆点大小一致，
   否则第 9 名与第 10 名会差出半个字符宽，整列看起来是歪的 */
.rank-no {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 26px;
  height: 24px;
  padding: 0 var(--sp-2);
  border-radius: var(--r-full);
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  font-weight: var(--fw-semi);
  font-size: var(--fs-sm);
  background: var(--surface-2);
  color: var(--fg);
}
/* 金银铜是**材质色**（金属的固有色），不是主题色：
   它们在任何主题下都必须是同一种金黄色，故刻意不走主题变量。
   三者的前景色都取深色，与亮底配出足够对比 */
.rank-no--gold {
  background: #f6c343;
  color: #3d2b00;
}
.rank-no--silver {
  background: #c8ced8;
  color: #2b2f36;
}
.rank-no--bronze {
  background: #d99a5b;
  color: #3a230a;
}

.is-me {
  font-weight: var(--fw-semi);
  color: var(--accent);
}

.my-rank {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  margin-top: var(--sp-3);
  padding: var(--sp-3);
  border: 1px dashed var(--accent);
  border-radius: var(--r-md);
}

:deep(.is-my-row) {
  background: color-mix(in srgb, var(--accent) 10%, transparent) !important;
}
</style>
