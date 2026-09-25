<template>
  <div class="cj-page">
    <!-- ============ 页头：标题 + 总量读数 ============
         先给「这是什么、有多少」的读数，再给操作 —— 这是仪表的信息顺序。
         页头不套卡片：它是"页面"的属性，不是"卡片"的属性 -->
    <header class="cj-pagehead">
      <h1 class="cj-pagehead__title">题库</h1>
      <span class="cj-pagehead__meta">
        共 <b>{{ total }}</b> 题
      </span>
      <div class="cj-spacer" />
      <div class="cj-pagehead__actions">
        <el-button v-perm="'problem:create'" type="primary" :icon="Plus" @click="goCreate">
          新建题目
        </el-button>
      </div>
    </header>

    <!-- ============ 筛选条 ============ -->
    <div class="cj-toolbar">
      <el-input
        v-model.trim="query.keyword"
        placeholder="搜索题目标题"
        clearable
        :prefix-icon="Search"
        class="cj-toolbar__kw"
        @keyup.enter="applyFilter"
        @clear="applyFilter"
      />

      <el-select
        v-model="query.difficulty"
        placeholder="难度"
        clearable
        class="cj-toolbar__sel"
        @change="applyFilter"
      >
        <el-option v-for="d in [1, 2, 3, 4, 5]" :key="d" :label="difficultyLabel(d)" :value="d" />
      </el-select>

      <el-select
        v-model="query.tagId"
        placeholder="标签"
        clearable
        filterable
        class="cj-toolbar__sel"
        @change="applyFilter"
      >
        <el-option v-for="t in tags" :key="t.id" :label="t.name" :value="t.id" />
      </el-select>

      <!-- 状态与"只看我的"需要 problem:manage（教师/管理员）：学员即便传了后端也会忽略
           （服务端二次收敛），这里直接不渲染，避免给出无效控件造成"筛了没反应"的误解。
           这里用 v-if 而非 v-perm：v-perm 删的是 DOM 节点，而 <template> 不产生节点。 -->
      <template v-if="user.can('problem:manage')">
        <el-select
          v-model="query.status"
          placeholder="状态"
          clearable
          class="cj-toolbar__sel"
          @change="applyFilter"
        >
          <el-option label="草稿" :value="0" />
          <el-option label="已发布" :value="1" />
          <el-option label="已下线" :value="2" />
        </el-select>

        <el-checkbox v-model="query.onlyMine" class="cj-toolbar__chk" @change="applyFilter">
          只看我的
        </el-checkbox>
      </template>

      <div class="cj-spacer" />
      <el-button :icon="Refresh" text @click="resetFilter">重置</el-button>
    </div>

    <!-- ============ 数据面板 ============
         表格单独放在一块面板上：它需要一块比页面底更亮的表面承载密集文字 -->
    <div class="cj-panel">
      <div class="cj-panel__table">
        <el-table
          v-loading="loading"
          :data="rows"
          row-key="id"
          :empty-text="loading ? '加载中…' : '没有符合条件的题目'"
          @row-click="goDetail"
        >
          <!-- 序号：等宽右对齐，让位宽随总数增长时仍然对齐 -->
          <el-table-column label="#" width="62" align="right">
            <template #default="{ $index }">
              <span class="cj-num-dim">
                {{ (query.pageNo - 1) * query.pageSize + $index + 1 }}
              </span>
            </template>
          </el-table-column>

          <el-table-column label="题目" min-width="240">
            <template #default="{ row }">
              <!-- grid 固定 ID 列宽：否则 #12 与 #1234 宽度不同，
                   标题的左边缘会参差不齐，整列就没法扫 -->
              <div class="cj-cell-idname">
                <span class="cj-cell-idname__id cj-num" :title="`#${row.id}`">#{{ row.id }}</span>
                <span class="cj-cell-idname__name">{{ row.title }}</span>
              </div>
            </template>
          </el-table-column>

          <!-- 标签独立成列，不再垫在标题下方 —— 独立成列后才可沿列扫读，
               且不会让有标签的行比无标签的高（实测行高分布曾为 [50,64]）。
               本列参与"数据列右对齐"的统一基准，故 align="right" 与
               TagCell 的 align="end" 成对出现 —— flex 容器不响应 text-align，
               只加列属性是推不动标签的 -->
          <el-table-column label="标签" width="176" align="right" class-name="cj-hide-sm">
            <template #default="{ row }">
              <TagCell :tags="row.tags" :limit="2" align="end" />
            </template>
          </el-table-column>

          <!-- 难度原先按"状态列居中"处理（对齐契约里状态列居中）。
               但本表的 5 个数据列已统一到右对齐基准，单留一个居中列，
               就是用户看到的"表头及内容显示不一致"来源之一，故一并右对齐 -->
          <el-table-column label="难度" width="88" align="right">
            <template #default="{ row }">
              <el-tag size="small" :type="difficultyTagType(row.difficulty)" effect="light">
                {{ difficultyLabel(row.difficulty) }}
              </el-tag>
            </template>
          </el-table-column>

          <!-- 限制：改用与「通过率」**完全相同**的单元格原语 ——
               两行堆叠，主值（时限）亮色半粗等宽、副值（内存）暗色小号等宽。
               原来是单行 `.cj-num-dim`（最小最暗），夹在一枚彩色标签和一个亮数字之间，
               是全表唯一"降级显示"的数值列 —— 这正是"样式不一致"的实质 -->
          <el-table-column label="限制" width="132" align="right" class-name="cj-hide-sm">
            <template #default="{ row }">
              <div class="cj-cell-stack cj-cell-stack--end">
                <span class="cj-cell-stack__v cj-num">{{ row.timeLimitMs }}ms</span>
                <span class="cj-cell-stack__sub cj-num">{{ row.memoryLimitMb }}MB</span>
              </div>
            </template>
          </el-table-column>

          <!-- 通过率是本页最重要的可扫描列：两个数字都等宽右对齐，
               用户才能沿着这一列竖着比出高低。
               刻意**不**用绿色：通过率是「测量值」不是「判题结论」。
               把 5% 和 82% 涂成同一种绿等于没有信息，还会误读成"好"；
               这里改为整行最亮的文字，靠亮度而非色相取得扫描优先级 -->
          <el-table-column label="通过率" width="128" align="right">
            <template #default="{ row }">
              <!-- 提交数为 0 时后端下发 null（刻意设计），展示为 "—" 而不是 "0%" -->
              <div class="cj-cell-stack cj-cell-stack--end">
                <span
                  class="cj-cell-stack__v cj-num"
                  :class="{ 'cj-cell-stack__v--null': row.acceptedRate === null }"
                >
                  {{ fmtPercent(row.acceptedRate) }}
                </span>
                <span class="cj-cell-stack__sub cj-num">
                  {{ row.acceptedCount ?? 0 }} / {{ row.submitCount ?? 0 }}
                </span>
              </div>
            </template>
          </el-table-column>

          <!-- 表格列必须用 v-if：v-perm 会把列节点的 DOM 删掉，破坏 el-table 的列布局 -->
          <el-table-column v-if="user.can('problem:manage')" label="状态" width="90" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="problemStatusTagType(row.status)" effect="plain">
                {{ problemStatusLabel(row.status) }}
              </el-tag>
            </template>
          </el-table-column>

          <el-table-column label="操作" width="118" align="right">
            <template #default="{ row }">
              <el-button link type="primary" @click.stop="goDetail(row)">查看</el-button>
              <el-button v-perm="'problem:edit'" link type="primary" @click.stop="goEdit(row)">
                编辑
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <div class="cj-pager">
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
</template>

<script setup>
import { onActivated, onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { Plus, Refresh, Search } from '@element-plus/icons-vue';
import TagCell from '@/components/TagCell.vue';
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
  // 状态/只看我的属于管理视图参数：无权限时不发，避免"传了被忽略"的静默行为
  if (user.can('problem:manage')) {
    if (query.status !== null && query.status !== '') p.status = query.status;
    if (query.onlyMine) p.onlyMine = true;
  }
  return p;
}

let reqSeq = 0;
async function load() {
  const seq = ++reqSeq;
  loading.value = true;
  try {
    const page = await problemApi.page(buildParams());
    if (seq !== reqSeq) return; // 已有更新的请求发出，丢弃本次过期响应
    rows.value = page?.list || [];
    total.value = Number(page?.total || 0);
  } catch (e) {
    if (seq !== reqSeq) return;
    rows.value = [];
    total.value = 0;
    ElMessage.error(e.message || '加载题目列表失败');
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

<!-- 无 scoped 样式：列表页的所有版式都来自全局 styles/data-list.css。
     这正是本次统一的目的 —— 同一处版式只有一份定义，改一次全局生效 -->
