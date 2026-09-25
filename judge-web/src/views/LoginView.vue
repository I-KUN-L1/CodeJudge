<template>
  <div class="auth">
    <!-- ===================== 左侧：身份面板 =====================
         对开放平台而言，登录页是第一屏也是转化页：陌生人要先知道
         「这是什么、有什么」，才谈得上注册。故这里放的是**真实能力清单**
         （取自平台已实现的功能），而不是宣传话术或装饰插图 -->
    <section class="auth__id">
      <div class="auth__brand">
        <span class="auth__mark">CJ</span>
        <span class="auth__wordmark">CodeJudge</span>
      </div>

      <p class="auth__tagline">分布式在线编程评测平台</p>

      <dl class="spec">
        <div v-for="row in spec" :key="row.k" class="spec__row">
          <dt class="spec__key">{{ row.k }}</dt>
          <dd class="spec__val">{{ row.v }}</dd>
        </div>
      </dl>

      <div class="auth__endpoint">
        <span class="auth__endpoint-key">网关</span>
        <code class="auth__endpoint-val">{{ apiBase }}</code>
      </div>
    </section>

    <!-- ===================== 右侧：表单 ===================== -->
    <section class="auth__form">
      <div class="auth__card">
        <!-- 窄屏下左侧身份面板整体收起，故这里补一个紧凑品牌锁定，
             否则手机首屏将完全没有产品标识 -->
        <div class="auth__brand auth__brand--compact">
          <span class="auth__mark">CJ</span>
          <span class="auth__wordmark">CodeJudge</span>
        </div>

        <header class="auth__head">
          <h1 class="auth__title">登录</h1>
          <p class="auth__sub">还没有账号？<router-link to="/register">注册学员账号</router-link></p>
        </header>

        <!-- 只有一个登录入口：角色是账号自身的属性，不是登录前要做的选择。
             后端在 /accounts/login 内按 user.type 判定角色、登录类型与 refresh cookie，
             前端既不需要知道、也无法选错。 -->
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
              inputmode="numeric"
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
            登录
          </el-button>
        </el-form>

        <el-alert
          v-if="errorMsg"
          class="auth__alert"
          type="error"
          :closable="false"
          show-icon
          :title="errorMsg"
        />

        <!-- ⚠️ 这里**刻意不写**「初始凭据未消费」的提示条。
             曾有一条 `v-if="pwdHint"` 的 el-alert 挂在此处，但它在登录成功的那一拍
             就被 `router.replace()` 连带卸载（提示与跳转在同一个函数里，中间没有渲染窗口），
             实测从未显示过 —— 见 logs/tmp/verify-bootstrap.cjs。
             该提醒现由 AppLayout 的 `.boot-warn` 承担：挂在布局层，跨页面存活，
             进入任何页面都会提醒，直到改密成功。 -->

        <!-- 页脚刻意**不再列出任何演示账号与口令**。
             登录页是公开页面，把默认凭据印在上面等于给每个部署都发一把钥匙；
             开发/演示账号见 sql/seed.sql 与 README，首个管理员的初始凭据见
             项目根目录的 .bootstrap-credentials（改密后自动删除）。 -->
        <div class="auth__foot">
          <a href="javascript:void(0)" @click="openFirstChange">首次登录修改初始密码</a>
        </div>
      </div>
    </section>

    <!-- 首次登录强制改密：只对"引导生成的首个管理员"有意义 -->
    <el-dialog v-model="firstChangeVisible" title="首次登录修改初始密码" width="440px">
      <p class="auth__dialog-note">
        首个管理员由 judge-auth 启动时安全引导生成，初始凭据写在项目根目录
        <code>.bootstrap-credentials</code>（该文件只存在到改密成功为止，且不会重新生成）。
        若该文件已不存在，说明初始密码已被消费过。
      </p>
      <el-form :model="pwdForm" label-position="top">
        <el-form-item label="手机号">
          <el-input v-model.trim="pwdForm.cellPhone" maxlength="11" inputmode="numeric" />
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
import { reactive, ref } from 'vue';
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

/** 左侧规格表的条目 —— 只列平台已实现的能力，不写未实现的东西 */
const spec = [
  { k: '判题', v: '沙箱隔离 · 多语言 · 实时进度推送' },
  { k: '竞赛', v: '封榜语义 · 实时榜单' },
  { k: '点评', v: 'AI 代码点评 · 流式输出' },
  { k: '工作台', v: '建题 · 测试用例 · 知识库' },
];

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

async function onSubmit() {
  errorMsg.value = '';
  const valid = await formRef.value?.validate().catch(() => false);
  if (!valid) return;

  try {
    // 返回值不再需要：登录响应里的 `mustChangePassword` 由 store 自己记下
    // （`user.mustChangePassword`），再由 AppLayout 的提醒条在**跳转之后**呈现。
    // 曾在这里 setState 一条页面内提示 —— 它与 router.replace 同处一个函数，
    // 中间没有渲染窗口，实测从未显示过（见 template 中该处注释）。
    //
    // 另：引导态下个人中心改密会走 /accounts/password/first-change（改库 + 删凭据文件），
    // 而不是 /students/password —— 分流逻辑见 ProfileView#changePwd。
    await user.login({ ...form });
    ElMessage.success(`欢迎回来，${user.displayName}`);

    // 落地页由后端按角色给出（capabilities.home），前端不写死 '/problems'。
    // 带 redirect 时优先回到被拦截的页面。
    const redirect = route.query.redirect;
    router.replace(typeof redirect === 'string' && redirect ? redirect : user.home);
  } catch (e) {
    errorMsg.value = e.message || '登录失败';
  }
}

