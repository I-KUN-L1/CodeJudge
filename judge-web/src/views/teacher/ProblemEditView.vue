<template>
  <div class="cj-page">
    <div class="cj-card">
      <div class="cj-card__head">
        <div class="cj-row">
          <el-button link :icon="ArrowLeft" @click="router.back()">返回</el-button>
          <span class="cj-title">{{ isEdit ? '编辑题目' : '新建题目' }}</span>
          <el-tag v-if="isEdit" size="small" effect="plain" class="cj-mono">#{{ problemId }}</el-tag>
        </div>
        <div class="cj-row">
          <el-button :loading="saving" type="primary" :icon="Check" @click="onSave">保存</el-button>
        </div>
      </div>

      <div class="cj-card__body">
        <el-alert
          v-if="!isEdit"
          type="info"
          :closable="false"
          show-icon
          style="margin-bottom: 14px"
          title="新建题目默认保存为「草稿」，对学员不可见。保存后会自动跳到编辑页，在那儿补充测试用例（没有用例无法判题）。"
        />

        <el-tabs v-model="tab">
          <!-- ==================== 基本信息 ==================== -->
          <el-tab-pane label="基本信息" name="basic">
            <el-form ref="formRef" :model="form" :rules="rules" label-width="96px" style="max-width: 720px">
              <el-form-item label="标题" prop="title">
                <el-input v-model.trim="form.title" maxlength="120" show-word-limit placeholder="题目标题" />
              </el-form-item>

              <el-form-item label="难度">
                <el-rate v-model="form.difficulty" :max="5" show-text :texts="['入门', '简单', '中等', '困难', '地狱']" />
              </el-form-item>

              <el-form-item label="时间限制">
                <el-input-number v-model="form.timeLimitMs" :min="100" :max="20000" :step="100" />
                <span class="cj-dim" style="margin-left: 8px">毫秒（默认 1000）</span>
              </el-form-item>

              <el-form-item label="内存限制">
                <el-input-number v-model="form.memoryLimitMb" :min="16" :max="1024" :step="16" />
                <span class="cj-dim" style="margin-left: 8px">MB（默认 256）</span>
              </el-form-item>

              <el-form-item label="状态">
                <el-radio-group v-model="form.status">
                  <el-radio :value="0">草稿</el-radio>
                  <el-radio :value="1">已发布</el-radio>
                  <el-radio :value="2">已下线</el-radio>
                </el-radio-group>
              </el-form-item>

              <el-form-item label="标签">
                <!-- 全量覆盖语义：不选 = 清空标签；要"不改动"得走别的入口。
                     这里显式提示，避免"我没动标签结果标签没了" -->
                <el-select
                  v-model="form.tagIds"
                  multiple
                  filterable
                  placeholder="选择标签（保存时会全量覆盖；留空即清空）"
                  style="width: 100%"
                >
                  <el-option v-for="t in tags" :key="t.id" :label="`${t.name} (${t.type})`" :value="t.id" />
                </el-select>
              </el-form-item>
            </el-form>
          </el-tab-pane>

          <!-- ==================== 题面 ==================== -->
          <el-tab-pane label="题面" name="statement">
            <el-form label-position="top">
              <el-form-item label="题目描述（Markdown）">
                <el-input v-model="form.statement" type="textarea" :rows="10" placeholder="支持 Markdown（标题/列表/表格/代码块）" />
              </el-form-item>
              <el-form-item label="输入说明">
                <el-input v-model="form.inputSpec" type="textarea" :rows="4" />
              </el-form-item>
              <el-form-item label="输出说明">
                <el-input v-model="form.outputSpec" type="textarea" :rows="4" />
              </el-form-item>
              <el-form-item label="提示 / 样例说明">
                <el-input v-model="form.hint" type="textarea" :rows="4" />
              </el-form-item>
            </el-form>
          </el-tab-pane>

          <!-- ==================== 模板代码 ==================== -->
          <el-tab-pane label="模板代码" name="template">
            <p class="cj-sub" style="margin-top: 0">
              模板会随题目详情下发，学员打开题面时编辑器预填对应语言模板。
              注意：存储键名是**小写**（<code>java</code>/<code>cpp</code>/<code>python</code>/<code>go</code>），
              这是题库既定的数据口径，不要改成大写。
            </p>
            <el-tabs v-model="tplLang" type="border-card">
              <el-tab-pane v-for="l in LANGUAGES" :key="l.value" :label="l.label" :name="l.value">
                <CodeEditor
                  :model-value="form.templateCode[l.value.toLowerCase()] || ''"
                  :language="l.value"
                  :height="300"
                  @update:model-value="(v) => (form.templateCode[l.value.toLowerCase()] = v)"
                />
              </el-tab-pane>
            </el-tabs>
          </el-tab-pane>

          <!-- ==================== 测试用例（仅编辑态） ==================== -->
          <el-tab-pane v-if="isEdit" label="测试用例" name="cases">
            <div class="case-tools">
              <span class="cj-dim">
                共 {{ cases.length }} 条 · 隐藏 {{ hiddenCount }} 条 · 保存走**全量替换**（先清空再写入）
              </span>
              <div class="cj-spacer" />
              <el-button size="small" type="primary" :icon="Plus" @click="addEmptyCase">新增用例</el-button>
              <el-button size="small" :loading="savingCases" @click="saveCases">保存用例</el-button>
            </div>

            <el-alert
              v-if="caseError"
              type="error"
              :closable="false"
              show-icon
              style="margin-bottom: 10px"
              :title="caseError"
            />

            <el-empty v-if="!cases.length" description="还没有用例。判题需要至少 1 条用例（样例或隐藏均可）" :image-size="70" />

            <div v-for="(c, i) in cases" :key="i" class="case-item">
              <div class="case-item__head">
                <el-input-number v-model="c.seq" :min="1" size="small" controls-position="right" style="width: 96px" />
                <el-checkbox v-model="c.isHidden" :true-value="1" :false-value="0">隐藏</el-checkbox>
                <span class="cj-dim">分值</span>
                <el-input-number v-model="c.score" :min="0" size="small" controls-position="right" style="width: 104px" />
                <span class="cj-dim">比对</span>
                <el-select v-model="c.judgeMode" size="small" style="width: 108px">
                  <el-option label="精确" :value="0" />
                  <el-option label="浮点容差" :value="1" />
                  <el-option label="特判" :value="2" />
                </el-select>
                <div class="cj-spacer" />
                <el-button link type="danger" @click="cases.splice(i, 1)">移除</el-button>
              </div>
              <div class="case-grid">
                <div>
                  <div class="case-label">标准输入</div>
                  <el-input v-model="c.stdin" type="textarea" :rows="3" />
                </div>
                <div>
                  <div class="case-label">期望输出</div>
                  <el-input v-model="c.expectedStdout" type="textarea" :rows="3" />
                </div>
              </div>
            </div>
          </el-tab-pane>
        </el-tabs>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { ArrowLeft, Check, Plus } from '@element-plus/icons-vue';
