<template>
  <div class="cj-page">
    <div class="cj-card">
      <div class="cj-card__head">
        <span class="cj-title">个人中心</span>
        <el-tag size="small" effect="plain">{{ user.roleLabel }}</el-tag>
      </div>

      <div class="cj-card__body">
        <el-descriptions :column="columnCount" border size="small">
          <el-descriptions-item label="用户 ID">
            <span class="cj-mono">{{ profile?.id || '—' }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="用户名">{{ profile?.username || '—' }}</el-descriptions-item>
          <el-descriptions-item label="姓名">{{ profile?.name || '—' }}</el-descriptions-item>
          <el-descriptions-item label="手机号">
            <span class="cj-mono">{{ profile?.cellPhone || '—' }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="邮箱">{{ profile?.email || '—' }}</el-descriptions-item>
          <el-descriptions-item label="城市">{{ profile?.city || '—' }}</el-descriptions-item>
          <el-descriptions-item label="性别">
            {{ profile?.gender === 1 ? '男' : profile?.gender === 2 ? '女' : '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag size="small" :type="profile?.status === 1 ? 'success' : 'danger'" effect="plain">
              {{ profile?.status === 1 ? '正常' : '已禁用' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="注册时间">{{ fmtTime(profile?.createTime) }}</el-descriptions-item>
        </el-descriptions>
      </div>
    </div>

    <!-- 资料编辑 -->
    <div class="cj-card">
      <div class="cj-card__head">编辑资料</div>
      <div class="cj-card__body">
        <el-form :model="form" label-width="82px" style="max-width: 460px">
          <el-form-item label="姓名">
            <el-input v-model.trim="form.name" maxlength="30" />
          </el-form-item>
          <el-form-item label="邮箱">
            <el-input v-model.trim="form.email" />
          </el-form-item>
          <el-form-item label="城市">
            <el-input v-model.trim="form.city" />
          </el-form-item>
          <el-form-item label="性别">
            <el-radio-group v-model="form.gender">
              <el-radio :value="1">男</el-radio>
              <el-radio :value="2">女</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="saving" @click="save">保存</el-button>
          </el-form-item>
        </el-form>
      </div>
    </div>

    <!-- 改密 -->
    <div class="cj-card">
      <div class="cj-card__head">修改密码</div>
      <div class="cj-card__body">
        <el-form :model="pwd" label-width="82px" style="max-width: 460px">
          <el-form-item label="原密码">
            <el-input v-model="pwd.oldPassword" type="password" show-password />
          </el-form-item>
          <el-form-item label="新密码">
            <el-input v-model="pwd.newPassword" type="password" show-password placeholder="至少 6 位" />
          </el-form-item>
          <el-form-item label="确认新密码">
            <el-input v-model="pwd.confirm" type="password" show-password />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="changing" @click="changePwd">修改密码</el-button>
            <span class="cj-dim" style="margin-left: 10px">修改成功后需要重新登录</span>
          </el-form-item>
        </el-form>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { authApi, userApi } from '@/api';
import { useUserStore } from '@/stores/user';
import { fmtTime } from '@/utils/format';

const router = useRouter();
const user = useUserStore();

const saving = ref(false);
const changing = ref(false);
const profile = ref(null);

const columnCount = computed(() => (window.innerWidth < 720 ? 1 : 4));

const form = reactive({ name: '', email: '', city: '', gender: null });
const pwd = reactive({ oldPassword: '', newPassword: '', confirm: '' });

function syncForm(p) {
  form.name = p?.name || '';
  form.email = p?.email || '';
  form.city = p?.city || '';
  form.gender = p?.gender ?? null;
}

async function load() {
  try {
    profile.value = await userApi.me();
    syncForm(profile.value);
  } catch (e) {
    ElMessage.error(e.message || '加载个人资料失败');
  }
}

async function save() {
  saving.value = true;
  try {
    // updateCurrentUser 走 PUT /users（后端取 body.id 定位记录）
    await userApi.updateMe({ id: profile.value.id, ...form });
    ElMessage.success('资料已更新');
    await user.loadProfile(true);
    await load();
  } catch (e) {
    ElMessage.error(e.message || '保存失败');
  } finally {
    saving.value = false;
  }
}

async function changePwd() {
  if (!pwd.oldPassword || !pwd.newPassword) {
    ElMessage.warning('请填写原密码与新密码');
    return;
  }
  if (pwd.newPassword.length < 6) {
    ElMessage.warning('新密码至少 6 位');
    return;
  }
  if (pwd.newPassword !== pwd.confirm) {
    ElMessage.warning('两次输入的新密码不一致');
    return;
  }
  changing.value = true;
  try {
    // ── 两条改密路径，按是否处于引导态分流 ────────────────────────────────
    // 必须分流的原因：删除 `.bootstrap-credentials` 的职责在 judge-auth，
    // 而 `PUT /students/password`（judge-user）只改库里的 BCrypt 摘要、碰不到那个文件。
    // 若引导态下走后者，密码是改了，但明文初始口令文件会永久留在磁盘上，
    // 且 isBootstrapPending() 恒真 ⇒ 每次 STAFF 登录都被提示改密 —— 需求
    // 「改密后该文件自动消失」在这条路径上静默失效。
    // 故引导态一律走 judge-auth 的 first-change（改库 + 删文件，见
    // AdminBootstrapService#changeBootstrapPassword）。
    if (user.mustChangePassword) {
      // 该接口靠手机号定位账号（走网关白名单、不带登录态），取不到就早失败，
      // 不要等后端回一句「账号不存在」让人去查登录态
      const cellPhone = profile.value?.cellPhone || user.profile?.cellPhone;
      if (!cellPhone) {
        ElMessage.error('未能取到本账号手机号，请刷新页面后重试');
        return;
      }
      await authApi.firstChangePassword({
        cellPhone,
        oldPassword: pwd.oldPassword,
        newPassword: pwd.newPassword,
      });
      // 文件已删除，提醒条随之撤下（不要等下次登录才消失）
      user.setMustChangePassword(false);
    } else {
      await userApi.changePassword({ oldPassword: pwd.oldPassword, newPassword: pwd.newPassword });
    }
    ElMessage.success('密码已修改，请重新登录');
    await user.logout();
    router.push({ name: 'login' });
  } catch (e) {
    ElMessage.error(e.message || '修改密码失败');
  } finally {
    changing.value = false;
  }
}

watch(() => user.profile, (p) => p && syncForm(p));
onMounted(load);
</script>
