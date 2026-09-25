<template>
  <div class="cj-page">
    <!-- ============ 页头 ============ -->
    <header class="cj-pagehead">
      <h1 class="cj-pagehead__title">判题集群</h1>
      <span class="cj-pagehead__meta">
        在线 <b>{{ workers.length }}</b> 台
      </span>
      <div class="cj-spacer" />
      <div class="cj-pagehead__actions">
        <el-checkbox v-model="autoRefresh">自动刷新（5s）</el-checkbox>
        <el-button :icon="Refresh" @click="loadAll">立即刷新</el-button>
      </div>
    </header>

    <!-- ============ 关键读数 ============ -->
    <div class="cj-toolbar">
      <div class="cj-stats">
        <div class="cj-stat">
          <div class="cj-stat__v">{{ metrics.queueBacklog ?? '—' }}</div>
          <div class="cj-stat__k">待判任务积压（Redis ZSet member 数）</div>
        </div>
        <div class="cj-stat">
          <div class="cj-stat__v" :class="{ 'cj-stat__v--bad': (metrics.deadTasks ?? 0) > 0 }">
            {{ metrics.deadTasks ?? '—' }}
          </div>
          <div class="cj-stat__k">死信任务（DEAD 状态）</div>
        </div>
        <div class="cj-stat">
          <div class="cj-stat__v">{{ workers.length }}</div>
          <div class="cj-stat__k">在线判题机</div>
        </div>
      </div>
    </div>

    <el-alert
      v-if="(metrics.deadTasks ?? 0) > 0"
      type="warning"
      :closable="false"
      show-icon
      style="margin-bottom: 12px"
      title="存在死信任务：说明有提交在重试耗尽后仍未判成功，需要人工介入（通常是沙箱镜像缺失或宿主机资源不足）"
    />

    <!-- ============ 节点列表 ============ -->
    <div class="cj-panel">
      <div class="cj-panel__table">
        <el-table v-loading="loading" :data="workers" row-key="workerId" empty-text="当前没有在线判题机">
          <el-table-column label="workerId" min-width="240">
            <template #default="{ row }">
              <span class="cj-mono">{{ row.workerId }}</span>
            </template>
          </el-table-column>

          <el-table-column label="心跳内容" min-width="200" class-name="cj-hide-sm">
            <template #default="{ row }">
              <span class="cj-mono cj-dim">{{ row.heartbeat || '—' }}</span>
            </template>
          </el-table-column>

          <el-table-column label="在跑任务数" width="130" align="right">
            <template #default="{ row }">
              <span class="cj-num">{{ row.runningTasks ?? 0 }}</span>
            </template>
          </el-table-column>

          <el-table-column label="状态" width="110" align="center">
            <template #default>
              <el-tag size="small" type="success" effect="plain">在线</el-tag>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <div class="cj-panel__note">
        在线判定依据是 Redis 心跳（TTL 30 秒，过期即视为离线）；在跑任务数来自负载 ZSet 的 score。
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

<!-- 无 scoped 样式：读数卡片用全局 .cj-stats / .cj-stat，
     此前这里与监控页、知识库页各写了一份字号 24/26、圆角 10px 的都不同 -->