/* ------------------------------ 首次改密 ------------------------------ */
const firstChangeVisible = ref(false);
const changing = ref(false);
const pwdForm = reactive({ cellPhone: '', oldPassword: '', newPassword: '' });

function openFirstChange() {
  // 已填的手机号带过去，少一次誊抄
  if (!pwdForm.cellPhone) {
    pwdForm.cellPhone = form.cellPhone;
  }
  firstChangeVisible.value = true;
}

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
    // 凭据文件已由 judge-auth 删除 ⇒ 服务端不再是引导态。
    // 本地可能还留着上次登录写下的标记（同标签页内），一并撤掉，
    // 否则用户用新口令登进去还会看到一条过期的改密提醒。
    user.setMustChangePassword(false);
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
  min-height: 100vh;
  display: grid;
  grid-template-columns: minmax(0, 5fr) minmax(0, 4fr);
  background: var(--bg);
}

/* ------------------------------------------------ 左侧身份面板 */
.auth__id {
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: var(--sp-5);
  padding: var(--sp-8) clamp(var(--sp-5), 6vw, 88px);
  /* 大面积底色压到 surface-3，与右侧表单的 surface-1 隔开两档。
     比在浅色底上铺装饰渐变安静得多，也把视觉重量压回到内容上 */
  background: var(--surface-3);
  border-right: 1px solid var(--line-1);
}

.auth__brand {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
}
/* 桌面端由左侧身份面板承担品牌，此处不重复 */
.auth__brand--compact {
  display: none;
  margin-bottom: var(--sp-5);
}
.auth__mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: var(--r-sm);
  background: var(--ink);
  color: var(--fg-on-ink);
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  font-weight: var(--fw-semi);
  letter-spacing: var(--ls-tight);
}
.auth__wordmark {
  font-size: var(--fs-md);
  font-weight: var(--fw-semi);
  letter-spacing: var(--ls-tight);
}

.auth__tagline {
  margin: 0;
  font-size: var(--fs-xl);
  font-weight: var(--fw-semi);
  line-height: var(--lh-tight);
  letter-spacing: var(--ls-none);
  color: var(--fg);
  max-width: 16em;
}

/* 规格表：kv 两列，key 用等宽小字定宽，形成一条竖直对齐的刻线。
   这是本页的「仪表读数」部分，也是全站数字用等宽这一原则的示范 */
.spec {
  margin: 0;
  border-top: 1px solid var(--line-1);
}
.spec__row {
  display: grid;
  grid-template-columns: 4.5em 1fr;
  gap: var(--sp-3);
  align-items: baseline;
  padding: var(--sp-2) 0;
  border-bottom: 1px solid var(--line-1);
}
.spec__key {
  margin: 0;
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  letter-spacing: var(--ls-wide);
  color: var(--fg-3);
}
.spec__val {
  margin: 0;
  font-size: var(--fs-sm);
  color: var(--fg-2);
  line-height: var(--lh-base);
}

.auth__endpoint {
  display: flex;
  align-items: baseline;
  gap: var(--sp-2);
  font-size: var(--fs-xs);
}
.auth__endpoint-key {
  font-family: var(--font-mono);
  letter-spacing: var(--ls-wide);
  color: var(--fg-3);
}
.auth__endpoint-val {
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  color: var(--fg-2);
  background: var(--surface-3);
  border: 1px solid var(--line-1);
  border-radius: var(--r-xs);
  padding: 1px var(--sp-2);
}

/* ------------------------------------------------ 右侧表单 */
.auth__form {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--sp-6) var(--sp-5);
  background: var(--surface-1);
}

.auth__card {
  width: 100%;
  max-width: 360px;
}

.auth__head {
  margin-bottom: var(--sp-5);
  padding-bottom: var(--sp-3);
  border-bottom: 1px solid var(--line-1);
}
.auth__title {
  margin: 0 0 var(--sp-1);
  font-size: var(--fs-lg);
  font-weight: var(--fw-semi);
  line-height: var(--lh-tight);
  letter-spacing: var(--ls-none);
  color: var(--fg);
}
.auth__sub {
  margin: 0;
  font-size: var(--fs-xs);
  color: var(--fg-3);
}

.auth__submit {
  width: 100%;
  margin-top: var(--sp-1);
}

.auth__alert {
  margin-top: var(--sp-4);
}

.auth__foot {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  margin-top: var(--sp-4);
  padding-top: var(--sp-3);
  border-top: 1px dashed var(--line-1);
  font-size: var(--fs-xs);
  color: var(--fg-3);
}

.auth__dialog-note {
  margin-top: 0;
  font-size: var(--fs-sm);
  color: var(--fg-2);
  line-height: var(--lh-base);
}
.auth__dialog-note code {
  font-family: var(--font-mono);
  background: var(--surface-2);
  border: 1px solid var(--line-1);
  border-radius: var(--r-xs);
  padding: 0 4px;
}

/* 窄屏：身份面板的信息密度在手机上会变成阻碍，
   故整体收起为单列表单，品牌改由卡内的紧凑锁定承担 */
@media (max-width: 880px) {
  .auth {
    grid-template-columns: 1fr;
  }
  .auth__id {
    display: none;
  }
  .auth__form {
    min-height: 100vh;
    align-items: center;
    padding: var(--sp-5) var(--sp-4);
  }
  .auth__brand--compact {
    display: flex;
  }
}
</style>
