<template>
  <div class="cj-page">
    <!-- ============ 页头 ============ -->
    <header class="cj-pagehead">
      <h1 class="cj-pagehead__title">用户管理</h1>
      <span class="cj-pagehead__meta">
        共 <b>{{ total }}</b> 位
      </span>
      <div class="cj-spacer" />
      <div class="cj-pagehead__actions">
        <el-button type="primary" :icon="Plus" @click="openCreate">新建用户</el-button>
        <el-button :icon="Refresh" @click="load">刷新</el-button>
      </div>
    </header>

    <!-- 三类角色分目录查询：/students/page、/teachers/page、/staffs/page 是各自独立的端点。
         页签属于"筛选维度"，故与搜索框同处一条工具带 -->
    <div class="cj-tabs-row">
      <el-tabs v-model="tab" @tab-change="onTabChange">
        <el-tab-pane label="学员" name="students" />
        <el-tab-pane label="教师" name="teachers" />
        <el-tab-pane label="管理员" name="staffs" />
      </el-tabs>

      <div class="cj-tabs-row__filter">
        <el-input
          v-model.trim="keyword"
          placeholder="按姓名 / 用户名 / 手机号搜索"
          clearable
          class="cj-toolbar__kw"
          @keyup.enter="applyFilter"
          @clear="applyFilter"
        />
        <el-button @click="applyFilter">查询</el-button>
      </div>
    </div>

    <div class="cj-panel">
      <div class="cj-panel__table">
        <el-table v-loading="loading" :data="rows" row-key="id" empty-text="暂无数据">
          <!-- ID 是 19 位雪花号：右对齐 + 等宽，位数相同的行右边缘才在同一条线上 -->
          <el-table-column label="ID" width="190" align="right">
            <template #default="{ row }">
              <span class="cj-num cj-dim">{{ row.id }}</span>
            </template>
          </el-table-column>

          <el-table-column label="姓名" width="130">
            <template #default="{ row }">
              <span class="cj-dim">{{ row.name || '—' }}</span>
            </template>
          </el-table-column>

          <el-table-column label="用户名" width="140" class-name="cj-hide-sm">
            <template #default="{ row }">
              <span class="cj-mono cj-dim">{{ row.username || '—' }}</span>
            </template>
          </el-table-column>

          <el-table-column label="手机号" width="140" align="right">
            <template #default="{ row }">
              <span class="cj-num">{{ row.cellPhone }}</span>
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

          <el-table-column label="注册时间" width="164" align="right" class-name="cj-hide-sm">
            <template #default="{ row }">
              <span class="cj-num-dim">{{ fmtTime(row.createTime) }}</span>
            </template>
          </el-table-column>

          <!-- 四个操作挤在 240px 里会折行、导致该单元格比相邻行高一倍。
               宽度放到 268px（四个两字链接 + 间距的最小值），并统一右对齐 -->
          <el-table-column label="操作" width="268" align="right" fixed="right">
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

      <div class="cj-pager">
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

    <!-- ================= 新建 / 编辑 ================= -->
    <el-dialog v-model="dialog" :title="editRow ? '编辑用户' : '新建用户'" width="480px">
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="82px">
        <!-- 建号策略：管理界面只产教师。学员走自助注册，管理员仅由后台引导/DBA 脚本创建；
             后端 POST /users 同样强制 type=3（忽略客户端传值），此处固定展示是同规的 UI 面 -->
        <el-form-item v-if="!editRow" label="角色">
          <el-tag effect="plain">教师</el-tag>
          <span class="cj-dim" style="margin-left: 8px; font-size: var(--fs-xs)">
            管理界面仅可创建教师账号；学员请自助注册，管理员仅由后台初始化产生
          </span>
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
        title="此处仅创建教师账号（后端强制 type=3）。学员请使用登录页自助注册；管理员账号仅由后台引导（.bootstrap-credentials）或 DBA 脚本产生，不提供在线创建入口"
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
  // 新建固定为教师(3)：与后端 POST /users 的强制覆盖同规（见下方 openCreate）
  type: 3,
  cellPhone: '',
  username: '',
  name: '',
  password: '',
  email: '',
  city: '',
});

const formRules = {
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

let reqSeq = 0;
async function load() {
  const seq = ++reqSeq;
  loading.value = true;
  try {
    const params = { pageNo: pageNo.value, pageSize: pageSize.value };
    if (keyword.value) params.keyword = keyword.value;
    const page = await endpointFor[tab.value](params);
    if (seq !== reqSeq) return; // 快速切页签/翻页时丢弃过期响应，避免数据与页签不一致
    rows.value = page?.list || [];
    total.value = Number(page?.total || 0);
  } catch (e) {
    if (seq !== reqSeq) return;
    rows.value = [];
    total.value = 0;
    ElMessage.error(e.message || '加载用户列表失败');
  } finally {
    if (seq === reqSeq) loading.value = false;
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
    type: 3, // 无论当前在哪个页签，新建一律是教师（后端亦强制覆盖 type）
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
/* 页签与搜索框同处一条工具带：页签在下沿有 1px 刻线，
   把搜索框抬到与页签文字同一行（-1px 抵消刻线占位） */
.cj-tabs-row {
  display: flex;
  align-items: center;
  gap: var(--sp-4);
  flex-wrap: wrap;
  padding-top: var(--sp-2);
}
.cj-tabs-row :deep(.el-tabs__header) {
  margin-bottom: 0;
}
.cj-tabs-row :deep(.el-tabs__nav-wrap::after) {
  height: 1px;
}
.cj-tabs-row__filter {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  margin-left: auto;
}
@media (max-width: 900px) {
  .cj-tabs-row__filter {
    margin-left: 0;
    width: 100%;
  }
}
</style>
