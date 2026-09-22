<template>
  <div class="cj-page">
    <div class="cj-card">
      <div class="cj-card__head">
        <span class="cj-title">判题集群</span>
        <div class="cj-row">
          <el-checkbox v-model="autoRefresh">自动刷新（5s）</el-checkbox>
          <el-button size="small" :icon="Refresh" @click="loadAll">立即刷新</el-button>
        </div>
      </div>

      <div class="cj-card__body">
        <!-- 队列积压 / 死信积压：判题链路的两个关键健康指标 -->
        <div class="stats">
          <div class="stat">
            <div class="stat__v">{{ metrics.queueBacklog ?? '—' }}</div>
            <div class="stat__k">待判任务积压（Redis ZSet member 数）</div>
          </div>
          <div class="stat">
            <div class="stat__v" :class="{ 'is-bad': (metrics.deadTasks ?? 0) > 0 }">
              {{ metrics.deadTasks ?? '—' }}
            </div>
            <div class="stat__k">死信任务（DEAD 状态）</div>
          </div>
          <div class="stat">
            <div class="stat__v">{{ workers.length }}</div>
            <div class="stat__k">在线判题机</div>
          </div>
        </div>

        <el-alert
          v-if="(metrics.deadTasks ?? 0) > 0"
          type="warning"
          :closable="false"
          show-icon
          style="margin: 12px 0"
          title="存在死信任务：说明有提交在重试耗尽后仍未判成功，需要人工介入（通常是沙箱镜像缺失或宿主机资源不足）"
        />

        <div class="cj-scroll-x" style="margin-top: 14px">
          <el-table v-loading="loading" :data="workers" stripe empty-text="当前没有在线判题机">
            <el-table-column label="workerId" min-width="220">
              <template #default="{ row }">
                <span class="cj-mono">{{ row.workerId }}</span>
              </template>
            </el-table-column>
            <el-table-column label="心跳内容" min-width="180" class-name="cj-hide-sm">
              <template #default="{ row }">
                <span class="cj-mono cj-dim">{{ row.heartbeat || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="在跑任务数" width="130" align="center">
              <template #default="{ row }">
                <span class="cj-mono">{{ row.runningTasks ?? 0 }}</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="110" align="center">
              <template #default>
                <el-tag size="small" type="success" effect="plain">在线</el-tag>
              </template>
            </el-table-column>
          </el-table>
        </div>

        <p class="cj-dim" style="margin-top: 12px">
          在线判定依据是 Redis 心跳（TTL 30 秒，过期即视为离线）；在跑任务数来自负载 ZSet 的 score。
        </p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { Refresh } from '@element-plus/icons-vue';
import { submissionApi } from '@/api';

const loading = ref(false);
const workers = ref([]);
const metrics = ref({ queueBacklog: null, deadTasks: null });
const autoRefresh = ref(false);

let timer = null;

async function loadAll() {
  loading.value = true;
  try {
    const [ws, m] = await Promise.all([
      submissionApi.workers(),
      submissionApi.workerMetrics(),
    ]);
    workers.value = ws || [];
    metrics.value = m || { queueBacklog: null, deadTasks: null };
  } catch (e) {
    workers.value = [];
    metrics.value = { queueBacklog: null, deadTasks: null };
    ElMessage.error(e.message || '加载判题集群信息失败（该端点需要管理员角色）');
  } finally {
    loading.value = false;
  }
}

function restartTimer() {
  if (timer) {
    clearInterval(timer);
    timer = null;
  }
  if (autoRefresh.value) {
    timer = setInterval(loadAll, 5000);
  }
}

watch(autoRefresh, restartTimer);

onMounted(loadAll);
// 页面离开必须停掉轮询，否则切走后仍在后台打请求
onBeforeUnmount(() => timer && clearInterval(timer));
</script>

<style scoped>
.stats {
  display: flex;
  gap: var(--cj-gap);
  flex-wrap: wrap;
}
.stat {
  flex: 1 1 200px;
  min-width: 180px;
  padding: 12px 16px;
  border: 1px solid var(--cj-border);
  border-radius: 10px;
  background: var(--cj-panel-2);
}
.stat__v {
  font-size: 26px;
  font-weight: 700;
  font-family: var(--cj-mono);
  color: var(--cj-accent);
}
.stat__v.is-bad {
  color: var(--cj-wrong);
}
.stat__k {
  font-size: 12.5px;
  color: var(--cj-text-sub);
}
</style>
