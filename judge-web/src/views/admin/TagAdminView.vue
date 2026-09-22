<template>
  <div class="cj-page">
    <div class="cj-card">
      <div class="cj-card__head">
        <span class="cj-title">标签管理</span>
        <div class="cj-row">
          <el-button type="primary" :icon="Plus" @click="dialog = true">新建标签</el-button>
          <el-button :icon="Refresh" @click="load">刷新</el-button>
        </div>
      </div>

      <div class="cj-card__body">
        <el-alert
          type="info"
          :closable="false"
          show-icon
          style="margin-bottom: 14px"
          title="标签是全局共享字典，增删收敛到管理员；教师建题时从已有标签里挑选。删除采用物理删除，因此被删标签名可以重新创建（逻辑删除会因唯一键不含 deleted 列而永久锁死复用）。"
        />

        <div class="cj-row" style="margin-bottom: 12px">
          <el-radio-group v-model="typeFilter" @change="load">
            <el-radio-button value="">全部</el-radio-button>
            <el-radio-button value="ALGORITHM">算法</el-radio-button>
            <el-radio-button value="SOURCE">来源</el-radio-button>
            <el-radio-button value="DIFFICULTY_TAG">难度</el-radio-button>
          </el-radio-group>
        </div>

        <div class="cj-scroll-x">
          <el-table v-loading="loading" :data="rows" stripe row-key="id" empty-text="暂无标签">
            <el-table-column label="ID" width="120">
              <template #default="{ row }">
                <span class="cj-mono cj-dim">{{ row.id }}</span>
              </template>
            </el-table-column>
            <el-table-column label="标签名" min-width="180">
              <template #default="{ row }">
                <el-tag effect="plain">{{ row.name }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="类型" width="160">
              <template #default="{ row }">
                <span class="cj-mono cj-dim">{{ row.type || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="110" align="center">
              <template #default="{ row }">
                <el-button link type="danger" @click="onDelete(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </div>
    </div>

    <el-dialog v-model="dialog" title="新建标签" width="420px">
      <el-form :model="form" label-width="76px">
        <el-form-item label="标签名" required>
          <el-input v-model.trim="form.name" placeholder="如：动态规划" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="form.type" style="width: 100%">
            <el-option label="算法 (ALGORITHM)" value="ALGORITHM" />
            <el-option label="来源 (SOURCE)" value="SOURCE" />
            <el-option label="难度 (DIFFICULTY_TAG)" value="DIFFICULTY_TAG" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onCreate">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus, Refresh } from '@element-plus/icons-vue';
import { problemApi } from '@/api';

const loading = ref(false);
const rows = ref([]);
const typeFilter = ref('');
const dialog = ref(false);
const saving = ref(false);
const form = reactive({ name: '', type: 'ALGORITHM' });

async function load() {
  loading.value = true;
  try {
    rows.value = (await problemApi.tagList(typeFilter.value || undefined)) || [];
  } catch (e) {
    rows.value = [];
    ElMessage.error(e.message || '加载标签失败');
  } finally {
    loading.value = false;
  }
}

async function onCreate() {
  if (!form.name) {
    ElMessage.warning('请输入标签名');
    return;
  }
  saving.value = true;
  try {
    await problemApi.createTag({ ...form });
    ElMessage.success('标签已创建');
    dialog.value = false;
    form.name = '';
    load();
  } catch (e) {
    // 标签名全局唯一，重复创建后端会返回明确提示，直接透出即可
    ElMessage.error(e.message || '创建失败');
  } finally {
    saving.value = false;
  }
}

async function onDelete(row) {
  try {
    await ElMessageBox.confirm(`删除标签「${row.name}」？被题目引用时后端会拒绝删除。`, '删除标签', {
      type: 'warning',
    });
  } catch {
    return;
  }
  try {
    await problemApi.removeTag(row.id);
    ElMessage.success('已删除');
    load();
  } catch (e) {
    // 被引用时后端会返回明确原因（如"该标签仍被 N 道题引用"），原样提示
    ElMessage.error(e.message || '删除失败');
  }
}

onMounted(load);
</script>
