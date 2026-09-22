<template>
  <div class="cj-page">
    <el-skeleton v-if="loading" :rows="8" animated />

    <template v-else-if="problem">
      <!-- ============ 头部信息 ============ -->
      <div class="cj-card">
        <div class="cj-card__head">
          <div class="cj-row">
            <el-button link :icon="ArrowLeft" @click="router.back()">返回</el-button>
            <span class="cj-title">{{ problem.title }}</span>
            <el-tag size="small" :type="difficultyTagType(problem.difficulty)">
              {{ difficultyLabel(problem.difficulty) }}
            </el-tag>
            <el-tag v-if="problem.status !== 1" size="small" type="warning" effect="plain">
              {{ problemStatusLabel(problem.status) }}
            </el-tag>
          </div>
          <div class="cj-row cj-dim">
            <span class="cj-mono">#{{ problem.id }}</span>
            <span>时限 {{ problem.timeLimitMs }} ms</span>
            <span>内存 {{ problem.memoryLimitMb }} MB</span>
            <span>通过率 {{ fmtPercent(problem.acceptedRate) }}</span>
          </div>
        </div>

        <div v-if="problem.tags?.length" class="cj-card__body" style="padding: 10px 16px">
          <el-tag v-for="t in problem.tags" :key="t.id" size="small" effect="plain" type="info" style="margin-right: 6px">
            {{ t.name }}
          </el-tag>
        </div>
      </div>

      <!-- ============ 主体：题面 / 编辑器 ============ -->
      <div class="work">
        <!-- 题面 -->
        <div class="work__left">
          <div class="cj-card">
            <div class="cj-card__head">题目描述</div>
            <div class="cj-card__body">
              <MarkdownView :text="problem.statement" />
            </div>
          </div>

          <div v-if="problem.inputSpec || problem.outputSpec" class="cj-card">
            <div class="cj-card__head">输入 / 输出</div>
            <div class="cj-card__body">
              <h4 class="sec">输入说明</h4>
              <MarkdownView :text="problem.inputSpec" />
              <h4 class="sec">输出说明</h4>
              <MarkdownView :text="problem.outputSpec" />
            </div>
          </div>

          <!-- 样例：只有 isHidden=0 的用例会下发到这里（后端按角色过滤） -->
          <div v-if="problem.samples?.length" class="cj-card">
            <div class="cj-card__head">
              样例
              <span class="cj-dim">共 {{ problem.samples.length }} 组</span>
            </div>
            <div class="cj-card__body">
              <div v-for="(s, i) in problem.samples" :key="s.id || i" class="sample">
                <div class="sample__head">
                  <span>样例 {{ i + 1 }}</span>
                  <span v-if="s.score" class="cj-dim">分值 {{ s.score }}</span>
                </div>
                <div class="sample__grid">
                  <div>
                    <div class="sample__label">输入</div>
                    <pre class="sample__pre">{{ s.stdin || '(空)' }}</pre>
                  </div>
                  <div>
                    <div class="sample__label">期望输出</div>
                    <pre class="sample__pre">{{ s.expectedStdout || '(空)' }}</pre>
                  </div>
                </div>
              </div>
            </div>
          </div>

          <!-- 提示 -->
          <div v-if="problem.hint" class="cj-card">
            <div class="cj-card__head">提示</div>
            <div class="cj-card__body">
              <MarkdownView :text="problem.hint" />
            </div>
          </div>
        </div>

        <!-- 编辑器 -->
        <div class="work__right">
          <div class="editor-card cj-card">
            <div class="cj-card__head">
              <span>代码编辑</span>
              <div class="cj-row">
                <el-select v-model="language" size="small" style="width: 150px">
                  <el-option v-for="l in LANGUAGES" :key="l.value" :label="l.label" :value="l.value" />
                </el-select>
                <el-tooltip content="恢复为题目模板代码" placement="top">
                  <el-button size="small" :icon="RefreshLeft" @click="resetToTemplate" />
                </el-tooltip>
              </div>
            </div>

            <div class="cj-card__body" style="padding: 12px">
              <CodeEditor v-model="code" :language="language" :height="360" />

              <div class="submit-row">
                <span class="cj-dim">
                  <template v-if="contestId">竞赛提交（contestId={{ contestId }}）</template>
                  <template v-else>普通提交</template>
                  · {{ code.length }} 字符
                </span>
                <div class="cj-spacer" />
                <el-button
                  type="primary"
                  :loading="submitting"
                  :icon="Promotion"
                  @click="onSubmit"
                >
                  提交判题
                </el-button>
              </div>
            </div>
          </div>

          <!-- 判题进度（提交后出现） -->
          <div v-if="currentSubmissionId" class="cj-card">
            <div class="cj-card__head">
              <span>判题进度</span>
              <el-tag
                size="small"
                :type="progress.state.connected ? 'success' : 'info'"
                effect="plain"
              >
                {{ progress.state.connected ? 'WS 已连接' : 'WS 未连接' }}
              </el-tag>
            </div>
            <div class="cj-card__body">
              <div class="prog-line">
                <span>提交</span>
                <el-link type="primary" class="cj-mono" @click="openSubmission(currentSubmissionId)">
                  #{{ currentSubmissionId }}
                </el-link>
                <div class="cj-spacer" />
                <VerdictTag
                  :verdict="progress.state.verdict"
                  :pending-text="statusLabel(progress.state.status || 'PENDING')"
                />
              </div>

              <el-progress
                :percentage="progress.state.progress || 0"
                :status="progressBarStatus"
                :stroke-width="10"
                style="margin: 12px 0"
              />

              <div class="cj-dim prog-msg">
                <template v-if="progress.state.totalCases">
                  用例 {{ progress.state.caseSeq }} / {{ progress.state.totalCases }} ·
                  通过 {{ progress.state.passedCount }}
                </template>
                {{ progress.state.message }}
              </div>

              <el-alert
                v-if="progress.state.error"
                type="warning"
                :closable="false"
                show-icon
                style="margin-top: 10px"
                :title="progress.state.error"
              />

              <div class="cj-row" style="margin-top: 12px">
                <el-button size="small" @click="openSubmission(currentSubmissionId)">
                  查看判题详情
                </el-button>
                <el-button size="small" link type="primary" @click="refreshSubmission">
                  刷新状态
                </el-button>
              </div>
            </div>
          </div>

          <!-- 我的最近提交 -->
          <div class="cj-card">
            <div class="cj-card__head">
              <span>我的最近提交</span>
              <el-link type="primary" @click="router.push('/submissions')">全部 →</el-link>
            </div>
            <div class="cj-card__body" style="padding: 8px 12px">
              <el-table :data="mySubmissions" size="small" :show-header="false" empty-text="暂无提交">
                <el-table-column width="94">
                  <template #default="{ row }">
                    <VerdictTag
                      :verdict="row.verdict"
                      :pending-text="statusLabel(row.status)"
                      :dot="false"
                    />
                  </template>
                </el-table-column>
                <el-table-column>
                  <template #default="{ row }">
                    <span class="cj-dim cj-mono">{{ languageLabel(row.language) }}</span>
                  </template>
                </el-table-column>
                <el-table-column width="120">
                  <template #default="{ row }">
                    <span class="cj-dim">{{ fmtFromNow(row.submitTime) }}</span>
                  </template>
                </el-table-column>
                <el-table-column width="52" align="right">
                  <template #default="{ row }">
                    <el-link type="primary" @click="openSubmission(row.id)">→</el-link>
                  </template>
                </el-table-column>
              </el-table>
            </div>
          </div>
        </div>
      </div>
    </template>

    <el-empty v-else description="题目不存在或已被删除" />
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { ArrowLeft, Promotion, RefreshLeft } from '@element-plus/icons-vue';
import CodeEditor from '@/components/CodeEditor.vue';
import MarkdownView from '@/components/MarkdownView.vue';
import VerdictTag from '@/components/VerdictTag.vue';
import { problemApi, submissionApi } from '@/api';
import { useUserStore } from '@/stores/user';
import { useSubmissionProgress } from '@/composables/useSubmissionProgress';
import {
  LANGUAGES,
  difficultyLabel,
  difficultyTagType,
  fmtFromNow,
  fmtPercent,
  languageLabel,
  problemStatusLabel,
  statusLabel,
} from '@/utils/format';

