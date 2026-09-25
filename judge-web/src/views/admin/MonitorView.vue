<template>
  <div class="cj-page">
    <!-- ================= 说明 ================= -->
    <div class="cj-card">
      <div class="cj-card__head">
        <span class="cj-title">系统监控</span>
        <div class="cj-row">
          <el-button size="small" :icon="Refresh" :loading="probing" @click="refreshAll">刷新</el-button>
        </div>
      </div>
      <div class="cj-card__body">
        <el-alert type="info" :closable="false" show-icon>
          <template #title>
            本页从各服务的 <code>/actuator/*</code> 端点**直连**读取
            （actuator 不在网关路由内，因此需要各服务端口可达）。
            指标的权威采集与长期存储由 Prometheus 承担，可视化与告警在 Grafana ——
            见 <code>deploy/monitoring/</code>。
          </template>
        </el-alert>

        <div class="links">
          <el-link type="primary" href="http://localhost:9090" target="_blank">
            Prometheus (9090) ↗
          </el-link>
          <el-link type="primary" href="http://localhost:3001" target="_blank">
            Grafana (3001) ↗
          </el-link>
          <el-link type="primary" href="http://localhost:18081" target="_blank">
            RocketMQ Dashboard (18081) ↗
          </el-link>
          <span class="cj-dim">
            监控栈未启动时上述链接不可用，属正常（用 docker compose -f deploy/monitoring/docker-compose.monitoring.yml up -d 启动）
          </span>
        </div>
      </div>
    </div>

    <!-- ================= 服务健康 ================= -->
    <div class="cj-card">
      <div class="cj-card__head">
        <span>服务健康</span>
        <span class="cj-dim">
          {{ upCount }} / {{ services.length }} UP
          <template v-if="lastProbeAt"> · 探测于 {{ lastProbeAt }}</template>
        </span>
      </div>
      <div class="cj-card__body">
        <div class="svc-grid">
          <div v-for="s in services" :key="s.name" class="svc" :class="{ 'is-down': !s.up }">
            <div class="svc__head">
              <span class="dot" :class="s.up ? 'dot--up' : 'dot--down'" />
              <span class="svc__name">{{ s.name }}</span>
            </div>
            <div class="svc__meta">
              <span class="cj-mono">:{{ s.port }}</span>
              <span class="cj-dim">{{ s.status }}</span>
            </div>
            <div class="svc__meta">
              <span class="cj-dim">响应 {{ s.latencyMs }} ms</span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- ================= 指标总览 ================= -->
    <div class="cj-card">
      <div class="cj-card__head">
        <span>指标总览（直连抓取 /actuator/prometheus）</span>
        <span class="cj-dim">{{ metricsLoaded }} / {{ services.length }} 个服务抓取成功</span>
      </div>
      <div class="cj-card__body">
        <el-alert
          v-if="metricsError"
          type="warning"
          :closable="false"
          show-icon
          style="margin-bottom: 12px"
          :title="metricsError"
        />

        <div class="cj-stats">
          <div class="cj-stat">
            <div class="cj-stat__v">{{ agg.totalRequests }}</div>
            <div class="cj-stat__k">累计请求数（8 服务合计）</div>
          </div>
          <div class="cj-stat">
            <div class="cj-stat__v" :class="{ 'cj-stat__v--bad': agg.serverErrors > 0 }">
              {{ agg.serverErrors }}
            </div>
            <div class="cj-stat__k">累计 5xx 错误</div>
          </div>
          <div class="cj-stat">
            <div class="cj-stat__v" :class="{ 'cj-stat__v--bad': (agg.serverErrorRate ?? 0) > 1 }">
              {{ agg.serverErrorRate === null ? '—' : `${agg.serverErrorRate.toFixed(2)}%` }}
            </div>
            <div class="cj-stat__k">5xx 占比（累计口径）</div>
          </div>
          <div class="cj-stat">
            <div class="cj-stat__v">{{ agg.avgMs === null ? '—' : `${agg.avgMs.toFixed(1)}ms` }}</div>
            <div class="cj-stat__k">平均响应耗时（累计）</div>
          </div>
          <div class="cj-stat">
            <div class="cj-stat__v">{{ fmtBytes(agg.heapUsed) }}</div>
            <div class="cj-stat__k">堆内存合计</div>
          </div>
        </div>

        <p class="cj-dim" style="margin: 10px 0 18px">
          注意：<code>*_count</code> / <code>_sum</code> 是**进程启动以来的累计值**，不是实时速率。
          要看 QPS / P95 / 错误率时序曲线，请用 Grafana 面板（PromQL 的 rate()）。
        </p>

        <div class="charts">
          <div class="charts__item">
            <div class="charts__title">各服务累计请求量</div>
            <EChartPanel :option="reqChartOption" />
          </div>
          <div class="charts__item">
            <div class="charts__title">请求量 Top URI（累计）</div>
            <EChartPanel :option="uriChartOption" />
          </div>
        </div>
      </div>
    </div>

    <!-- ================= 逐服务明细 ================= -->
    <div class="cj-card">
      <div class="cj-card__head">
        <span>逐服务明细</span>
        <span class="cj-dim">JVM 堆 / CPU / 连接池 / 请求统计</span>
      </div>
      <div class="cj-card__body">
        <div class="cj-scroll-x">
          <el-table :data="serviceMetrics" empty-text="尚未抓到任何指标">
            <el-table-column label="服务" min-width="150">
              <template #default="{ row }">
                <span class="cj-mono">{{ row.name }}</span>
              </template>
            </el-table-column>
            <el-table-column label="累计请求" width="110" align="right">
              <template #default="{ row }">
                <span class="cj-mono">{{ row.total }}</span>
              </template>
            </el-table-column>
            <el-table-column label="5xx" width="90" align="right">
              <template #default="{ row }">
                <span class="cj-mono" :class="{ 'v-wa': row.serverErrors > 0 }">{{ row.serverErrors }}</span>
              </template>
            </el-table-column>
            <el-table-column label="平均耗时" width="110" align="right">
              <template #default="{ row }">
                <span class="cj-mono">{{ row.avgMs === null ? '—' : `${row.avgMs.toFixed(1)}ms` }}</span>
              </template>
            </el-table-column>
            <el-table-column label="堆内存" width="110" align="right">
              <template #default="{ row }">
                <span class="cj-mono">{{ fmtBytes(row.heapUsed) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="CPU" width="100" align="right">
              <template #default="{ row }">
                <span class="cj-mono">{{ row.cpu === null ? '—' : `${(row.cpu * 100).toFixed(1)}%` }}</span>
              </template>
            </el-table-column>
            <el-table-column label="活跃连接" width="110" align="right" class-name="cj-hide-sm">
              <template #default="{ row }">
                <span class="cj-mono">{{ row.hikari === null ? '—' : row.hikari }}</span>
              </template>
            </el-table-column>
            <el-table-column label="抓取" width="100" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="row.ok ? 'success' : 'danger'" effect="plain">
                  {{ row.ok ? '正常' : '失败' }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import EChartPanel from '@/components/EChartPanel.vue';
import { SERVICE_PORTS, fetchPrometheus, probeAllServices } from '@/api';
import {
  fmtBytes,
  heapUsedBytes,
  hikariActive,
  httpOverview,
  parseMetrics,
  processCpu,
  topUris,
} from '@/utils/prometheus';

const probing = ref(false);
const services = ref([]);
const serviceMetrics = ref([]);
const metricsLoaded = ref(0);
const metricsError = ref('');
const lastProbeAt = ref('');

const agg = reactive({
  totalRequests: 0,
  serverErrors: 0,
  serverErrorRate: null,
  avgMs: null,
  heapUsed: null,
});

const upCount = computed(() => services.value.filter((s) => s.up).length);

const reqChartOption = computed(() => {
  const rows = serviceMetrics.value.filter((r) => r.ok);
  return {
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: 34, right: 16, top: 24, bottom: 30, containLabel: true },
    xAxis: {
      type: 'category',
      data: rows.map((r) => r.name.replace('judge-', '')),
      axisLabel: { interval: 0, rotate: 30 },
    },
    yAxis: { type: 'value', name: '请求数' },
    series: [
      {
        type: 'bar',
        data: rows.map((r) => r.total),
        barMaxWidth: 32,
        // 不写 itemStyle.color：系列色由 EChartPanel 从 CSS 变量 --chart-1 注入
        label: { show: true, position: 'top', fontSize: 10 },
      },
    ],
  };
});

const uriChartOption = computed(() => {
  const rows = topUris(globalMetrics.value, 8);
  return {
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: 34, right: 24, top: 20, bottom: 24, containLabel: true },
    xAxis: { type: 'value' },
    yAxis: {
      type: 'category',
      inverse: true,
      data: rows.map((r) => r.uri),
      axisLabel: { fontSize: 11, width: 160, overflow: 'truncate' },
    },
    series: [
      {
        type: 'bar',
        data: rows.map((r) => r.count),
        barMaxWidth: 16,
        // 不写 itemStyle.color：系列色由 EChartPanel 从 CSS 变量注入
      },
    ],
  };
});

/** 8 个服务的指标合并（URI 维度跨服务统计用）。
 * 必须是 ref：uriChartOption 这个 computed 只依赖它，普通变量不会触发重算（图表永远空数据） */
