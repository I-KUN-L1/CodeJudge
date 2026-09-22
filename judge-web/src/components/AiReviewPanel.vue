<template>
  <div class="ai">
    <!-- 工具栏 -->
    <div class="ai__bar">
      <el-select v-model="reviewType" size="small" style="width: 168px" :disabled="running">
        <el-option label="错误诊断" :value="1" />
        <el-option label="主动点评" :value="2" />
        <el-option label="相似题推荐" :value="3" />
      </el-select>

      <el-button v-if="!running" type="primary" size="small" :icon="MagicStick" @click="start">
        生成 AI 点评
      </el-button>
      <el-button v-else type="danger" size="small" plain @click="abort">中断</el-button>

      <el-tag v-if="degraded" size="small" type="warning" effect="dark">降级模式（非 AI 生成）</el-tag>
      <el-tag v-if="model" size="small" effect="plain" type="info">{{ model }}</el-tag>

      <div class="cj-spacer" />
      <span class="cj-dim">{{ statusText }}</span>
    </div>

    <!-- 追问框：生成中禁用（服务端一次只处理一条流） -->
    <div class="ai__ask">
      <el-input
        v-model.trim="question"
        size="small"
        placeholder="可以追问，例如「边界情况怎么处理？」（首轮留空即可）"
        :disabled="running"
        @keyup.enter="start"
      >
        <template #append>
          <el-button :disabled="running" @click="start">追问</el-button>
        </template>
      </el-input>
    </div>

    <!-- RAG 依据：RETRIEVAL 事件**先于正文**推送，因此可在生成期间就展示"依据了什么" -->
    <div v-if="sources.length" class="ai__sources">
      <div class="ai__sources-title">
        <el-icon><Link /></el-icon>
        参考依据（{{ sources.length }}）—— 检索完成于正文之前
      </div>
      <div class="ai__source-list">
        <el-tag
          v-for="(s, i) in sources"
          :key="i"
          size="small"
          effect="plain"
          class="ai__source"
          :title="s.title"
        >
          {{ s.sourceType || '知识' }}：{{ s.title || '(无标题)' }}
          <span v-if="s.score !== undefined && s.score !== null" class="cj-dim">
            · {{ Number(s.score).toFixed(3) }}
          </span>
        </el-tag>
      </div>
    </div>

    <!-- 正文（流式增量渲染） -->
    <div v-if="content || running" class="ai__body">
      <MarkdownView :text="content" />
      <!-- 打字机光标：纯 UI 细节，但对"流式感"的感知影响很大 -->
      <span v-if="running" class="ai__caret" />
    </div>

    <el-alert
      v-if="error"
      type="error"
      :closable="false"
      show-icon
      style="margin-top: 10px"
      :title="error"
    />

    <el-empty v-if="!content && !running && !error" description="还没有点评，点上方按钮生成" :image-size="60" />

    <!-- 历史点评 -->
    <div v-if="history.length" class="ai__history">
      <el-divider content-position="left">历史点评（{{ history.length }}）</el-divider>
      <div v-for="h in history" :key="h.id" class="ai__hist-item">
        <div class="ai__hist-head">
          <el-tag size="small" effect="plain">{{ h.reviewTypeLabel || h.reviewType }}</el-tag>
          <span class="cj-dim">{{ fmtTime(h.createTime) }}</span>
          <el-tag v-if="h.degraded" size="small" type="warning" effect="plain">降级</el-tag>
          <el-tag v-if="h.status === 2" size="small" type="danger" effect="plain">失败</el-tag>
          <div class="cj-spacer" />
          <el-link type="primary" @click="toggleHistory(h.id)">
            {{ expanded.has(h.id) ? '收起' : '展开' }}
          </el-link>
        </div>
        <div v-if="expanded.has(h.id)" class="ai__hist-body">
          <MarkdownView :text="h.content || h.errorMsg || '(无内容)'" />
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { Link, MagicStick } from '@element-plus/icons-vue';
import MarkdownView from '@/components/MarkdownView.vue';
import { aiApi } from '@/api';
import { streamReview } from '@/utils/sse';
import { useUserStore } from '@/stores/user';
import { fmtTime } from '@/utils/format';

/**
 * AI 点评面板
 *
 * 契约要点全部来自 docs/P5-前端SSE接入说明.md：
 *   1. **不能用 EventSource**：无法带 Authorization 头、无法 POST 长追问、
 *      无法自控 Last-Event-ID。故用 fetch + ReadableStream（实现在 utils/sse.js）。
 *   2. **两类错误要分流**：订阅前失败（未登录）= JSON + code 401；
 *      订阅后失败（越权/上游不可用）= text/event-stream + ERROR 事件，
 *      **两种情况 HTTP 状态码都是 200** —— 判据是 Content-Type。
 *   3. **增量渲染用"全量重渲染"而非追加**：Markdown 是上下文相关的
 *      （`## 标题` 只有独占一行才成立），边收边追加 DOM 会产出空标题等碎裂结构。
 *      正文通常 1–3KB，O(n²) 的重渲染代价在现代浏览器上无感。
 */