defineOptions({ name: 'ProblemDetailView' });

const route = useRoute();
const router = useRouter();
const user = useUserStore();
const progress = useSubmissionProgress();

const loading = ref(false);
const submitting = ref(false);
const problem = ref(null);
const language = ref('JAVA');
const code = ref('');
const mySubmissions = ref([]);
const currentSubmissionId = ref(null);

/** 从竞赛页跳转过来时带上 contestId，提交会记为竞赛提交 */
const contestId = computed(() => {
  const v = route.query.contestId;
  return v ? Number(v) : null;
});

/**
 * 内置兜底模板。
 * 题目模板代码存在 problem_version.template_code（JSON 列），键名是**小写**
 * （`java` / `cpp` / `python` / `go`，已核对种子数据），而提交接口要的是**大写**枚举名。
 * 这里用 language.toLowerCase() 取值；缺失时用下面的兜底，保证编辑器永远不是空白。
 */
const FALLBACK_TEMPLATE = {
  JAVA: `import java.util.*;\n\npublic class Main {\n    public static void main(String[] args) {\n        Scanner sc = new Scanner(System.in);\n        // TODO: 在此实现你的解法\n    }\n}\n`,
  CPP: `#include <iostream>\nusing namespace std;\n\nint main() {\n    // TODO: 在此实现你的解法\n    return 0;\n}\n`,
  PYTHON: `import sys\n\ndef main():\n    # TODO: 在此实现你的解法\n    pass\n\nif __name__ == "__main__":\n    main()\n`,
  GO: `package main\n\nimport "fmt"\n\nfunc main() {\n\t// TODO: 在此实现你的解法\n\t_ = fmt.Sprint\n}\n`,
};

