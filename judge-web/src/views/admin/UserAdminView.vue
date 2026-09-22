<template>
  <div class="cj-page">
    <div class="cj-card">
      <div class="cj-card__head">
        <span class="cj-title">用户管理</span>
        <div class="cj-row">
          <el-button type="primary" :icon="Plus" @click="openCreate">新建用户</el-button>
          <el-button :icon="Refresh" @click="load">刷新</el-button>
        </div>
      </div>

      <div class="cj-card__body">
        <!-- 三类角色分目录查询：/students/page、/teachers/page、/staffs/page 是各自独立的端点 -->
        <el-tabs v-model="tab" @tab-change="onTabChange">
          <el-tab-pane label="学员" name="students" />
          <el-tab-pane label="教师" name="teachers" />
          <el-tab-pane label="管理员" name="staffs" />
        </el-tabs>

        <div class="cj-row" style="margin-bottom: 12px">
          <el-input
            v-model.trim="keyword"
            placeholder="按姓名 / 用户名 / 手机号搜索"
            clearable
            class="filters__kw"
            @keyup.enter="applyFilter"
            @clear="applyFilter"
          />
          <el-button @click="applyFilter">查询</el-button>
          <span class="cj-dim">
            当前页 {{ rows.length }} 条 / 共 {{ total }} 条
          </span>
        </div>

        <div class="cj-scroll-x">
          <el-table v-loading="loading" :data="rows" stripe row-key="id" empty-text="暂无数据">
            <el-table-column label="ID" width="190">
              <template #default="{ row }">
                <span class="cj-mono cj-dim">{{ row.id }}</span>
              </template>
            </el-table-column>
            <el-table-column label="姓名" width="120">
              <template #default="{ row }">
                <span>{{ row.name || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="用户名" width="140" class-name="cj-hide-sm">
              <template #default="{ row }">
                <span class="cj-mono">{{ row.username || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="手机号" width="140">
              <template #default="{ row }">
                <span class="cj-mono">{{ row.cellPhone }}</span>
              </template>
            </el-table-column>
            <el-table-column label="角色" width="96" align="center">
              <template #default="{ row }">
                <el-tag size="small" effect="plain">{{ userTypeLabel(row.type) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="96" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === 1 ? 'success' : 'danger'" effect="plain">
                  {{ userStatusLabel(row.status) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="注册时间" width="160" class-name="cj-hide-sm">
              <template #default="{ row }">
                <span class="cj-dim">{{ fmtTime(row.createTime) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="240" align="center" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
                <el-button link type="warning" @click="onResetPwd(row)">重置密码</el-button>
                <el-button link :type="row.status === 1 ? 'info' : 'success'" @click="onToggleStatus(row)">
                  {{ row.status === 1 ? '禁用' : '启用' }}
                </el-button>
                <el-button link type="danger" @click="onDelete(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>

        <div class="pager">
          <el-pagination
            v-model:current-page="pageNo"
            v-model:page-size="pageSize"
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

    <!-- ================= 新建 / 编辑 ================= -->
    <el-dialog v-model="dialog" :title="editRow ? '编辑用户' : '新建用户'" width="480px">
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="82px">
        <el-form-item v-if="!editRow" label="角色" prop="type">
          <el-radio-group v-model="form.type">
            <el-radio :value="2">学员</el-radio>
            <el-radio :value="3">教师</el-radio>
            <el-radio :value="1">管理员</el-radio>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="手机号" prop="cellPhone">
          <el-input v-model.trim="form.cellPhone" maxlength="11" :disabled="!!editRow" />
        </el-form-item>

        <el-form-item label="用户名">
          <el-input v-model.trim="form.username" />
        </el-form-item>

        <el-form-item label="姓名" prop="name">
          <el-input v-model.trim="form.name" maxlength="30" />
        </el-form-item>

        <el-form-item v-if="!editRow" label="初始密码" prop="password">
          <el-input v-model="form.password" type="password" show-password placeholder="至少 6 位" />
        </el-form-item>

        <el-form-item label="邮箱">
          <el-input v-model.trim="form.email" />
        </el-form-item>

        <el-form-item label="城市">
          <el-input v-model.trim="form.city" />
        </el-form-item>
      </el-form>

      <el-alert
        v-if="!editRow"
        type="info"
        :closable="false"
        show-icon
        title="管理员可直接创建教师/管理员账号 —— 这是教师账号的唯一开通途径（/teachers/register 已收紧为管理员专属，取消匿名自助注册）"
      />

      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus, Refresh } from '@element-plus/icons-vue';
import { userApi } from '@/api';
import { fmtTime, userStatusLabel, userTypeLabel } from '@/utils/format';

const tab = ref('students');
const loading = ref(false);
const rows = ref([]);
const total = ref(0);
const pageNo = ref(1);
const pageSize = ref(20);
const keyword = ref('');

const dialog = ref(false);
const saving = ref(false);
const editRow = ref(null);
const formRef = ref(null);

const form = reactive({
  id: null,
  type: 2,
  cellPhone: '',
  username: '',
  name: '',
  password: '',
  email: '',
  city: '',
});

const formRules = {
  type: [{ required: true, message: '请选择角色', trigger: 'change' }],
  cellPhone: [
    { required: true, message: '请输入手机号', trigger: 'blur' },
    { pattern: /^1\d{10}$/, message: '手机号格式不正确', trigger: 'blur' },
  ],
  name: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  password: [
    { required: true, message: '请输入初始密码', trigger: 'blur' },
    { min: 6, message: '密码至少 6 位', trigger: 'blur' },
  ],
};

const endpointFor = {
  students: (p) => userApi.studentsPage(p),
  teachers: (p) => userApi.teachersPage(p),
  staffs: (p) => userApi.staffsPage(p),
};

async function load() {
  loading.value = true;
  try {
    const params = { pageNo: pageNo.value, pageSize: pageSize.value };
    if (keyword.value) params.keyword = keyword.value;
    const page = await endpointFor[tab.value](params);
    rows.value = page?.list || [];
    total.value = Number(page?.total || 0);
  } catch (e) {
    rows.value = [];
    total.value = 0;
    ElMessage.error(e.message || '加载用户列表失败');
  } finally {
    loading.value = false;
  }
}

function onTabChange() {
  pageNo.value = 1;
  keyword.value = '';
  load();
}

function applyFilter() {
  pageNo.value = 1;
  load();
}

function onSizeChange() {
  pageNo.value = 1;
  load();
}

function openCreate() {
  editRow.value = null;
  Object.assign(form, {
    id: null,
    type: tab.value === 'teachers' ? 3 : tab.value === 'staffs' ? 1 : 2,
    cellPhone: '',
    username: '',
    name: '',
    password: '',
    email: '',
    city: '',
  });
  dialog.value = true;
}

function openEdit(row) {
  editRow.value = row;
  Object.assign(form, {
    id: row.id,
    type: row.type,
    cellPhone: row.cellPhone,
    username: row.username || '',
    name: row.name || '',
    password: '',
    email: row.email || '',
    city: row.city || '',
  });
  dialog.value = true;
}

async function onSave() {
  const valid = await formRef.value?.validate().catch(() => false);
  if (!valid) return;
  saving.value = true;
  try {
    if (editRow.value) {
      await userApi.update(form.id, { ...form, password: undefined });
      ElMessage.success('已更新');
    } else {
      await userApi.add({ ...form });
      ElMessage.success('已创建');
    }
    dialog.value = false;
    load();
  } catch (e) {
    ElMessage.error(e.message || '保存失败');
  } finally {
    saving.value = false;
  }
}

async function onResetPwd(row) {
  try {
    await ElMessageBox.confirm(
      `将把「${row.name || row.cellPhone}」的密码重置为系统默认密码。确定继续？`,
      '重置密码',
      { type: 'warning' },
    );
  } catch {
    return;
  }
  try {
    await userApi.resetPassword(row.id);
    ElMessage.success('已重置为默认密码');
  } catch (e) {
    ElMessage.error(e.message || '重置失败');
  }
}

async function onToggleStatus(row) {
  const next = row.status === 1 ? 0 : 1;
  try {
    await userApi.updateStatus(row.id, next);
    ElMessage.success(next === 1 ? '已启用' : '已禁用');
    load();
  } catch (e) {
    ElMessage.error(e.message || '操作失败');
  }
}

async function onDelete(row) {
  try {
    await ElMessageBox.confirm(
      `删除用户「${row.name || row.cellPhone}」不可恢复。后端禁止删除当前登录账号与最后一名管理员。确定继续？`,
      '高危操作',
      { type: 'error', confirmButtonText: '确认删除' },
    );
  } catch {
    return;
  }
  try {
    await userApi.remove(row.id);
    ElMessage.success('已删除');
    load();
  } catch (e) {
    ElMessage.error(e.message || '删除失败');
  }
}

onMounted(load);
</script>

<style scoped>
.filters__kw {
  width: 280px;
}
.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}
</style>