import CodeEditor from '@/components/CodeEditor.vue';
import { problemApi } from '@/api';
import { LANGUAGES } from '@/utils/format';

const route = useRoute();
const router = useRouter();

const isEdit = computed(() => route.name === 'teacher-problem-edit');
// 雪花 id 一律按字符串透传（Number 对 19 位 id 会精度截断，update 请求会打到不存在的题目）
const problemId = computed(() => (isEdit.value ? route.params.id : null));

const tab = ref('basic');
const tplLang = ref('JAVA');
const formRef = ref(null);
const saving = ref(false);
const tags = ref([]);

const form = reactive({
  title: '',
  difficulty: 1,
  timeLimitMs: 1000,
  memoryLimitMb: 256,
  status: 0,
  tagIds: [],
  statement: '',
  inputSpec: '',
  outputSpec: '',
  hint: '',
  // 键名小写，与 problem_version.template_code 的实际存储口径一致
  templateCode: { java: '', cpp: '', python: '', go: '' },
});

const rules = {
  title: [{ required: true, message: '请输入题目标题', trigger: 'blur' }],
};

/* ------------------------------ 用例 ------------------------------ */
const cases = ref([]);
const savingCases = ref(false);
const caseError = ref('');
const hiddenCount = computed(() => cases.value.filter((c) => c.isHidden === 1).length);

async function loadTags() {
  try {
    tags.value = (await problemApi.tagList()) || [];
  } catch {
    tags.value = [];
  }
}

