<template>
  <div class="cj-page">
    <div class="cj-card">
      <div class="cj-card__head">
        <span class="cj-title">竞赛</span>
        <div class="cj-row">
          <el-button v-if="user.canManage" type="primary" :icon="Plus" @click="router.push('/teacher/contests/new')">
            创建竞赛
          </el-button>
        </div>
      </div>

      <div class="cj-card__body">
        <div class="filters">
          <el-input
            v-model.trim="query.keyword"
            placeholder="搜索竞赛标题"
            clearable
            :prefix-icon="Search"
            class="filters__kw"
            @keyup.enter="applyFilter"
            @clear="applyFilter"
          />
          <el-select v-model="query.status" placeholder="状态" clearable class="filters__sel" @change="applyFilter">
            <el-option label="未开始" :value="0" />
            <el-option label="进行中" :value="1" />
            <el-option label="已结束" :value="2" />
          </el-select>
          <el-checkbox v-model="query.registeredOnly" class="filters__chk" @change="applyFilter">
            只看我报名的
          </el-checkbox>
          <div class="cj-spacer" />
          <el-button :icon="Refresh" @click="load">刷新</el-button>
        </div>

        <div class="cj-scroll-x" style="margin-top: 12px">
          <el-table
            v-loading="loading"
            :data="rows"
            stripe
            row-key="id"
            :empty-text="loading ? '加载中…' : '暂无竞赛'"
            @row-click="goDetail"
          >
            <el-table-column label="竞赛" min-width="220">
              <template #default="{ row }">
                <div class="cell-title">
                  <span class="cell-title__name">{{ row.title }}</span>
                  <el-tag size="small" :type="contestStatusTagType(row.status)" effect="light">
                    {{ contestStatusLabel(row.status) }}
                  </el-tag>
                  <el-tag v-if="row.frozen" size="small" type="danger" effect="dark">封榜中</el-tag>
                  <el-tag v-if="row.myRegistered" size="small" type="success" effect="plain">已报名</el-tag>
                </div>
                <div v-if="row.description" class="cj-dim cell-desc">{{ row.description }}</div>
              </template>
            </el-table-column>

            <el-table-column label="赛制" width="90" align="center">
              <template #default="{ row }">
                <el-tag size="small" effect="plain" :type="row.rule === 'IOI' ? 'warning' : 'primary'">
                  {{ row.rule || 'ACM' }}
                </el-tag>
              </template>
            </el-table-column>

            <el-table-column label="时间" width="230" class-name="cj-hide-sm">
              <template #default="{ row }">
                <div class="cj-mono cj-dim time-cell">
                  <span>{{ fmtTime(row.startTime, false) }}</span>
                  <span>→ {{ fmtTime(row.endTime, false) }}</span>
                </div>
              </template>
            </el-table-column>

            <el-table-column label="题目" width="80" align="center" class-name="cj-hide-sm">
              <template #default="{ row }">
                <span class="cj-mono">{{ row.problemCount ?? 0 }}</span>
              </template>
            </el-table-column>

            <el-table-column label="报名" width="80" align="center" class-name="cj-hide-sm">
              <template #default="{ row }">
                <span class="cj-mono">{{ row.registerCount ?? 0 }}</span>
              </template>
            </el-table-column>

            <el-table-column label="操作" width="160" align="center">
              <template #default="{ row }">
                <el-button link type="primary" @click.stop="goDetail(row)">榜单</el-button>
                <el-button
                  v-if="!row.myRegistered && row.status !== 2"
                  link
                  type="primary"
                  @click.stop="onRegister(row)"
                >
                  报名
                </el-button>
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
import { onActivated, onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { Plus, Refresh, Search } from '@element-plus/icons-vue';
import { contestApi } from '@/api';
import { useUserStore } from '@/stores/user';
import { contestStatusLabel, contestStatusTagType, fmtTime } from '@/utils/format';

defineOptions({ name: 'ContestListView' });

const router = useRouter();
const user = useUserStore();

const loading = ref(false);
const rows = ref([]);
const total = ref(0);

const query = reactive({
  keyword: '',
  status: null,
  registeredOnly: false,
  pageNo: 1,
  pageSize: 10,
});

function buildParams() {
  const p = { pageNo: query.pageNo, pageSize: query.pageSize };
  if (query.keyword) p.keyword = query.keyword;
  // status=0 是有意义的取值（未开始），不能用真值判断
  if (query.status !== null && query.status !== '') p.status = query.status;
  if (query.registeredOnly) p.registeredOnly = true;
  return p;
}

async function load() {
  loading.value = true;
  try {
    const page = await contestApi.page(buildParams());
    rows.value = page?.list || [];
    total.value = Number(page?.total || 0);
  } catch (e) {
    rows.value = [];
    total.value = 0;
    ElMessage.error(e.message || '加载竞赛列表失败');
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

async function onRegister(row) {
  try {
    await contestApi.register(row.id);
    ElMessage.success(`已报名「${row.title}」`);
    load();
  } catch (e) {
    ElMessage.error(e.message || '报名失败');
  }
}

function goDetail(row) {
  router.push({ name: 'contest-detail', params: { id: row.id } });
}

onMounted(load);
onActivated(() => {
  if (rows.value.length) load();
});
</script>

<style scoped>
.filters {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.filters__kw {
  width: 220px;
}
.filters__sel {
  width: 140px;
}
.cell-title {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  cursor: pointer;
}
.cell-title__name {
  font-weight: 600;
}
.cell-desc {
  margin-top: 3px;
  display: -webkit-box;
  -webkit-line-clamp: 1;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.time-cell {
  display: flex;
  flex-direction: column;
  line-height: 1.45;
}
.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}
@media (max-width: 900px) {
  .filters__kw,
  .filters__sel {
    width: 100%;
  }
}
</style>
