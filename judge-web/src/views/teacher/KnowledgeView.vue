<template>
  <div class="cj-page">
    <!-- ================= 规模概览 ================= -->
    <div class="cj-card">
      <div class="cj-card__head">
        <span class="cj-title">AI 知识库（pgvector）</span>
        <el-button size="small" :icon="Refresh" @click="loadCount">刷新</el-button>
      </div>
      <div class="cj-card__body">
        <div class="cj-stats">
          <div class="cj-stat">
            <div class="cj-stat__v">{{ count.knowledgeChunks ?? '—' }}</div>
            <div class="cj-stat__k">知识切片数</div>
          </div>
          <div class="cj-stat">
            <div class="cj-stat__v">{{ count.reviews ?? '—' }}</div>
            <div class="cj-stat__k">历史点评数</div>
          </div>
          <div class="cj-stat cj-stat--note">
            知识切片会被注入每一次点评的 Prompt，是面向全体学员的输出内容，
            因此写入权限收紧到教师/管理员 —— 学员若能写入，等于开了一条
            「往所有同学的 AI 点评里注入指定文本」的间接提示注入通道。
          </div>
        </div>
      </div>
    </div>

    <!-- ================= 检索（可观测性） ================= -->
    <div class="cj-card">
      <div class="cj-card__head">
        <span>检索自检</span>
        <span class="cj-dim">RAG 的问题绝大多数出在检索侧 —— 这个面板把「召回什么、相似度多少」摊开看</span>
      </div>
      <div class="cj-card__body">
        <div class="cj-row">
          <el-input
            v-model.trim="search.query"
            placeholder="输入一段检索文本，例如「动态规划 状态转移」"
            style="max-width: 420px"
            @keyup.enter="onSearch"
          />
          <el-input v-model="search.problemId" placeholder="限定题目 ID（可空）" style="width: 170px" />
          <el-input-number v-model="search.topK" :min="0" :max="50" controls-position="right" style="width: 130px" />
          <el-checkbox v-model="search.includeHistory">含历史点评</el-checkbox>
          <el-button type="primary" :loading="searching" @click="onSearch">检索</el-button>
        </div>

        <el-alert
          v-if="searchError"
          type="error"
          :closable="false"
          show-icon
          style="margin-top: 12px"
          :title="searchError"
        />

        <div v-if="hits.length" class="hits">
          <div v-for="h in hits" :key="h.id" class="hit">
            <div class="hit__head">
              <!-- 四段定宽：来源 / 标题 / 题目 / 相似度。
                   相似度用 grid 的自适应列推到最右，而不是靠 flex 的 gap 碰运气 ——
                   各行的相似度数值位数不同，必须右对齐才比得出来 -->
              <el-tag size="small" effect="plain">{{ h.sourceType || '—' }}</el-tag>
              <span class="hit__title">{{ h.title || '(无标题)' }}</span>
              <span class="cj-num-dim">题目 #{{ h.problemId ?? '通用' }}</span>
              <span class="cj-num hit__score">
                {{ h.score !== null && h.score !== undefined ? Number(h.score).toFixed(4) : '—' }}
              </span>
            </div>
            <div class="hit__body">{{ h.content }}</div>
          </div>
        </div>
        <el-empty v-else-if="searched && !searchError" description="没有召回到任何切片" :image-size="60" />
      </div>
    </div>

    <!-- ================= 入库 ================= -->
    <div class="cj-card">
      <div class="cj-card__head">
        <span>知识入库</span>
        <span class="cj-dim">切分 → 逐片向量化 → 写 pgvector</span>
      </div>
      <div class="cj-card__body">
        <el-form :model="upload" label-width="96px">
          <el-form-item label="关联题目">
            <el-input v-model="upload.problemId" placeholder="题目 ID；留空表示通用算法知识（跨题可召回）" style="max-width: 420px" />
          </el-form-item>
          <el-form-item label="来源类型">
            <el-select v-model="upload.sourceType" style="width: 240px">
              <el-option label="题面 (STATEMENT)" value="STATEMENT" />
              <el-option label="题解 (EDITORIAL)" value="EDITORIAL" />
              <el-option label="错误模式 (ERROR_PATTERN)" value="ERROR_PATTERN" />
              <el-option label="算法笔记 (TAG_NOTE)" value="TAG_NOTE" />
            </el-select>
          </el-form-item>
          <el-form-item label="标题">
            <el-input v-model.trim="upload.title" style="max-width: 420px" placeholder="应写成「浓缩主题」而非「文档名」——标题会与正文一起做向量化" />
          </el-form-item>
          <el-form-item label="正文">
            <el-input v-model="upload.content" type="textarea" :rows="8" placeholder="按 chunk-size 滑动窗口切分后逐片入库" />
          </el-form-item>
          <el-form-item label="选项">
            <el-checkbox v-model="upload.replace">先清空该题旧切片再入库（重新灌题解时应当勾选）</el-checkbox>
          </el-form-item>
          <el-form-item>
            <el-button :loading="previewing" @click="onPreview">切片预览</el-button>
            <el-button type="primary" :loading="uploading" @click="onUpload">入库</el-button>
            <el-button type="danger" plain :loading="clearing" @click="onClear">清空该题知识</el-button>
          </el-form-item>
        </el-form>

        <el-alert
          v-if="previewText"
          type="info"
          :closable="false"
          show-icon
          style="margin-top: 8px"
          :title="previewText"
        />
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Refresh } from '@element-plus/icons-vue';
import { aiApi } from '@/api';