const props = defineProps({
  submissionId: { type: [Number, String], required: true },
});

const user = useUserStore();

const reviewType = ref(1);
const question = ref('');
const running = ref(false);
const content = ref('');
const sources = ref([]);
const degraded = ref(false);
const model = ref('');
const error = ref('');
const history = ref([]);
const expanded = ref(new Set());

let task = null;

const statusText = computed(() => {
  if (running.value && !content.value) return '检索中…（首 token 可能等 5–20 秒）';
  if (running.value) return '生成中…';
  return content.value ? '生成完成' : '';
});

function start() {
  if (running.value) return;
  error.value = '';
  content.value = '';
  sources.value = [];
  degraded.value = false;
  model.value = '';
  running.value = true;

  task = streamReview({
    token: user.accessToken,
    submissionId: Number(props.submissionId),
    reviewType: reviewType.value,
    question: question.value || null,

    onStart: (e) => {
      // 每次 START 都要清空正文（utils/sse.js 内部也会清），这里是第二道保险
      content.value = '';
      degraded.value = !!e.degraded;
      model.value = e.model || '';
      if (e.degraded) {
        ElMessage.warning('当前为降级模式：LLM 未配置，返回的是结构化模板内容，不是 AI 生成');
      }
    },

    onRetrieval: (e) => {
      sources.value = e.sources || [];
    },

    onDelta: (_e, full) => {
      // second 参数是累积正文（utils/sse.js 已累加），直接整体替换触发重渲染
      content.value = full;
    },

    onError: (e) => {
      // 业务错误（HTTP 仍是 200），区分"用户可行动"与"稍后重试"给不同措辞
      const code = e.code;
      const prefix =
        code === 403
          ? '无权点评该提交（学员只能点评自己的提交）'
          : code === 404
            ? '找不到该提交的判题信息'
            : code === 429
              ? '当前点评人数较多，请稍后再试'
              : '点评失败';
      error.value = `${prefix}：${e.content || ''}`;
    },

    onEnd: (e) => {
      running.value = false;
      const reason = e.finishReason;
      if (reason === 'DEGRADED') degraded.value = true;
      if (reason === 'ERROR') error.value = error.value || '生成中途失败，正文可能不完整';
      if (reason === 'BUSY') error.value = '服务端并发连接数已满，请稍后重试';
      if (reason === 'REPLAYED_EXPIRED') error.value = '重连回放缓冲已过期，请重新发起点评';
      question.value = '';
      loadHistory();
    },

    onHttpError: (e) => {
      // 订阅前失败：Content-Type 不是 text/event-stream
      running.value = false;
      if (e.status === 401) {
        error.value = '登录已过期，请重新登录后再试';
      } else {
        error.value = `请求被拒绝（${e.status || e.code}）：${e.message || ''}`;
      }
    },
  });

  task.done?.finally(() => {
    running.value = false;
  });
}

function abort() {
  task?.abort();
  running.value = false;
  ElMessage.info('已中断，服务端会落库已生成的部分');
  loadHistory();
}

async function loadHistory() {
  try {
    history.value = (await aiApi.history(Number(props.submissionId), 10)) || [];
  } catch {
    // 历史查询失败不打扰用户（可能是权限或服务未启动）
    history.value = [];
  }
}

function toggleHistory(id) {
  const s = new Set(expanded.value);
  if (s.has(id)) s.delete(id);
  else s.add(id);
  expanded.value = s;
}

onMounted(loadHistory);
onBeforeUnmount(() => task?.abort());
</script>

<style scoped>
.ai__bar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.ai__ask {
  margin-top: 10px;
}

.ai__sources {
  margin-top: 12px;
  padding: 10px 12px;
  background: var(--cj-panel-2);
  border: 1px solid var(--cj-border);
  border-radius: 8px;
}
.ai__sources-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12.5px;
  color: var(--cj-text-sub);
  margin-bottom: 8px;
}
.ai__source-list {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.ai__source {
  max-width: 100%;
}

.ai__body {
  margin-top: 14px;
  padding: 14px 16px;
  background: var(--cj-panel-2);
  border: 1px solid var(--cj-border);
  border-radius: 10px;
  min-height: 60px;
}

.ai__caret {
  display: inline-block;
  width: 8px;
  height: 15px;
  margin-left: 2px;
  vertical-align: -2px;
  background: var(--cj-accent);
  animation: cj-blink 1s steps(2, start) infinite;
}
@keyframes cj-blink {
  to {
    visibility: hidden;
  }
}

.ai__history {
  margin-top: 8px;
}
.ai__hist-item {
  border: 1px solid var(--cj-border);
  border-radius: 8px;
  padding: 10px 12px;
  margin-bottom: 8px;
}
.ai__hist-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.ai__hist-body {
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px dashed var(--cj-border);
  max-height: 340px;
  overflow: auto;
}
</style>
