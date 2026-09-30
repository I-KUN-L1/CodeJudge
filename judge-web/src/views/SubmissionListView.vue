<template>
  <div class="cj-page">
    <!-- ============ 页头：标题 + 总量读数 ============ -->
    <header class="cj-pagehead">
      <h1 class="cj-pagehead__title">提交记录</h1>
      <span class="cj-pagehead__meta">
        共 <b>{{ total }}</b> 条
        <template v-if="!user.can('submission:view-all')"> · 仅显示我自己的提交</template>
        <template v-else> · 教师/管理员视图</template>
      </span>
      <div class="cj-spacer" />
      <div class="cj-pagehead__actions">
        <el-button :icon="Refresh" @click="load">刷新</el-button>
      </div>
    </header>

    <!-- ============ 筛选条 ============ -->
    <div class="cj-toolbar">
      <el-input
        v-model.trim="query.problemId"
        placeholder="按题目 ID 过滤"
        clearable
        class="cj-toolbar__num"
        @keyup.enter="applyFilter"
        @clear="applyFilter"
      />
      <el-select v-model="query.status" placeholder="状态" clearable class="cj-toolbar__sel" @change="applyFilter">
        <el-option v-for="(v, k) in SUBMISSION_STATUS" :key="k" :label="v.label" :value="k" />
      </el-select>
      <el-select v-model="query.verdict" placeholder="结论" clearable class="cj-toolbar__sel" @change="applyFilter">
        <el-option v-for="(v, k) in VERDICT" :key="k" :label="`${v.label} (${k})`" :value="k" />
      </el-select>
    </div>

    <div class="cj-panel">
      <div class="cj-panel__table">
        <el-table
          v-loading="loading"
          :data="rows"
          row-key="id"
          :empty-text="loading ? '加载中…' : '暂无提交记录'"
          @row-click="goDetail"
        >
          <!-- 提交号：等宽右对齐（与题库列表的序号列同一套约定）。
               列宽必须按 `#` + 19 位雪花号留够（实测 172px）：
               原来是 104px，而 el-table 的 .cell 默认 `word-break: break-all`，
               于是每一行的提交号都被从中间劈成两行（#2102699920 / 8152733985）。
               只加 `.cj-cell-id` 的 nowrap 不加宽，只会把文字挤到裁切边界上 -->
          <el-table-column label="提交" width="172" align="right">
            <template #default="{ row }">
              <span class="cj-cell-id cj-dim" :title="`#${row.id}`">#{{ row.id }}</span>
            </template>
          </el-table-column>

          <el-table-column label="题目" width="110" align="right">
            <template #default="{ row }">
              <el-link type="primary" class="cj-num" @click.stop="goProblem(row.problemId)">
                #{{ row.problemId }}
              </el-link>
            </template>
          </el-table-column>

          <!-- 「竞赛」原先是一枚内联在题目号后面的标签（还带 style="margin-left:6px"），
               标签宽度不定 → 该列文字左边缘随标签出现与否而漂移。
               改为独立列后，列边界由表头决定，与内容无关 -->
          <el-table-column label="来源" width="86" align="center">
            <template #default="{ row }">
              <el-tag v-if="row.contestId" size="small" type="warning" effect="plain">竞赛</el-tag>
              <span v-else class="cj-dim">日常</span>
            </template>
          </el-table-column>

          <el-table-column label="语言" width="130">
            <template #default="{ row }">
              <span class="cj-mono cj-dim">{{ languageLabel(row.language) }}</span>
            </template>
          </el-table-column>

          <el-table-column label="结论" width="130" align="center">
            <template #default="{ row }">
              <VerdictTag :verdict="row.verdict" :pending-text="statusLabel(row.status)" />
            </template>
          </el-table-column>

          <!-- 提交人同理：userId 也是 19 位雪花号，列宽与禁折行处理跟提交号一致 -->
          <el-table-column
            v-if="user.can('submission:view-all')"
            label="提交人"
            width="172"
            align="right"
            class-name="cj-hide-sm"
          >
            <template #default="{ row }">
              <span class="cj-cell-id cj-dim" :title="`#${row.userId}`">#{{ row.userId }}</span>
            </template>
          </el-table-column>

          <!-- 耗时 / 内存：主值在上、副值在下，两行同一条右边缘。
               原先写成一行 "12ms / 1024KB"，位数变化时右侧会参差不齐 -->
          <el-table-column label="耗时 / 内存" width="132" align="right" class-name="cj-hide-sm">
            <template #default="{ row }">
              <div class="cj-cell-stack cj-cell-stack--end">
                <span class="cj-cell-stack__v cj-num">{{ fmtDuration(row.timeMs) }}</span>
                <span class="cj-cell-stack__sub cj-num">{{ fmtMemory(row.memoryKb) }}</span>
              </div>
            </template>
          </el-table-column>

          <!-- 提交时间用 min-width（弹性列）：本页 9 列原本全部定宽，
               总宽 (~1016px) 小于面板内宽 (~1382px)，表格右侧留白、
               与题库/竞赛列表「尾列撑满面板」的骨架不一致。
               时间文本最短，适合做唯一弹性列；min-width=150 保住底宽 -->
          <el-table-column label="提交时间" min-width="150" align="right">
            <template #default="{ row }">
              <el-tooltip :content="fmtTime(row.submitTime)" placement="top">
                <span class="cj-num-dim">{{ fmtFromNow(row.submitTime) }}</span>
              </el-tooltip>
            </template>
          </el-table-column>

          <el-table-column label="操作" width="84" align="right">
            <template #default="{ row }">
              <el-button link type="primary" @click.stop="goDetail(row)">详情</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <div class="cj-pager">
        <el-pagination
          v-model:current-page="query.pageNo"
          v-model:page-size="query.pageSize"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          background
          @current-change="load"
          @size-change="onSizeChange"
        />
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { Refresh } from '@element-plus/icons-vue';
import VerdictTag from '@/components/VerdictTag.vue';
import { submissionApi } from '@/api';
import { useUserStore } from '@/stores/user';
import {
  SUBMISSION_STATUS,
  VERDICT,
  fmtDuration,
  fmtFromNow,
  fmtMemory,
  fmtTime,
  languageLabel,
  statusLabel,
} from '@/utils/format';