const count = reactive({ knowledgeChunks: null, reviews: null });

const search = reactive({ query: '', problemId: '', topK: 5, includeHistory: false });
const searching = ref(false);
const searched = ref(false);
const searchError = ref('');
const hits = ref([]);

const upload = reactive({
  problemId: '',
  sourceType: 'EDITORIAL',
  title: '',
  content: '',
  replace: false,
});
const uploading = ref(false);
const previewing = ref(false);
const clearing = ref(false);
const previewText = ref('');

async function loadCount() {
  try {
    const data = await aiApi.knowledgeCount();
    count.knowledgeChunks = data?.knowledgeChunks ?? 0;
    count.reviews = data?.reviews ?? 0;
  } catch (e) {
    count.knowledgeChunks = '—';
    count.reviews = '—';
    ElMessage.error(e.message || '读取知识库规模失败（需要教师/管理员角色）');
  }
}

async function onSearch() {
  searchError.value = '';
  searched.value = true;
  if (!search.query) {
    ElMessage.warning('请输入检索文本');
    return;
  }
  searching.value = true;
  try {
    hits.value =
      (await aiApi.knowledgeSearch({
        query: search.query,
        problemId: search.problemId || null,
        topK: search.topK,
        includeHistory: search.includeHistory,
      })) || [];
  } catch (e) {
    hits.value = [];
    searchError.value = e.message || '检索失败';
  } finally {
    searching.value = false;
  }
}

function buildUploadBody() {
  return {
    problemId: upload.problemId || null,
    sourceType: upload.sourceType,
    title: upload.title,
    content: upload.content,
    replace: upload.replace,
  };
}

async function onPreview() {
  previewText.value = '';
  if (!upload.content) {
    ElMessage.warning('请先粘贴正文');
    return;
  }
  previewing.value = true;
  try {
    const data = await aiApi.knowledgePreview(buildUploadBody());
    previewText.value = `将切分为 ${data?.chunkCount ?? 0} 片（仅预览，未向量化、未入库）`;
  } catch (e) {
    ElMessage.error(e.message || '预览失败');
  } finally {
    previewing.value = false;
  }
}

async function onUpload() {
  if (!upload.content) {
    ElMessage.warning('请先粘贴正文');
    return;
  }
  uploading.value = true;
  try {
    const data = await aiApi.knowledgeUpload(buildUploadBody());
    ElMessage.success(`已入库 ${data?.chunks ?? 0} 片，当前总量 ${data?.total ?? '—'}`);
    previewText.value = '';
    await loadCount();
  } catch (e) {
    ElMessage.error(e.message || '入库失败');
  } finally {
    uploading.value = false;
  }
}

async function onClear() {
  if (!upload.problemId) {
    ElMessage.warning('清空需要指定题目 ID');
    return;
  }
  try {
    await ElMessageBox.confirm(
      `将物理删除题目 #${upload.problemId} 的全部知识切片，不可恢复。确定继续？`,
      '高危操作',
      { type: 'error', confirmButtonText: '确认删除' },
    );
  } catch {
    return;
  }
  clearing.value = true;
  try {
    const data = await aiApi.knowledgeClear(upload.problemId);
    ElMessage.success(`已清空，当前总量 ${data?.total ?? '—'}`);
    await loadCount();
  } catch (e) {
    ElMessage.error(e.message || '清空失败');
  } finally {
    clearing.value = false;
  }
}

onMounted(loadCount);
</script>

<style scoped>
/* 读数卡片用全局 .cj-stats / .cj-stat；这里只保留召回结果列表的样式 */

.hits {
  margin-top: var(--sp-3);
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
}
.hit {
  border: 1px solid var(--line-1);
  border-radius: var(--r-md);
  padding: var(--sp-3);
}
.hit__head {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  flex-wrap: wrap;
  margin-bottom: var(--sp-2);
}
.hit__title {
  font-weight: var(--fw-semi);
}
/* 相似度推到最右：position 用 margin-left:auto 而不是 flex:1 的 spacer，
   这样它在窄屏折行时不会被拉成整行宽 */
.hit__score {
  margin-left: auto;
  font-size: var(--fs-sm);
  color: var(--fg);
}
.hit__body {
  font-size: var(--fs-sm);
  color: var(--fg-2);
  line-height: var(--lh-base);
  max-height: 160px;
  overflow: auto;
  white-space: pre-wrap;
}
</style>
