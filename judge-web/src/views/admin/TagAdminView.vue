<template>
  <div class="cj-page">
    <!-- ============ 页头 ============ -->
    <header class="cj-pagehead">
      <h1 class="cj-pagehead__title">标签管理</h1>
      <span class="cj-pagehead__meta">
        共 <b>{{ rows.length }}</b> 个
      </span>
      <div class="cj-spacer" />
      <div class="cj-pagehead__actions">
        <el-button type="primary" :icon="Plus" @click="dialog = true">新建标签</el-button>
        <el-button :icon="Refresh" @click="load">刷新</el-button>
      </div>
    </header>

    <!-- ============ 说明 + 筛选条 ============ -->
    <div class="cj-toolbar">
      <el-radio-group v-model="typeFilter" @change="load">
        <el-radio-button value="">全部</el-radio-button>
        <el-radio-button value="ALGORITHM">算法</el-radio-button>
        <el-radio-button value="SOURCE">来源</el-radio-button>
        <el-radio-button value="DIFFICULTY_TAG">难度</el-radio-button>
      </el-radio-group>
    </div>

    <div class="cj-panel">
      <div class="cj-panel__table">
        <el-table v-loading="loading" :data="rows" row-key="id" empty-text="暂无标签">
          <el-table-column label="ID" width="112" align="right">
            <template #default="{ row }">
              <span class="cj-num cj-dim">{{ row.id }}</span>
            </template>
          </el-table-column>

          <!-- 标签名直接用标签渲染：这里看到的就是它在题库/题目详情里的样子 -->
          <el-table-column label="标签名" min-width="200">
            <template #default="{ row }">
              <div class="cj-cell-tags">
                <el-tag size="small" effect="plain">{{ row.name }}</el-tag>
              </div>
            </template>
          </el-table-column>

          <el-table-column label="类型" width="200">
            <template #default="{ row }">
              <span class="cj-mono cj-dim">{{ row.type || '—' }}</span>
            </template>
          </el-table-column>

          <el-table-column label="操作" width="110" align="right">
            <template #default="{ row }">
              <el-button link type="danger" @click="onDelete(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <!-- 面板脚注：把原先那个占了整屏宽度的 el-alert 收成一行脚注。
           它是"说明"不是"告警"，用告警条表达会让每次进页面都像出了问题 -->
      <div class="cj-panel__note">
        标签是全局共享字典，增删收敛到管理员；教师建题时从已有标签里挑选。
        删除采用**物理删除**，因此被删标签名可以重新创建（逻辑删除会因唯一键不含 deleted 列而永久锁死复用）。
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

<!-- 无 scoped 样式：一律用全局 data-list.css 的列表骨架 -->