async function loadProblem() {
  if (!isEdit.value) return;
  try {
    const d = await problemApi.detail(problemId.value);
    Object.assign(form, {
      title: d.title || '',
      difficulty: d.difficulty ?? 1,
      timeLimitMs: d.timeLimitMs ?? 1000,
      memoryLimitMb: d.memoryLimitMb ?? 256,
      status: d.status ?? 0,
      tagIds: (d.tags || []).map((t) => t.id),
      statement: d.statement || '',
      inputSpec: d.inputSpec || '',
      outputSpec: d.outputSpec || '',
      hint: d.hint || '',
      templateCode: {
        java: d.templateCode?.java || '',
        cpp: d.templateCode?.cpp || '',
        python: d.templateCode?.python || '',
        go: d.templateCode?.go || '',
      },
    });
  } catch (e) {
    ElMessage.error(e.message || '加载题目失败');
  }
}

async function loadCases() {
  if (!isEdit.value) return;
  try {
    const list = await problemApi.listTestCases(problemId.value);
    cases.value = (list || []).map((c) => ({
      seq: c.seq,
      stdin: c.stdin ?? '',
      expectedStdout: c.expectedStdout ?? '',
      isHidden: c.isHidden ?? 0,
      score: c.score ?? 0,
      timeLimitMs: c.timeLimitMs ?? null,
      judgeMode: c.judgeMode ?? 0,
    }));
  } catch {
    cases.value = [];
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
 * 保存用例（全量替换）。
 *
 * 用例与题目是**两个独立请求**：题面改了但用例没保存，或反之，
 * 所以这里的保存按钮只作用域用例集合，不隐式带上题目表单 —— 避免用户
 * 只是想存个用例却把没写完的题面一起提交了。
 */
async function saveCases() {
  caseError.value = '';
  const seqs = cases.value.map((c) => c.seq);
  if (new Set(seqs).size !== seqs.length) {
    caseError.value = '存在重复的用例序号（seq 必须唯一，否则唯一键冲突会导致整批失败）';
    return;
  }
  if (!cases.value.length) {
    try {
      await ElMessageBox.confirm('用例为空会被清空该题全部用例，确定吗？', '确认', { type: 'warning' });
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
    const n = await problemApi.replaceTestCases(problemId.value, payload);
    ElMessage.success(`已写入 ${n} 条用例`);
  } catch (e) {
    caseError.value = e.message || '保存用例失败';
  } finally {
    savingCases.value = false;
  }
}

/* ------------------------------ 保存题目 ------------------------------ */
async function onSave() {
  const valid = await formRef.value?.validate().catch(() => false);
  if (!valid) {
    tab.value = 'basic';
    return;
  }
  saving.value = true;
  try {
    /**
     * 只提交非空的模板代码：空字符串同样会被存成"该语言模板是空"，
     * 前端编辑器遇到空模板会退化到内置骨架，两种行为等价，
     * 但传空串会在库中留下 noise。这里剔除空值让语义更干净。
     */
    const templateCode = Object.fromEntries(
      Object.entries(form.templateCode).filter(([, v]) => v && v.trim()),
    );

    const payload = {
      title: form.title,
      difficulty: form.difficulty,
      timeLimitMs: form.timeLimitMs,
      memoryLimitMb: form.memoryLimitMb,
      status: form.status,
      tagIds: form.tagIds,
      statement: form.statement,
      inputSpec: form.inputSpec,
      outputSpec: form.outputSpec,
      hint: form.hint,
      templateCode: Object.keys(templateCode).length ? templateCode : null,
    };

    if (isEdit.value) {
      await problemApi.update(problemId.value, payload);
      ElMessage.success('题目已更新（题面有变更时会自动生成新版本）');
      await loadProblem();
    } else {
      const newId = await problemApi.create(payload);
      ElMessage.success('题目已创建（草稿），请继续补充测试用例');
      // 创建后直接跳编辑页：没有用例的题目无法判题，把用户留在"还能继续配"的页面上
      router.replace({ name: 'teacher-problem-edit', params: { id: newId } });
    }
  } catch (e) {
    ElMessage.error(e.message || '保存失败');
  } finally {
    saving.value = false;
  }
}

onMounted(async () => {
  await loadTags();
  if (isEdit.value) {
    await loadProblem();
    await loadCases();
  }
});
</script>

<style scoped>
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
.case-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}
.case-label {
  font-size: 12px;
  color: var(--cj-text-dim);
  margin-bottom: 4px;
}
code {
  font-family: var(--cj-mono);
  background: var(--cj-panel-2);
  border: 1px solid var(--cj-border);
  border-radius: 4px;
  padding: 0 4px;
}
@media (max-width: 900px) {
  .case-grid {
    grid-template-columns: 1fr;
  }
}
</style>
