<template>
  <div class="cj-page">
    <!-- ============ 页头：标题 + 总量读数 + 操作 ============ -->
    <header class="cj-pagehead">
      <h1 class="cj-pagehead__title">竞赛</h1>
      <span class="cj-pagehead__meta">
        共 <b>{{ total }}</b> 场
      </span>
      <div class="cj-spacer" />
      <div class="cj-pagehead__actions">
        <el-button
          v-perm="'contest:create'"
          type="primary"
          :icon="Plus"
          @click="router.push('/teacher/contests/new')"
        >
          创建竞赛
        </el-button>
        <el-button :icon="Refresh" @click="load">刷新</el-button>
      </div>
    </header>

    <!-- ============ 筛选条 ============ -->
    <div class="cj-toolbar">
      <el-input
        v-model.trim="query.keyword"
        placeholder="搜索竞赛标题"
        clearable
        :prefix-icon="Search"
        class="cj-toolbar__kw"
        @keyup.enter="applyFilter"
        @clear="applyFilter"
      />
      <el-select v-model="query.status" placeholder="状态" clearable class="cj-toolbar__sel" @change="applyFilter">
        <el-option label="未开始" :value="0" />
        <el-option label="进行中" :value="1" />
        <el-option label="已结束" :value="2" />
      </el-select>
      <el-checkbox v-model="query.registeredOnly" class="cj-toolbar__chk" @change="applyFilter">
        只看我报名的
      </el-checkbox>
    </div>

    <div class="cj-panel">
      <div class="cj-panel__table">
        <el-table
          v-loading="loading"
          :data="rows"
          row-key="id"
          :empty-text="loading ? '加载中…' : '暂无竞赛'"
          @row-click="goDetail"
        >
          <el-table-column label="竞赛" min-width="248">
            <template #default="{ row }">
              <!-- 主行：定宽右对齐的 #id + 标题；副行：赛程说明（grid 第 2 列，
                   与标题左边缘同一条线）。副行**恒定占位** —— 没有说明的场次
                   渲染 "—"，否则有说明的行 2 行、没有的 1 行，行高会参差 -->
              <div class="cj-cell-idname">
                <span class="cj-cell-idname__id cj-num" :title="`#${row.id}`">#{{ row.id }}</span>
                <span class="cj-cell-idname__name">{{ row.title }}</span>
                <span class="cj-cell-idname__sub">{{ row.description || '—' }}</span>
              </div>
            </template>
          </el-table-column>

          <!-- 状态类标签一律**独立成列**、一格一枚。
               原先「状态 / 封榜中 / 已报名」三枚标签全内联在标题后面，
               标题列宽被它们反复挤压，且三枚都有的行比一枚的宽出一大截 -->
          <el-table-column label="状态" width="88" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="contestStatusTagType(row.status)" effect="light">
                {{ contestStatusLabel(row.status) }}
              </el-tag>
            </template>
          </el-table-column>

          <el-table-column label="封榜" width="88" align="center">
            <template #default="{ row }">
              <el-tag v-if="row.frozen" size="small" type="danger" effect="plain">封榜中</el-tag>
              <span v-else class="cj-dim">—</span>
            </template>
          </el-table-column>

          <el-table-column label="赛制" width="80" align="center">
            <template #default="{ row }">
              <el-tag size="small" effect="plain" :type="row.rule === 'IOI' ? 'warning' : 'primary'">
                {{ row.rule || 'ACM' }}
              </el-tag>
            </template>
          </el-table-column>

          <el-table-column label="赛程" width="212" align="right" class-name="cj-hide-sm">
            <template #default="{ row }">
              <div class="cj-cell-stack cj-cell-stack--end">
                <span class="cj-cell-stack__v cj-num">{{ fmtTime(row.startTime, false) }}</span>
                <span class="cj-cell-stack__sub cj-num">→ {{ fmtTime(row.endTime, false) }}</span>
              </div>
            </template>
          </el-table-column>

          <!-- 题目 / 报名：单值数据列，用 `.cj-cell-num` 取「主值」那一份排版
               （与「赛程」「通过率」的主值共用 CSS 规则）。
               原来只写 `.cj-num` —— 那只是等宽，没有字号/字重/颜色，
               于是同一张表里「赛程的日期是半粗、题目数是常规」，读起来是两套数字 -->
          <el-table-column label="题目" width="72" align="right" class-name="cj-hide-sm">
            <template #default="{ row }">
              <span class="cj-cell-num cj-num">{{ row.problemCount ?? 0 }}</span>
            </template>
          </el-table-column>

          <el-table-column label="报名" width="72" align="right" class-name="cj-hide-sm">
            <template #default="{ row }">
              <span class="cj-cell-num cj-num">{{ row.registerCount ?? 0 }}</span>
            </template>
          </el-table-column>

          <el-table-column label="我的报名" width="92" align="center">
            <template #default="{ row }">
              <el-tag v-if="row.myRegistered" size="small" type="success" effect="plain">已报名</el-tag>
              <span v-else class="cj-dim">—</span>
            </template>
          </el-table-column>

          <el-table-column label="操作" width="132" align="right">
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
import { onActivated, onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { Plus, Refresh, Search } from '@element-plus/icons-vue';
import { contestApi } from '@/api';
import { contestStatusLabel, contestStatusTagType, fmtTime } from '@/utils/format';

defineOptions({ name: 'ContestListView' });

const router = useRouter();
// 本页不需要登录态：唯一的权限相关按钮（创建竞赛）已交由 v-perm 指令按后端能力码渲染

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

let reqSeq = 0;
async function load() {
  const seq = ++reqSeq;
  loading.value = true;
  try {
    const page = await contestApi.page(buildParams());
    if (seq !== reqSeq) return; // 已有更新的请求发出，丢弃本次过期响应
    rows.value = page?.list || [];
    total.value = Number(page?.total || 0);
  } catch (e) {
    if (seq !== reqSeq) return;
    rows.value = [];
    total.value = 0;
    ElMessage.error(e.message || '加载竞赛列表失败');
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

<!-- 无 scoped 样式：一律用全局 data-list.css 的列表骨架 -->