const globalMetrics = ref({});

async function refreshAll() {
  probing.value = true;
  metricsError.value = '';

  // 1) 健康探测（快，先出结果让页面立刻有内容）
  services.value = await probeAllServices();
  lastProbeAt.value = new Date().toLocaleTimeString('zh-CN');

  // 2) 抓取 Prometheus 指标
  const entries = Object.entries(SERVICE_PORTS);
  const results = await Promise.all(
    entries.map(async ([name, port]) => {
      try {
        const text = await fetchPrometheus(port);
        return { name, port, text, ok: true };
      } catch (e) {
        return { name, port, text: '', ok: false, error: e.message };
      }
    }),
  );

  globalMetrics.value = {};
  const perService = [];
  let okCount = 0;

  for (const r of results) {
    if (!r.ok) {
      perService.push({
        name: r.name,
        total: 0,
        serverErrors: 0,
        avgMs: null,
        heapUsed: null,
        cpu: null,
        hikari: null,
        ok: false,
      });
      continue;
    }
    okCount++;
    const m = parseMetrics(r.text);
    // 合并到全局（同名指标直接拼接 series）
    for (const [k, v] of Object.entries(m)) {
      (globalMetrics.value[k] ||= []).push(...v);
    }

    const http = httpOverview(m);
    perService.push({
      name: r.name,
      total: http.total,
      serverErrors: http.serverErrors,
      avgMs: http.avgMs,
      heapUsed: heapUsedBytes(m) || null,
      cpu: processCpu(m),
      hikari: hikariActive(m),
      ok: true,
    });
  }

  metricsLoaded.value = okCount;
  serviceMetrics.value = perService;

  const globalHttp = httpOverview(globalMetrics);
  Object.assign(agg, {
    totalRequests: globalHttp.total,
    serverErrors: globalHttp.serverErrors,
    serverErrorRate: globalHttp.serverErrorRate,
    avgMs: globalHttp.avgMs,
    heapUsed: heapUsedBytes(globalMetrics) || null,
  });

  if (okCount === 0) {
    metricsError.value =
      '没有抓到任何 /actuator/prometheus 指标。若服务已启动，请确认各服务已暴露 prometheus 端点（P6 已为 8 个服务统一开启）。';
  } else if (okCount < entries.length) {
    metricsError.value = `${entries.length - okCount} 个服务的指标抓取失败（服务未启动或端口不可达）`;
  }

  probing.value = false;
}

