<template>
  <div class="cj-page">
    <div class="cj-card">
      <div class="cj-card__head">
        <div class="cj-row">
          <el-button link :icon="ArrowLeft" @click="router.back()">返回</el-button>
          <span class="cj-title">创建竞赛</span>
        </div>
        <el-button type="primary" :loading="saving" :icon="Check" @click="onSubmit">创建</el-button>
      </div>

      <div class="cj-card__body">
        <el-form ref="formRef" :model="form" :rules="rules" label-width="110px" style="max-width: 840px">
          <el-form-item label="竞赛标题" prop="title">
            <el-input v-model.trim="form.title" maxlength="120" placeholder="如：2026 秋季选拔赛 Round 1" />
          </el-form-item>

          <el-form-item label="描述">
            <el-input v-model="form.description" type="textarea" :rows="3" />
          </el-form-item>

          <el-form-item label="赛制">
            <el-radio-group v-model="form.rule">
              <el-radio-button value="ACM">ACM（按通过题数 + 罚时）</el-radio-button>
              <el-radio-button value="IOI">IOI（按总分）</el-radio-button>
            </el-radio-group>
          </el-form-item>

          <el-form-item label="开始时间" prop="startTime">
            <el-date-picker
              v-model="form.startTime"
              type="datetime"
              placeholder="选择开始时间"
              format="YYYY-MM-DD HH:mm:ss"
              value-format="YYYY-MM-DDTHH:mm:ss"
              style="width: 240px"
            />
          </el-form-item>

          <el-form-item label="结束时间" prop="endTime">
            <el-date-picker
              v-model="form.endTime"
              type="datetime"
              placeholder="选择结束时间"
              format="YYYY-MM-DD HH:mm:ss"
              value-format="YYYY-MM-DDTHH:mm:ss"
              style="width: 240px"
            />
            <span class="cj-dim" style="margin-left: 10px">必须晚于开始时间</span>
          </el-form-item>

          <el-form-item label="封榜时长">
            <el-input-number v-model="form.freezeMinutes" :min="0" :max="600" :step="10" />
            <span class="cj-dim" style="margin-left: 8px">
              分钟，0 = 不封榜。封榜**时刻**由后端推导为「结束时间 − 封榜时长」——
              延长比赛不需要同时改两个字段，避免漏改导致封榜时刻错位
            </span>
          </el-form-item>

          <el-form-item label="罚时规则">
            <el-input-number v-model="form.penaltyMinutes" :min="0" :max="120" :step="5" />
            <span class="cj-dim" style="margin-left: 8px">ACM 每次错误提交的罚时（分钟）</span>
          </el-form-item>

          <el-form-item label="竞赛题目" required>
            <div class="problems">
              <el-alert
                v-if="!form.problems.length"
                type="info"
                :closable="false"
                show-icon
                title="至少添加一道题目。题目必须已发布，否则后端会拒绝创建。"
              />
              <div v-for="(p, i) in form.problems" :key="i" class="problem-row">
                <el-select
                  v-model="p.problemId"
                  filterable
                  remote
                  :remote-method="searchProblems"
                  :loading="searching"
                  placeholder="搜索题目（输入标题关键字）"
                  style="width: 300px"
                >
                  <el-option
                    v-for="opt in problemOptions"
                    :key="opt.id"
                    :label="`#${opt.id} ${opt.title}`"
                    :value="opt.id"
                  />
                </el-select>

                <el-input v-model="p.label" placeholder="题号 A" style="width: 96px" />
                <el-input-number v-model="p.fullScore" :min="1" :max="1000" size="default" style="width: 120px" />
                <div class="cj-spacer" />
                <el-button link type="danger" @click="form.problems.splice(i, 1)">移除</el-button>
              </div>

              <el-button :icon="Plus" @click="addProblem">添加题目</el-button>
            </div>
          </el-form-item>
        </el-form>

        <el-alert
          v-if="errorMsg"
          type="error"
          :closable="false"
          show-icon
          style="max-width: 840px"
          :title="errorMsg"
        />
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { ArrowLeft, Check, Plus } from '@element-plus/icons-vue';
import { contestApi, problemApi } from '@/api';

const router = useRouter();

const formRef = ref(null);
const saving = ref(false);
const errorMsg = ref('');
const problemOptions = ref([]);
const searching = ref(false);

/** 题号自动生成：A、B、C…（超过 26 用 AA、AB） */
function labelFor(index) {
  let n = index;
  let s = '';
  do {
    s = String.fromCharCode(65 + (n % 26)) + s;
    n = Math.floor(n / 26) - 1;
  } while (n >= 0);
  return s;
}

const form = reactive({
  title: '',
  description: '',
  rule: 'ACM',
  startTime: '',
  endTime: '',
  freezeMinutes: 0,
  penaltyMinutes: 20,
  problems: [{ problemId: null, label: 'A', displayOrder: 0, fullScore: 100 }],
});

const rules = {
  title: [{ required: true, message: '请输入竞赛标题', trigger: 'blur' }],
  startTime: [{ required: true, message: '请选择开始时间', trigger: 'change' }],
  endTime: [
    { required: true, message: '请选择结束时间', trigger: 'change' },
    {
      validator: (_r, value, cb) => {
        if (!value || !form.startTime) return cb();
        // 时间比较统一走时间戳：字符串比较在跨时区/格式差异时不可靠
        return new Date(value).getTime() > new Date(form.startTime).getTime()
          ? cb()
          : cb(new Error('结束时间必须晚于开始时间'));
      },
      trigger: 'change',
    },
  ],
};

function addProblem() {
  const idx = form.problems.length;
  form.problems.push({
    problemId: null,
    label: labelFor(idx),
    displayOrder: idx,
    fullScore: 100,
  });
}

async function searchProblems(keyword) {
  searching.value = true;
  try {
    // 只搜「已发布」的题目：草稿/下线题目无法用于竞赛（后端会校验并拒绝）
    const page = await problemApi.page({ keyword: keyword || '', status: 1, pageNo: 1, pageSize: 20 });
    problemOptions.value = page?.list || [];
  } catch {
    problemOptions.value = [];
  } finally {
    searching.value = false;
  }
}

async function onSubmit() {
  errorMsg.value = '';
  const valid = await formRef.value?.validate().catch(() => false);
  if (!valid) return;

  if (!form.problems.length) {
    errorMsg.value = '竞赛至少需要一道题目';
    return;
  }
  if (form.problems.some((p) => !p.problemId)) {
    errorMsg.value = '存在未选择题目的条目，请补齐或移除';
    return;
  }

  saving.value = true;
  try {
    const payload = {
      title: form.title,
      description: form.description,
      rule: form.rule,
      // 后端字段是 LocalDateTime，ISO 的 "yyyy-MM-ddTHH:mm:ss" 可直接反序列化
      startTime: form.startTime,
      endTime: form.endTime,
      freezeMinutes: form.freezeMinutes,
      penaltyMinutes: form.penaltyMinutes,
      problems: form.problems.map((p, i) => ({
        problemId: p.problemId,
        label: p.label || labelFor(i),
        displayOrder: i,
        fullScore: p.fullScore ?? 100,
      })),
    };
    const created = await contestApi.create(payload);
    ElMessage.success('竞赛创建成功');
    router.replace({ name: 'contest-detail', params: { id: created?.id ?? '' } });
  } catch (e) {
    errorMsg.value = e.message || '创建失败';
  } finally {
    saving.value = false;
  }
}

onMounted(() => searchProblems(''));
</script>

<style scoped>
.problems {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.problem-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  padding: 8px;
  border: 1px solid var(--cj-border);
  border-radius: 8px;
}
</style>