function templateFor(lang) {
  const fromProblem = problem.value?.templateCode?.[String(lang).toLowerCase()];
  return fromProblem || FALLBACK_TEMPLATE[lang] || '';
}

const progressBarStatus = computed(() => {
  const v = progress.state.verdict;
  if (!v) return undefined;
  if (v === 'AC') return 'success';
  return 'exception';
});

async function loadProblem() {
  loading.value = true;
  try {
    problem.value = await problemApi.detail(route.params.id);
    resetToTemplate();
  } catch (e) {
    problem.value = null;
    ElMessage.error(e.message || '题目加载失败');
  } finally {
    loading.value = false;
  }
}

function resetToTemplate() {
  code.value = templateFor(language.value);
}

async function loadMySubmissions() {
  try {
    const page = await submissionApi.page({ problemId: route.params.id, pageNo: 1, pageSize: 5 });
    mySubmissions.value = page?.list || [];
  } catch {
    mySubmissions.value = [];
  }
}

async function onSubmit() {
  if (!code.value.trim()) {
    ElMessage.warning('代码不能为空');
    return;
  }
  submitting.value = true;
  try {
    const body = {
      problemId: Number(route.params.id),
      language: language.value,
      code: code.value,
    };
    if (contestId.value) body.contestId = contestId.value;
    // contestId 为普通提交时留空（后端 null 视为普通提交）

    // 同一份代码 60 秒内重复提交会命中幂等，后端返回已有记录并带 idempotent=true
    const vo = await submissionApi.submit(body);
    currentSubmissionId.value = vo.id;

    if (vo.idempotent) {
      ElMessage.info('检测到重复提交，已返回 60 秒内的那次记录（幂等）');
    } else {
      ElMessage.success('提交成功，正在判题…');
    }

    // 订阅进度：连接建立的瞬间服务端会推 SNAPSHOT，能覆盖"判题太快、错过进度"的情况
    progress.subscribe(vo.id, user.accessToken, {
      onTerminal: () => {
        loadMySubmissions();
        reloadProblemStats();
      },
    });
    loadMySubmissions();
  } catch (e) {
    ElMessage.error(e.message || '提交失败');
  } finally {
    submitting.value = false;
  }
}

