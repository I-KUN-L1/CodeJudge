<template>
  <div class="cj-page">
    <el-skeleton v-if="loading" :rows="8" animated />

    <template v-else-if="detail">
      <!-- ================= 概要 ================= -->
      <div class="cj-card">
        <div class="cj-card__head">
          <div class="cj-row">
            <el-button link :icon="ArrowLeft" @click="router.back()">返回</el-button>
            <span class="cj-title">提交 #{{ detail.id }}</span>
            <VerdictTag :verdict="detail.verdict" :pending-text="statusLabel(detail.status)" />
            <el-tag v-if="detail.contestId" size="small" type="warning" effect="plain">
              竞赛 #{{ detail.contestId }}
            </el-tag>
          </div>

          <div class="cj-row">
            <el-button
              v-if="user.canManage"
              size="small"
              :icon="RefreshRight"
              :loading="rejudging"
              @click="onRejudge"
            >
              重判
            </el-button>
            <el-button size="small" :icon="Refresh" @click="load">刷新</el-button>
          </div>
        </div>

        <div class="cj-card__body">
          <el-descriptions :column="descColumns" border size="small">
            <el-descriptions-item label="题目">
              <el-link type="primary" @click="goProblem">
                #{{ detail.problemId }}
              </el-link>
            </el-descriptions-item>
            <el-descriptions-item label="语言">
              <span class="cj-mono">{{ languageLabel(detail.language) }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="得分">
              {{ detail.score ?? '—' }}
            </el-descriptions-item>
            <el-descriptions-item label="耗时">
              {{ fmtDuration(detail.timeMs) }}
            </el-descriptions-item>
            <el-descriptions-item label="内存">
              {{ fmtMemory(detail.memoryKb) }}
            </el-descriptions-item>
            <el-descriptions-item label="提交时间">
              {{ fmtTime(detail.submitTime) }}
            </el-descriptions-item>
            <el-descriptions-item label="提交人">
              <span class="cj-mono">#{{ detail.userId }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="状态">
              <el-tag size="small" :type="statusTagType(detail.status)" effect="plain">
                {{ statusLabel(detail.status) }}
              </el-tag>
            </el-descriptions-item>
          </el-descriptions>
        </div>
      </div>

      <!-- ================= 实时进度（未达终态时） ================= -->
      <div v-if="!isTerminal" class="cj-card">
        <div class="cj-card__head">
          <span>实时判题进度</span>
          <el-tag size="small" :type="progress.state.connected ? 'success' : 'info'" effect="plain">
            WS {{ progress.state.connected ? '已连接' : '未连接' }}
          </el-tag>
        </div>
        <div class="cj-card__body">
          <el-progress
            :percentage="progress.state.progress || 0"
            :stroke-width="10"
            :status="progressBarStatus"
          />
          <div class="cj-dim" style="margin-top: 10px">
            <template v-if="progress.state.totalCases">
              用例 {{ progress.state.caseSeq }} / {{ progress.state.totalCases }} ·
              通过 {{ progress.state.passedCount }} ·
            </template>
            {{ progress.state.message || '等待推送…' }}
          </div>
        </div>
      </div>

      <!-- ================= 编译信息 ================= -->
      <div v-if="detail.compileInfo" class="cj-card">
        <div class="cj-card__head">
          <span>编译结果</span>
          <el-tag size="small" :type="detail.compileInfo.success ? 'success' : 'danger'" effect="plain">
            {{ detail.compileInfo.success ? '编译通过' : '编译失败' }}
          </el-tag>
          <span class="cj-dim">耗时 {{ fmtDuration(detail.compileInfo.durationMs) }}</span>
        </div>
        <div class="cj-card__body">
          <template v-if="detail.compileInfo.stderrLog">
            <div class="sec">stderr</div>
            <pre class="log">{{ detail.compileInfo.stderrLog }}</pre>
          </template>
          <template v-if="detail.compileInfo.stdoutLog">
            <div class="sec">stdout</div>
            <pre class="log">{{ detail.compileInfo.stdoutLog }}</pre>
          </template>
        </div>
      </div>

      <!-- ================= 逐用例结果 ================= -->
      <div class="cj-card">
        <div class="cj-card__head">
          <span>用例结果</span>
          <span class="cj-dim">
            共 {{ detail.caseResults?.length || 0 }} 条
            <template v-if="user.isStudent">（隐藏用例不返回输出摘要）</template>
          </span>
        </div>
        <div class="cj-card__body">
          <div class="cj-scroll-x">
            <el-table
              :data="detail.caseResults || []"
              size="small"
              stripe
              empty-text="暂无用例结果（判题进行中或已短路）"
            >
              <el-table-column label="用例" width="80" align="center">
                <template #default="{ row }">
                  <span class="cj-mono">#{{ row.seq }}</span>
                </template>
              </el-table-column>
              <el-table-column label="结论" width="120">
                <template #default="{ row }">
                  <VerdictTag :verdict="row.verdict" :dot="false" />
                </template>
              </el-table-column>
              <el-table-column label="耗时" width="100">
                <template #default="{ row }">
                  <span class="cj-mono cj-dim">{{ fmtDuration(row.timeMs) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="内存" width="110">
                <template #default="{ row }">
                  <span class="cj-mono cj-dim">{{ fmtMemory(row.memoryKb) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="可见性" width="100" class-name="cj-hide-sm">
                <template #default="{ row }">
                  <!-- 只有教师/管理员视角才会区分隐藏用例；学员视角该字段不参与展示 -->
                  <el-tag v-if="row.hidden" size="small" type="warning" effect="plain">隐藏</el-tag>
                  <el-tag v-else size="small" type="info" effect="plain">样例</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="输出摘要">
                <template #default="{ row }">
                  <span v-if="row.outputDigest" class="cj-mono digest">{{ row.outputDigest }}</span>
                  <span v-else class="cj-dim">—</span>
                </template>
              </el-table-column>
              <el-table-column label="错误摘要">
                <template #default="{ row }">
                  <span v-if="row.stderrDigest" class="cj-mono digest">{{ row.stderrDigest }}</span>
                  <span v-else class="cj-dim">—</span>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </div>
      </div>

      <!-- ================= AI 点评 ================= -->
      <div class="cj-card">
        <div class="cj-card__head">
          <span>AI 代码点评</span>
          <span class="cj-dim">流式返回（SSE）· RAG 检索题目知识与历史点评</span>
        </div>
        <div class="cj-card__body">
          <AiReviewPanel :submission-id="detail.id" />
        </div>
      </div>
    </template>

    <el-empty v-else description="提交不存在或无权查看" />
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { ArrowLeft, Refresh, RefreshRight } from '@element-plus/icons-vue';
import VerdictTag from '@/components/VerdictTag.vue';
import AiReviewPanel from '@/components/AiReviewPanel.vue';
import { submissionApi } from '@/api';
import { useUserStore } from '@/stores/user';
import { useSubmissionProgress } from '@/composables/useSubmissionProgress';
import {
  fmtDuration,
  fmtMemory,
  fmtTime,
  isTerminalStatus,
  languageLabel,
  statusLabel,
  statusTagType,
} from '@/utils/format';

const route = useRoute();
const router = useRouter();
const user = useUserStore();
const progress = useSubmissionProgress();

const loading = ref(false);
const rejudging = ref(false);
const detail = ref(null);

const submissionId = computed(() => route.params.id);
const isTerminal = computed(() => isTerminalStatus(detail.value?.status));

/** 窄屏下描述列表降到 1 列，否则标签与值会互相挤压 */
const descColumns = computed(() => (window.innerWidth < 720 ? 1 : 4));

const progressBarStatus = computed(() => {
  const v = progress.state.verdict;
  if (!v) return undefined;
  return v === 'AC' ? 'success' : 'exception';
});

async function load() {
  loading.value = true;
  try {
    detail.value = await submissionApi.detail(submissionId.value);
    // 未达终态才订阅进度；已判完的提交订阅只会白占一个服务端会话位
    if (!isTerminalStatus(detail.value?.status)) {
      progress.subscribe(detail.value.id, user.accessToken, {
        onTerminal: () => load(),
      });
    }
  } catch (e) {
    detail.value = null;
    ElMessage.error(e.message || '加载提交详情失败');
  } finally {
    loading.value = false;
  }
}

async function onRejudge() {
  rejudging.value = true;
  try {
    await submissionApi.rejudge(submissionId.value);
    ElMessage.success('已重新投递判题');
    await load();
  } catch (e) {
    ElMessage.error(e.message || '重判失败');
  } finally {
    rejudging.value = false;
  }
}

function goProblem() {
  router.push({ name: 'problem-detail', params: { id: detail.value.problemId } });
}

onMounted(load);
</script>

<style scoped>
.sec {
  margin: 12px 0 6px;
  font-size: 13px;
  color: var(--cj-text-sub);
  font-weight: 600;
}
.sec:first-child {
  margin-top: 0;
}

.log {
  margin: 0;
  max-height: 280px;
  overflow: auto;
  font-size: 12.5px;
  line-height: 1.55;
}

.digest {
  font-size: 12px;
  color: var(--cj-text-sub);
  word-break: break-all;
}
</style>