onMounted(refreshAll);
</script>

<style scoped>
/* 读数卡片用全局 .cj-stats / .cj-stat（原先本页、判题集群页、知识库页
   各写了一份字号 24/26、圆角 10px 都不同的实现） */

code {
  font-family: var(--font-mono);
  background: var(--surface-2);
  border: 1px solid var(--line-1);
  border-radius: var(--r-xs);
  padding: 0 var(--sp-1);
}

.links {
  display: flex;
  gap: var(--sp-4);
  align-items: center;
  flex-wrap: wrap;
  margin-top: var(--sp-3);
  font-size: var(--fs-sm);
}

/* 服务方块：等宽网格，避免"每行 3 个 2 个 4 个"的参差 */
.svc-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(190px, 1fr));
  gap: var(--sp-2);
}
.svc {
  border: 1px solid var(--line-1);
  border-radius: var(--r-md);
  padding: var(--sp-3);
  background: var(--surface-2);
}
.svc.is-down {
  border-color: var(--wa);
}
.svc__head {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  font-weight: var(--fw-semi);
  font-size: var(--fs-sm);
}
.svc__meta {
  display: flex;
  gap: var(--sp-2);
  font-size: var(--fs-xs);
  margin-top: var(--sp-1);
}
.dot {
  width: 8px;
  height: 8px;
  border-radius: var(--r-full);
  flex: 0 0 auto;
}
.dot--up {
  background: var(--ac);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--ac) 22%, transparent);
}
.dot--down {
  background: var(--wa);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--wa) 22%, transparent);
}

.charts {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--sp-4);
}
.charts__title {
  font-size: var(--fs-sm);
  font-weight: var(--fw-semi);
  color: var(--fg-2);
  margin-bottom: var(--sp-2);
}

@media (max-width: 900px) {
  .charts {
    grid-template-columns: 1fr;
  }
}
</style>
