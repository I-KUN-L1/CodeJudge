<template>
  <div class="err cj-page">
    <div class="err__box cj-card">
      <div class="err__code">{{ code }}</div>
      <h2 class="err__title">{{ title }}</h2>
      <p class="err__desc">{{ desc }}</p>
      <div class="cj-row" style="justify-content: center">
        <el-button @click="router.back()">返回上一页</el-button>
        <el-button type="primary" @click="router.push('/problems')">回到题库</el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';

/**
 * 403 / 404 通用错误页。
 * 403 的典型来源：当前账号缺少该路由 `meta.perm` 要求的能力码
 * （如学员访问 /admin/users）—— 这里只做前端引导，
 * 真正的权限边界在后端 @RequireRole 上。
 */
const route = useRoute();
const router = useRouter();

const code = computed(() => route.meta?.code || 404);

const title = computed(() => (code.value === 403 ? '无权访问' : '页面不存在'));

const desc = computed(() =>
  code.value === 403
    ? '当前账号的角色不能访问该页面。教师/管理员功能需要相应用户类型。'
    : '你访问的地址没有对应的页面，可能是链接已失效或输入有误。',
);
</script>

<style scoped>
.err {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 62vh;
}
.err__box {
  max-width: 520px;
  width: 100%;
  padding: 40px 28px;
  text-align: center;
}
/* 错误码用**尺寸与字重**取得第一视觉层级，不用渐变字。
   蓝紫渐变是上一版的遗留（登录页已去除，这里属于同一处未收尾的改动）：
   渐变字在深色模式下会掉对比，且把"错误码"这一中性读数涂成品牌色是错的语义 */
.err__code {
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  font-size: var(--fs-2xl);
  font-weight: var(--fw-bold);
  line-height: 1;
  letter-spacing: var(--ls-tight);
  color: var(--fg);
}
.err__title {
  margin: 14px 0 8px;
}
.err__desc {
  color: var(--cj-text-sub);
  margin: 0 0 22px;
}
</style>
