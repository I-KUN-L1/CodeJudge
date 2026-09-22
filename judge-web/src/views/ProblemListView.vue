<template>
  <div class="cj-page">
    <div class="cj-card">
      <div class="cj-card__head">
        <span class="cj-title">题库</span>
        <div class="cj-row">
          <el-button v-if="user.canManage" type="primary" :icon="Plus" @click="goCreate">
            新建题目
          </el-button>
        </div>
      </div>

      <div class="cj-card__body">
        <!-- 筛选区：窄屏自动折行为多行，不做横向挤压 -->
        <div class="filters">
          <el-input
            v-model.trim="query.keyword"
            placeholder="搜索题目标题"
            clearable
            :prefix-icon="Search"
            class="filters__kw"
            @keyup.enter="applyFilter"
            @clear="applyFilter"
          />

          <el-select v-model="query.difficulty" placeholder="难度" clearable class="filters__sel" @change="applyFilter">
            <el-option v-for="d in [1, 2, 3, 4, 5]" :key="d" :label="difficultyLabel(d)" :value="d" />
          </el-select>

          <el-select v-model="query.tagId" placeholder="标签" clearable filterable class="filters__sel" @change="applyFilter">
            <el-option v-for="t in tags" :key="t.id" :label="t.name" :value="t.id" />
          </el-select>

          <!-- 状态与"只看我的"是教师/管理员专属：学员传了后端也会忽略（服务端二次收敛），
               这里直接不显示，避免给出无效控件造成"筛了没反应"的误解 -->
          <template v-if="user.canManage">
            <el-select v-model="query.status" placeholder="状态" clearable class="filters__sel" @change="applyFilter">
              <el-option label="草稿" :value="0" />
              <el-option label="已发布" :value="1" />
              <el-option label="已下线" :value="2" />
            </el-select>

            <el-checkbox v-model="query.onlyMine" class="filters__chk" @change="applyFilter">
              只看我的
            </el-checkbox>
          </template>

          <div class="cj-spacer" />
          <el-button :icon="Refresh" @click="resetFilter">重置</el-button>
        </div>

        <div class="cj-scroll-x" style="margin-top: 12px">
          <el-table
            v-loading="loading"
            :data="rows"
            stripe
            row-key="id"
            :empty-text="loading ? '加载中…' : '没有符合条件的题目'"
            @row-click="goDetail"
          >
            <el-table-column label="#" width="64" align="center">
              <template #default="{ $index }">
                <span class="cj-dim cj-mono">{{ (query.pageNo - 1) * query.pageSize + $index + 1 }}</span>
              </template>
            </el-table-column>

            <el-table-column label="题目" min-width="240">
              <template #default="{ row }">
                <div class="cell-title">
                  <span class="cell-title__id cj-mono">#{{ row.id }}</span>
                  <span class="cell-title__name">{{ row.title }}</span>
                </div>
                <div v-if="row.tags?.length" class="cell-tags">
                  <el-tag v-for="t in row.tags" :key="t.id" size="small" effect="plain" type="info">
                    {{ t.name }}
                  </el-tag>
                </div>
              </template>
            </el-table-column>

            <el-table-column label="难度" width="90" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="difficultyTagType(row.difficulty)" effect="light">
                  {{ difficultyLabel(row.difficulty) }}
                </el-tag>
              </template>
            </el-table-column>

            <el-table-column label="限制" width="140" class-name="cj-hide-sm">
              <template #default="{ row }">
                <span class="cj-dim cj-mono">
                  {{ row.timeLimitMs }}ms / {{ row.memoryLimitMb }}MB
                </span>
              </template>
            </el-table-column>

            <el-table-column label="通过率" width="120" align="center">
              <template #default="{ row }">
                <!-- 提交数为 0 时后端下发 null（刻意设计），展示为 "—" 而不是 "0%" -->
                <span :class="row.acceptedRate === null ? 'cj-dim' : 'v-ac'">
                  {{ fmtPercent(row.acceptedRate) }}
                </span>
                <div class="cj-dim">{{ row.acceptedCount }} / {{ row.submitCount }}</div>
              </template>
            </el-table-column>

            <el-table-column v-if="user.canManage" label="状态" width="94" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="problemStatusTagType(row.status)" effect="plain">
                  {{ problemStatusLabel(row.status) }}
                </el-tag>
              </template>
            </el-table-column>

            <el-table-column label="操作" width="140" align="center">
              <template #default="{ row }">
                <el-button link type="primary" @click.stop="goDetail(row)">查看</el-button>
                <el-button
                  v-if="user.canManage"
                  link
                  type="primary"
                  @click.stop="goEdit(row)"
                >
                  编辑
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
            :page-sizes="[10, 20, 50, 100]"
            layout="total, sizes, prev, pager, next, jumper"
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
import { problemApi } from '@/api';
import { useUserStore } from '@/stores/user';
import {
  difficultyLabel,
  difficultyTagType,
  fmtPercent,
  problemStatusLabel,
  problemStatusTagType,
} from '@/utils/format';

defineOptions({ name: 'ProblemListView' });

const router = useRouter();
const user = useUserStore();

const loading = ref(false);
const rows = ref([]);
const total = ref(0);
const tags = ref([]);

const query = reactive({
  keyword: '',
  difficulty: null,
  tagId: null,
  status: null,
  onlyMine: false,
  pageNo: 1,
  pageSize: 20,
});

/** 只把有值的条件发出去：空串/ null 会被后端当作"该条件不参与过滤"，
 *  但传空串会让部分后端的 LIKE 变成 '%%' 全表扫描，显式剔除更稳。 */
function buildParams() {
  const p = { pageNo: query.pageNo, pageSize: query.pageSize };
  if (query.keyword) p.keyword = query.keyword;
  if (query.difficulty) p.difficulty = query.difficulty;
  if (query.tagId) p.tagId = query.tagId;
  if (user.canManage) {
    if (query.status !== null && query.status !== '') p.status = query.status;
    if (query.onlyMine) p.onlyMine = true;
  }
  return p;
}

async function load() {
  loading.value = true;
  try {
    const page = await problemApi.page(buildParams());
    rows.value = page?.list || [];
    total.value = Number(page?.total || 0);
  } catch (e) {
    rows.value = [];
    total.value = 0;
    ElMessage.error(e.message || '加载题目列表失败');
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

function resetFilter() {
  query.keyword = '';
  query.difficulty = null;
  query.tagId = null;
  query.status = null;
  query.onlyMine = false;
  applyFilter();
}

function goDetail(row) {
  router.push({ name: 'problem-detail', params: { id: row.id } });
}

function goEdit(row) {
  router.push({ name: 'teacher-problem-edit', params: { id: row.id } });
}

function goCreate() {
  router.push({ name: 'teacher-problem-new' });
}

async function loadTags() {
  try {
    tags.value = (await problemApi.tagList()) || [];
  } catch {
    tags.value = [];
  }
}

onMounted(() => {
  loadTags();
  load();
});

// 本页被 keep-alive 缓存：从详情页返回时保留筛选条件（高频操作），
// 但需要手动刷新一次，否则刚提交过的题目的"提交数/通过率"是旧的
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
  width: 132px;
}
.filters__chk {
  margin-left: 2px;
}

.cell-title {
  display: flex;
  align-items: baseline;
  gap: 8px;
  cursor: pointer;
}
.cell-title__id {
  font-size: 12px;
  color: var(--cj-text-dim);
}
.cell-title__name {
  font-weight: 600;
}
.cell-tags {
  display: flex;
  gap: 5px;
  flex-wrap: wrap;
  margin-top: 4px;
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
