<template>
  <div class="auth">
    <div class="auth__bg" aria-hidden="true" />

    <div class="auth__card cj-card">
      <div class="auth__head">
        <div class="auth__mark">CJ</div>
        <h1 class="auth__title">注册学员账号</h1>
        <p class="auth__sub">
          教师账号需由管理员开通（<code>/teachers/register</code> 已收紧为管理员专属）
        </p>
      </div>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        size="large"
        @submit.prevent="onSubmit"
      >
        <el-form-item label="手机号" prop="cellPhone">
          <el-input v-model.trim="form.cellPhone" maxlength="11" placeholder="11 位手机号" />
        </el-form-item>

        <el-form-item label="姓名" prop="name">
          <el-input v-model.trim="form.name" placeholder="用于榜单与提交记录展示" maxlength="30" />
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <el-input v-model="form.password" type="password" show-password placeholder="至少 6 位" />
        </el-form-item>

        <el-form-item label="确认密码" prop="confirm">
          <el-input v-model="form.confirm" type="password" show-password @keyup.enter="onSubmit" />
        </el-form-item>

        <el-button type="primary" size="large" class="auth__submit" :loading="submitting" @click="onSubmit">
          注 册
        </el-button>
      </el-form>

      <div class="auth__foot">
        <span class="cj-dim">已有账号？</span>
        <router-link to="/login">返回登录</router-link>
      </div>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { userApi } from '@/api';

const router = useRouter();
const formRef = ref(null);
const submitting = ref(false);

const form = reactive({ cellPhone: '', name: '', password: '', confirm: '' });

/**
 * 校验规则必须与后端 StudentController.validateRegister 一致
 * （手机号 ^1\d{10}$、密码 ≥ 6 位）—— 前端先拦一道只为体验，
 * 真正的判定权永远在后端：前端校验可被绕过。
 */
const rules = {
  cellPhone: [
    { required: true, message: '请输入手机号', trigger: 'blur' },
    { pattern: /^1\d{10}$/, message: '手机号格式不正确', trigger: 'blur' },
  ],
  name: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, message: '密码至少 6 位', trigger: 'blur' },
  ],
  confirm: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    {
      validator: (_r, value, cb) =>
        value === form.password ? cb() : cb(new Error('两次输入的密码不一致')),
      trigger: 'blur',
    },
  ],
};

async function onSubmit() {
  const valid = await formRef.value?.validate().catch(() => false);
  if (!valid) return;

  submitting.value = true;
  try {
    // 后端会强制把 type 置为 2（学员），忽略客户端传入的角色
    await userApi.registerStudent({
      cellPhone: form.cellPhone,
      name: form.name,
      password: form.password,
    });
    ElMessage.success('注册成功，请登录');
    router.replace({ name: 'login' });
  } catch (e) {
    ElMessage.error(e.message || '注册失败');
  } finally {
    submitting.value = false;
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
.auth__bg {
  position: absolute;
  inset: -20%;
  background:
    radial-gradient(42% 42% at 76% 16%, rgba(59, 110, 246, 0.22), transparent 70%),
    radial-gradient(38% 38% at 24% 80%, rgba(124, 77, 255, 0.2), transparent 70%);
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
}
.auth__title {
  margin: 0;
  font-size: 21px;
}
.auth__sub {
  margin: 6px 0 0;
  font-size: 12.5px;
  color: var(--cj-text-sub);
}
.auth__sub code {
  font-family: var(--cj-mono);
  font-size: 11.5px;
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
</style>
