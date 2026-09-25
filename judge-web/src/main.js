import { createApp } from 'vue';
import { createPinia } from 'pinia';
import ElementPlus from 'element-plus';
import zhCn from 'element-plus/es/locale/lang/zh-cn';
import * as ElementPlusIconsVue from '@element-plus/icons-vue';

import 'element-plus/dist/index.css';
// Element Plus 暗色主题变量：配合 <html class="dark"> 生效
import 'element-plus/theme-chalk/dark/css-vars.css';

// 等宽层字体，自托管。只用 latin 子集（每字重约 15KB）——中文字形由系统栈提供，
// 引入 CJK webfont 会带来 2–8MB 的首屏代价，不划算。
import '@fontsource/ibm-plex-mono/latin-400.css';
import '@fontsource/ibm-plex-mono/latin-500.css';
import '@fontsource/ibm-plex-mono/latin-600.css';

// 顺序要紧：token 层先定义变量，EP 对齐层要在 EP 自带样式**之后**才能覆盖它；
// data-list 是「列表页骨架 + 单元格对齐契约」，依赖 main.css 的 token，故排在其后
import '@/styles/main.css';
import '@/styles/data-list.css';
import '@/styles/theme-element.css';

import App from '@/App.vue';
import router from '@/router';
import { configureAuth } from '@/api/http';
import perm from '@/directives/permission';
import { useUserStore } from '@/stores/user';
import { useThemeStore } from '@/stores/theme';

const app = createApp(App);
const pinia = createPinia();

app.use(pinia);

// 图标全局注册（模板里直接 <el-icon><Search /></el-icon>）
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component);
}

// 按钮级权限指令：无权限即不渲染（见 directives/permission.js 的设计说明）
app.directive('perm', perm);

app.use(ElementPlus, { locale: zhCn });

// 主题与登录态都必须在挂载路由之前初始化：
// 若放在组件 mounted 里，首屏会先按浅色渲染再闪成深色（FOUC），且路由守卫读不到 token。
useThemeStore().init();
useUserStore().restore();

// 后端明确拒绝（403）时由前端「带路」到 403 页。
// 注意方向：**判定「谁被拒绝」的是后端**，这里只是把结果呈现出来 ——
// 前端不猜权限，只在被拒绝时给出可理解的落点。
configureAuth({
  onForbidden: () => {
    if (router.currentRoute.value.name !== 'forbidden') {
      router.push({ name: 'forbidden' });
    }
  },
});

app.use(router);
app.mount('#app');
