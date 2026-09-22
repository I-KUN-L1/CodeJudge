import { createApp } from 'vue';
import { createPinia } from 'pinia';
import ElementPlus from 'element-plus';
import zhCn from 'element-plus/es/locale/lang/zh-cn';
import * as ElementPlusIconsVue from '@element-plus/icons-vue';

import 'element-plus/dist/index.css';
// Element Plus 暗色主题变量：配合 <html class="dark"> 生效
import 'element-plus/theme-chalk/dark/css-vars.css';
import '@/styles/main.css';

import App from '@/App.vue';
import router from '@/router';
import { useUserStore } from '@/stores/user';
import { useThemeStore } from '@/stores/theme';

const app = createApp(App);
const pinia = createPinia();

app.use(pinia);

// 图标全局注册（模板里直接 <el-icon><Search /></el-icon>）
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component);
}

app.use(ElementPlus, { locale: zhCn });

// 主题与登录态都必须在挂载路由之前初始化：
// 若放在组件 mounted 里，首屏会先按浅色渲染再闪成深色（FOUC），且路由守卫读不到 token。
useThemeStore().init();
useUserStore().restore();

app.use(router);
app.mount('#app');
