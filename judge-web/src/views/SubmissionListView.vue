<template>
  <div class="cj-page">
    <div class="cj-card">
      <div class="cj-card__head">
        <span class="cj-title">提交记录</span>
        <span class="cj-dim">
          <template v-if="user.isStudent">仅显示我自己的提交</template>
          <template v-else>教师/管理员视图：可按结论与题目过滤</template>
        </span>
      </div>

      <div class="cj-card__body">
        <div class="filters">
          <el-input
            v-model.trim="query.problemId"
            placeholder="按题目 ID 过滤"
            clearable
            class="filters__id"
            @keyup.enter="applyFilter"
            @clear="applyFilter"
          />
          <el-select v-model="query.status" placeholder="状态" clearable class="filters__sel" @change="applyFilter">
            <el-option v-for="(v, k) in SUBMISSION_STATUS" :key="k" :label="v.label" :value="k" />
          </el-select>
          <el-select v-model="query.verdict" placeholder="结论" clearable class="filters__sel" @change="applyFilter">
            <el-option v-for="(v, k) in VERDICT" :key="k" :label="`${v.label} (${k})`" :value="k" />
          </el-select>
          <div class="cj-spacer" />
          <el-button :icon="Refresh" @click="load">刷新</el-button>
        </div>

        <div class="cj-scroll-x" style="margin-top: 12px">
          <el-table
            v-loading="loading"
            :data="rows"
            stripe
            row-key="id"
            :empty-text="loading ? '加载中…' : '暂无提交记录'"
            @row-click="goDetail"
          >
            <el-table-column label="提交" width="110">
              <template #default="{ row }">
                <span class="cj-mono cj-dim">#{{ row.id }}</span>
              </template>
            </el-table-column>

            <el-table-column label="题目" min-width="150">
              <template #default="{ row }">
                <el-link type="primary" @click.stop="goProblem(row.problemId)">
                  #{{ row.problemId }}
                </el-link>
                <el-tag v-if="row.contestId" size="small" type="warning" effect="plain" style="margin-left: 6px">
                  竞赛
                </el-tag>
              </template>
            </el-table-column>

            <el-table-column label="语言" width="130" class-name="cj-hide-sm">
              <template #default="{ row }">
                <span class="cj-mono cj-dim">{{ languageLabel(row.language) }}</span>
              </template>
            </el-table-column>

            <el-table-column label="结论" width="130">
              <template #default="{ row }">
                <VerdictTag :verdict="row.verdict" :pending-text="statusLabel(row.status)" />
              </template>
            </el-table-column>

            <el-table-column v-if="!user.isStudent" label="提交人" width="110" class-name="cj-hide-sm">
              <template #default="{ row }">
                <span class="cj-mono cj-dim">#{{ row.userId }}</span>
              </template>
            </el-table-column>

            <el-table-column label="耗时 / 内存" width="150" class-name="cj-hide-sm">
              <template #default="{ row }">
                <span class="cj-mono cj-dim">
                  {{ fmtDuration(row.timeMs) }} / {{ fmtMemory(row.memoryKb) }}
                </span>
              </template>
            </el-table-column>

            <el-table-column label="提交时间" width="150">
              <template #default="{ row }">
                <el-tooltip :content="fmtTime(row.submitTime)" placement="top">
                  <span class="cj-dim">{{ fmtFromNow(row.submitTime) }}</span>
                </el-tooltip>
              </template>
            </el-table-column>

            <el-table-column label="操作" width="80" align="center">
              <template #default="{ row }">
                <el-button link type="primary" @click.stop="goDetail(row)">详情</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>

        <div class="pager">
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

async function load() {
  loading.value = true;
  try {
    const page = await submissionApi.page(buildParams());
    rows.value = page?.list || [];
    total.value = Number(page?.total || 0);
  } catch (e) {
    rows.value = [];
    total.value = 0;
    ElMessage.error(e.message || '加载提交记录失败');
  } finally {
    loading.value = false;
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

<style scoped>
.filters {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.filters__id {
  width: 180px;
}
.filters__sel {
  width: 150px;
}
.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}
@media (max-width: 900px) {
  .filters__id,
  .filters__sel {
    width: 100%;
  }
}
</style>
