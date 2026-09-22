<template>
  <div class="cj-page">
    <div class="cj-card">
      <div class="cj-card__head">
        <span class="cj-title">题目管理</span>
        <div class="cj-row">
          <el-button type="primary" :icon="Plus" @click="router.push('/teacher/problems/new')">
            新建题目
          </el-button>
          <el-button :icon="Refresh" @click="load">刷新</el-button>
        </div>
      </div>

      <div class="cj-card__body">
        <div class="filters">
          <el-input
            v-model.trim="query.keyword"
            placeholder="搜索标题"
            clearable
            class="filters__kw"
            @keyup.enter="applyFilter"
            @clear="applyFilter"
          />
          <el-select v-model="query.status" placeholder="状态" clearable class="filters__sel" @change="applyFilter">
            <el-option label="草稿" :value="0" />
            <el-option label="已发布" :value="1" />
            <el-option label="已下线" :value="2" />
          </el-select>
          <el-checkbox v-model="query.onlyMine" @change="applyFilter">只看我的</el-checkbox>
          <div class="cj-spacer" />
        </div>

        <div class="cj-scroll-x" style="margin-top: 12px">
          <el-table v-loading="loading" :data="rows" stripe row-key="id" empty-text="暂无题目">
            <el-table-column label="ID" width="90">
              <template #default="{ row }">
                <span class="cj-mono cj-dim">{{ row.id }}</span>
              </template>
            </el-table-column>

            <el-table-column label="标题" min-width="200">
              <template #default="{ row }">
                <el-link type="primary" @click="router.push({ name: 'problem-detail', params: { id: row.id } })">
                  {{ row.title }}
                </el-link>
                <div v-if="row.tags?.length" style="margin-top: 3px">
                  <el-tag v-for="t in row.tags" :key="t.id" size="small" effect="plain" type="info" style="margin-right: 4px">
                    {{ t.name }}
                  </el-tag>
                </div>
              </template>
            </el-table-column>

            <el-table-column label="难度" width="86" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="difficultyTagType(row.difficulty)" effect="light">
                  {{ difficultyLabel(row.difficulty) }}
                </el-tag>
              </template>
            </el-table-column>

            <el-table-column label="状态" width="150" align="center">
              <template #default="{ row }">
                <el-select
                  :model-value="row.status"
                  size="small"
                  style="width: 108px"
                  @change="(v) => onStatusChange(row, v)"
                >
                  <el-option label="草稿" :value="0" />
                  <el-option label="已发布" :value="1" />
                  <el-option label="已下线" :value="2" />
                </el-select>
              </template>
            </el-table-column>

            <el-table-column label="提交 / 通过" width="120" align="center" class-name="cj-hide-sm">
              <template #default="{ row }">
                <span class="cj-mono cj-dim">{{ row.acceptedCount }} / {{ row.submitCount }}</span>
                <div class="cj-dim">{{ fmtPercent(row.acceptedRate) }}</div>
              </template>
            </el-table-column>

            <el-table-column label="更新时间" width="160" class-name="cj-hide-sm">
              <template #default="{ row }">
                <span class="cj-dim">{{ fmtTime(row.updateTime) }}</span>
              </template>
            </el-table-column>

            <el-table-column label="操作" width="210" align="center">
              <template #default="{ row }">
                <el-button link type="primary" @click="router.push({ name: 'teacher-problem-edit', params: { id: row.id } })">
                  编辑
                </el-button>
                <el-button link type="primary" @click="openCases(row)">用例</el-button>
                <el-button link type="danger" @click="onDelete(row)">删除</el-button>
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

    <!-- ================= 用例管理抽屉 ================= -->
    <el-drawer v-model="caseDrawer" :title="`测试用例 · ${currentProblem?.title || ''}`" size="640px">
      <div class="case-tools">
        <span class="cj-dim">
          共 {{ cases.length }} 条 · 隐藏 {{ hiddenCount }} 条
          <br />
          用例删除是**物理删除**，所以删掉序号后可以重新加回同一序号（后端设计如此）
        </span>
        <div class="cj-spacer" />
        <el-button size="small" type="primary" :icon="Plus" @click="addEmptyCase">新增用例</el-button>
        <el-button size="small" :loading="savingCases" @click="saveCases">保存全部</el-button>
      </div>

      <el-alert
        v-if="caseError"
        type="error"
        :closable="false"
        show-icon
        style="margin-bottom: 10px"
        :title="caseError"
      />

      <el-empty v-if="!cases.length" description="还没有用例。至少要有 1 组样例才能正常判题" :image-size="70" />

      <div v-for="(c, i) in cases" :key="i" class="case-item">
        <div class="case-item__head">
          <el-input-number v-model="c.seq" :min="1" size="small" controls-position="right" style="width: 96px" />
          <el-checkbox v-model="c.isHidden" :true-value="1" :false-value="0">隐藏用例</el-checkbox>
          <span class="cj-dim">分值</span>
          <el-input-number v-model="c.score" :min="0" size="small" controls-position="right" style="width: 104px" />
          <span class="cj-dim">比对</span>
          <el-select v-model="c.judgeMode" size="small" style="width: 104px">
            <el-option label="精确" :value="0" />
            <el-option label="浮点容差" :value="1" />
            <el-option label="特判" :value="2" />
          </el-select>
          <div class="cj-spacer" />
          <el-button link type="danger" @click="cases.splice(i, 1)">移除</el-button>
        </div>
        <div class="case-item__grid">
          <div>
            <div class="case-label">标准输入</div>
            <el-input v-model="c.stdin" type="textarea" :rows="3" />
          </div>
          <div>
            <div class="case-label">期望输出</div>
            <el-input v-model="c.expectedStdout" type="textarea" :rows="3" />
          </div>
        </div>
        <div class="case-item__grid" style="margin-top: 6px">
          <div>
            <div class="case-label">用例级时限(ms，留空沿用题目限制)</div>
            <el-input v-model.number="c.timeLimitMs" placeholder="留空" />
          </div>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus, Refresh } from '@element-plus/icons-vue';