const route = useRoute();
const router = useRouter();
const user = useUserStore();

const loading = ref(false);
const rows = ref([]);
const total = ref(0);

const query = reactive({
  problemId: route.query.problemId ? String(route.query.problemId) : '',
  status: '',
  verdict: '',
  pageNo: 1,
  pageSize: 20,
});

function buildParams() {
  const p = { pageNo: query.pageNo, pageSize: query.pageSize };
  if (query.problemId) p.problemId = query.problemId;
  if (query.status) p.status = query.status;
  if (query.verdict) p.verdict = query.verdict;
  return p;
}

let reqSeq = 0;
async function load() {
  const seq = ++reqSeq;
  loading.value = true;
  try {
    const page = await submissionApi.page(buildParams());
    if (seq !== reqSeq) return; // 已有更新的请求发出，丢弃本次过期响应
    rows.value = page?.list || [];
    total.value = Number(page?.total || 0);
  } catch (e) {
    if (seq !== reqSeq) return;
    rows.value = [];
    total.value = 0;
    ElMessage.error(e.message || '加载提交记录失败');
  } finally {
    if (seq === reqSeq) loading.value = false;
  }
}

function applyFilter() {
  query.pageNo = 1;
  load();
}

function onSizeChange() {
  query.pageNo = 1;
  load();
}

function goDetail(row) {
  router.push({ name: 'submission-detail', params: { id: row.id } });
}

function goProblem(problemId) {
  router.push({ name: 'problem-detail', params: { id: problemId } });
}

onMounted(load);
</script>

<!-- 无 scoped 样式：一律用全局 data-list.css 的列表骨架 -->