/** 判完题后通过率会变，重新拉一次题目头部统计 */
async function reloadProblemStats() {
  try {
    const fresh = await problemApi.detail(route.params.id);
    if (fresh && problem.value) {
      problem.value = { ...problem.value, ...fresh };
    }
  } catch {
    /* 统计刷新失败不影响主流程 */
  }
}

async function refreshSubmission() {
  if (!currentSubmissionId.value) return;
  try {
    const d = await submissionApi.detail(currentSubmissionId.value);
    Object.assign(progress.state, {
      status: d.status,
      verdict: d.verdict || '',
      terminal: d.status === 'SUCCESS' || d.status === 'FAILED',
    });
    if (progress.state.terminal) progress.state.progress = 100;
  } catch (e) {
    ElMessage.error(e.message || '刷新失败');
  }
}

function openSubmission(id) {
  router.push({ name: 'submission-detail', params: { id } });
}

// 切换语言时若当前代码仍是"上一个语言的模板"（用户没改过），就跟着换模板；
// 若用户已经写了代码，则询问后再覆盖 —— 静默清空用户代码是很恶劣的体验
watch(language, (nv, ov) => {
  const wasPristine = code.value === templateFor(ov);
  if (wasPristine) {
    resetToTemplate();
    return;
  }
  ElMessageBox.confirm('切换语言会替换当前编辑器内容，确定继续吗？', '切换语言', {
    confirmButtonText: '替换',
    cancelButtonText: '保留',
    type: 'warning',
  })
    .then(() => resetToTemplate())
    .catch(() => {
      // 用户选择保留：把下拉框选回原语言，避免"语言与代码不匹配"的隐性错误
      language.value = ov;
    });
});

onMounted(() => {
  loadProblem();
  loadMySubmissions();
});

onBeforeUnmount(() => progress.close());
</script>

<style scoped>
.work {
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(0, 1fr);
  gap: var(--cj-gap);
  margin-top: var(--cj-gap);
  align-items: start;
}

.work__left,
.work__right {
  display: flex;
  flex-direction: column;
  gap: var(--cj-gap);
  min-width: 0;
}

/* 编辑器面板在桌面端吸顶，长题面滚动时仍能操作 */
.work__right {
  position: sticky;
  top: calc(var(--cj-header-h) + 12px);
}

.sec {
  margin: 14px 0 6px;
  font-size: 13px;
  color: var(--cj-text-sub);
  font-weight: 600;
}
.sec:first-child {
  margin-top: 0;
}

.sample + .sample {
  margin-top: 14px;
}
.sample__head {
  display: flex;
  justify-content: space-between;
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 6px;
}
.sample__grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}
.sample__label {
  font-size: 12px;
  color: var(--cj-text-dim);
  margin-bottom: 4px;
}
.sample__pre {
  margin: 0;
  max-height: 180px;
  overflow: auto;
  font-size: 12.5px;
  line-height: 1.5;
}

.submit-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 12px;
  flex-wrap: wrap;
}

.prog-line {
  display: flex;
  align-items: center;
  gap: 8px;
}
.prog-msg {
  line-height: 1.7;
}

@media (max-width: 1100px) {
  .work {
    grid-template-columns: 1fr;
  }
  .work__right {
    position: static;
  }
}

@media (max-width: 640px) {
  .sample__grid {
    grid-template-columns: 1fr;
  }
}
</style>