import { problemApi } from '@/api';
import { difficultyLabel, difficultyTagType, fmtPercent, fmtTime } from '@/utils/format';

const router = useRouter();

const loading = ref(false);
const rows = ref([]);
const total = ref(0);

const query = reactive({ keyword: '', status: null, onlyMine: false, pageNo: 1, pageSize: 20 });

/* ---------------- 用例抽屉 ---------------- */
const caseDrawer = ref(false);
const currentProblem = ref(null);
const cases = ref([]);
const savingCases = ref(false);
const caseError = ref('');

const hiddenCount = computed(() => cases.value.filter((c) => c.isHidden === 1).length);

async function load() {
  loading.value = true;
  try {
    const p = { pageNo: query.pageNo, pageSize: query.pageSize };
    if (query.keyword) p.keyword = query.keyword;
    if (query.status !== null && query.status !== '') p.status = query.status;
    if (query.onlyMine) p.onlyMine = true;
    const page = await problemApi.page(p);
    rows.value = page?.list || [];
    total.value = Number(page?.total || 0);
  } catch (e) {
    rows.value = [];
    total.value = 0;
    ElMessage.error(e.message || '加载失败');
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

/**
 * 状态变更。
 *
 * ⚠️ 失败时必须把本地行状态**回滚**：el-select 是受控的（:model-value），
 * 但 row.status 已经不在本地改——这里刻意不预改本地值，靠 load() 重新拉取同步，
 * 因此失败时界面自然保持原状态。这比"先乐观更新再回滚"少一类不一致。
 */
async function onStatusChange(row, status) {
  try {
    await problemApi.updateStatus(row.id, status);
    ElMessage.success(`已更新为「${['草稿', '已发布', '已下线'][status]}」`);
    load();
  } catch (e) {
    ElMessage.error(e.message || '状态更新失败');
    load();
  }
}

async function onDelete(row) {
  try {
    await ElMessageBox.confirm(
      `删除题目「${row.title}」会连带物理删除其全部测试用例与标签关联，且不可恢复。确定删除？`,
      '高危操作',
      { type: 'error', confirmButtonText: '确认删除', cancelButtonText: '取消' },
    );
  } catch {
    return;
  }
  try {
    await problemApi.remove(row.id);
    ElMessage.success('已删除');
    load();
  } catch (e) {
    ElMessage.error(e.message || '删除失败');
  }
}

async function openCases(row) {
  currentProblem.value = row;
  caseDrawer.value = true;
  caseError.value = '';
  try {
    const list = await problemApi.listTestCases(row.id);
    cases.value = (list || []).map((c) => ({
      seq: c.seq,
      stdin: c.stdin ?? '',
      expectedStdout: c.expectedStdout ?? '',
      isHidden: c.isHidden ?? 0,
      score: c.score ?? 0,
      timeLimitMs: c.timeLimitMs ?? null,
      judgeMode: c.judgeMode ?? 0,
    }));
  } catch (e) {
    cases.value = [];
    caseError.value = e.message || '加载用例失败（仅题目归属教师/管理员可查看全部用例）';
  }
}

function addEmptyCase() {
  const nextSeq = cases.value.reduce((m, c) => Math.max(m, c.seq || 0), 0) + 1;
  cases.value.push({
    seq: nextSeq,
    stdin: '',
    expectedStdout: '',
    isHidden: 0,
    score: 0,
    timeLimitMs: null,
    judgeMode: 0,
  });
}

/**
 * 保存全部用例 —— 走 **全量替换** 语义（PUT /problems/{id}/test-cases）。
 *
 * 后端注释已明确这是破坏性操作（先清空再按序写入），因此这里在提交前做一次校验：
 *  · seq 不得重复（唯一键冲突会导致整批失败，报错信息不直观）；
 *  · 至少一组样例（全部隐藏的话学员看不到任何样例，判题体验很差）。
 */
async function saveCases() {
  caseError.value = '';
  const seqs = cases.value.map((c) => c.seq);
  if (new Set(seqs).size !== seqs.length) {
    caseError.value = '存在重复的用例序号（seq 必须唯一）';
    return;
  }
  if (!cases.value.some((c) => c.isHidden === 0)) {
    try {
      await ElMessageBox.confirm(
        '当前没有任何"可见样例"用例，学员将看不到任何示例输入输出。仍要保存吗？',
        '提示',
        { type: 'warning' },
      );
    } catch {
      return;
    }
  }

  savingCases.value = true;
  try {
    const payload = cases.value.map((c) => ({
      seq: c.seq,
      stdin: c.stdin,
      expectedStdout: c.expectedStdout,
      isHidden: c.isHidden,
      score: c.score ?? 0,
      timeLimitMs: c.timeLimitMs === '' ? null : c.timeLimitMs,
      judgeMode: c.judgeMode ?? 0,
    }));
    const n = await problemApi.replaceTestCases(currentProblem.value.id, payload);
    ElMessage.success(`已写入 ${n} 条用例（全量替换）`);
    caseDrawer.value = false;
    load();
  } catch (e) {
    caseError.value = e.message || '保存用例失败';
  } finally {
    savingCases.value = false;
  }
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
.filters__kw {
  width: 220px;
}
.filters__sel {
  width: 140px;
}
.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}

.case-tools {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.case-item {
  border: 1px solid var(--cj-border);
  border-radius: 8px;
  padding: 10px 12px;
  margin-bottom: 10px;
}
.case-item__head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}
.case-item__grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}
.case-label {
  font-size: 12px;
  color: var(--cj-text-dim);
  margin-bottom: 4px;
}

@media (max-width: 900px) {
  .filters__kw,
  .filters__sel {
    width: 100%;
  }
  .case-item__grid {
    grid-template-columns: 1fr;
  }
}
</style>
