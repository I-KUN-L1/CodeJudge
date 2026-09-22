<template>
  <div class="auth">
    <div class="auth__bg" aria-hidden="true" />

    <div class="auth__card cj-card">
      <div class="auth__head">
        <div class="auth__mark">CJ</div>
        <h1 class="auth__title">CodeJudge</h1>
        <p class="auth__sub">分布式在线编程评测平台 · 判题 / 竞赛 / AI 点评</p>
      </div>

      <!-- 两个登录入口后端是不同端点：
           学员/教师走 /accounts/login，管理员走 /accounts/admin/login
           （refresh cookie 的 key 也不同，见 AccountController.writeRefreshCookie） -->
      <el-radio-group v-model="mode" class="auth__mode" size="default">
        <el-radio-button value="user">学员 / 教师</el-radio-button>
        <el-radio-button value="admin">管理员</el-radio-button>
      </el-radio-group>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        size="large"
        @submit.prevent="onSubmit"
      >
        <el-form-item label="手机号" prop="cellPhone">
          <el-input
            v-model.trim="form.cellPhone"
            placeholder="11 位手机号"
            :prefix-icon="Iphone"
            maxlength="11"
            clearable
          />
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="请输入密码"
            :prefix-icon="Lock"
            show-password
            @keyup.enter="onSubmit"
          />
        </el-form-item>

        <el-button
          type="primary"
          size="large"
          class="auth__submit"
          :loading="user.loggingIn"
          @click="onSubmit"
        >
          登 录
        </el-button>
      </el-form>

      <div class="auth__foot">
        <span class="cj-dim">还没有账号？</span>
        <router-link to="/register">注册学员账号</router-link>
        <span class="cj-spacer" />
        <a href="javascript:void(0)" @click="firstChangeVisible = true">首次登录改密</a>
      </div>

      <el-alert
        v-if="errorMsg"
        class="auth__alert"
        type="error"
        :closable="false"
        show-icon
        :title="errorMsg"
      />

      <div class="auth__hint cj-dim">
        网关地址 <code>{{ apiBase }}</code>
        <br />
        演示账号：学员 <code>13900000001</code> / 密码 <code>123456</code>
      </div>
    </div>

    <!-- 首次登录强制改密：只对"引导生成的首个管理员"有意义 -->
    <el-dialog v-model="firstChangeVisible" title="首次登录修改初始密码" width="440px">
      <p class="cj-sub" style="margin-top: 0">
        首个管理员由 judge-auth 启动时安全引导生成，初始凭据写在仓库根目录
        <code>.bootstrap-credentials</code>。改密成功后该文件会被自动删除。
      </p>
      <el-form :model="pwdForm" label-position="top">
        <el-form-item label="手机号">
          <el-input v-model.trim="pwdForm.cellPhone" maxlength="11" />
        </el-form-item>
        <el-form-item label="初始密码">
          <el-input v-model="pwdForm.oldPassword" type="password" show-password />
        </el-form-item>
        <el-form-item label="新密码（至少 6 位）">
          <el-input v-model="pwdForm.newPassword" type="password" show-password />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="firstChangeVisible = false">取消</el-button>
        <el-button type="primary" :loading="changing" @click="onFirstChange">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { Iphone, Lock } from '@element-plus/icons-vue';
import { useUserStore } from '@/stores/user';
import { authApi } from '@/api';
import { API_BASE } from '@/api/base';

const route = useRoute();
const router = useRouter();
const user = useUserStore();
const apiBase = API_BASE;

const mode = ref('user');
const formRef = ref(null);
const errorMsg = ref('');
const form = reactive({ cellPhone: '', password: '' });

const rules = {
  cellPhone: [
    { required: true, message: '请输入手机号', trigger: 'blur' },
    { pattern: /^1\d{10}$/, message: '手机号格式不正确', trigger: 'blur' },
  ],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
};

// 切换登录入口时清掉上一次的错误提示，避免"管理员登录失败"的红条留在学员入口上
watch(mode, () => {
  errorMsg.value = '';
  form.password = '';
});

async function onSubmit() {
  errorMsg.value = '';
  const valid = await formRef.value?.validate().catch(() => false);
  if (!valid) return;

  try {
    await user.login({ ...form }, mode.value === 'admin');
    ElMessage.success(`欢迎回来，${user.displayName}`);
    // 带 redirect 时回到被拦截的页面，否则进题库
    const redirect = route.query.redirect;
    router.replace(typeof redirect === 'string' && redirect ? redirect : '/problems');
  } catch (e) {
    errorMsg.value = e.message || '登录失败';
  }
}

/* ------------------------------ 首次改密 ------------------------------ */
const firstChangeVisible = ref(false);
const changing = ref(false);
const pwdForm = reactive({ cellPhone: '', oldPassword: '', newPassword: '' });

async function onFirstChange() {
  if (!pwdForm.cellPhone || !pwdForm.oldPassword || !pwdForm.newPassword) {
    ElMessage.warning('请填写完整');
    return;
  }
  if (pwdForm.newPassword.length < 6) {
    ElMessage.warning('新密码至少 6 位');
    return;
  }
  changing.value = true;
  try {
    await authApi.firstChangePassword({ ...pwdForm });
    ElMessage.success('密码已修改，请用新密码登录');
    firstChangeVisible.value = false;
    form.cellPhone = pwdForm.cellPhone;
    form.password = '';
    pwdForm.oldPassword = '';
    pwdForm.newPassword = '';
  } catch (e) {
    ElMessage.error(e.message || '修改失败');
  } finally {
    changing.value = false;
  }
}
</script>

<style scoped>
.auth {
  position: relative;
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px 16px;
  overflow: hidden;
}

/* 背景光斑：纯装饰，用 aria-hidden 屏蔽读屏器 */
.auth__bg {
  position: absolute;
  inset: -20%;
  background:
    radial-gradient(42% 42% at 22% 18%, rgba(59, 110, 246, 0.22), transparent 70%),
    radial-gradient(38% 38% at 78% 76%, rgba(124, 77, 255, 0.2), transparent 70%);
  filter: blur(6px);
  pointer-events: none;
}

.auth__card {
  position: relative;
  width: 100%;
  max-width: 420px;
  padding: 28px 26px 22px;
}

.auth__head {
  text-align: center;
  margin-bottom: 18px;
}
.auth__mark {
  width: 46px;
  height: 46px;
  margin: 0 auto 10px;
  border-radius: 13px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #3b6ef6, #7c4dff);
  color: #fff;
  font-weight: 700;
  font-size: 17px;
  letter-spacing: 0.5px;
}
.auth__title {
  margin: 0;
  font-size: 22px;
  letter-spacing: 0.3px;
}
.auth__sub {
  margin: 6px 0 0;
  font-size: 12.5px;
  color: var(--cj-text-sub);
}

.auth__mode {
  display: flex;
  width: 100%;
  margin-bottom: 14px;
}
.auth__mode :deep(.el-radio-button) {
  flex: 1 1 0;
}
.auth__mode :deep(.el-radio-button__inner) {
  width: 100%;
}

.auth__submit {
  width: 100%;
  margin-top: 4px;
  letter-spacing: 4px;
}

.auth__foot {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 16px;
  font-size: 13px;
}

.auth__alert {
  margin-top: 14px;
}

.auth__hint {
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px dashed var(--cj-border);
  line-height: 1.9;
}
.auth__hint code {
  font-family: var(--cj-mono);
  background: var(--cj-panel-2);
  border: 1px solid var(--cj-border);
  border-radius: 4px;
  padding: 0 4px;
}
</style>
