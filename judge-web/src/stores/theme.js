import { defineStore } from 'pinia';

/**
 * 主题（暗色 / 亮色）
 *
 * Element Plus 的暗色模式靠 `<html class="dark">` 激活（配合
 * `element-plus/theme-chalk/dark/css-vars.css`），自定义变量见 styles/main.css。
 *
 * 初始值取 `VITE_DEFAULT_THEME`，其次跟随系统 `prefers-color-scheme`。
 * 生效时机必须早于首屏渲染，否则会闪一下亮色（FOUC）——故由 main.js 主动调用 init()。
 */

const STORAGE_KEY = 'cj_theme';

export const useThemeStore = defineStore('theme', {
  state: () => ({
    mode: 'dark',
  }),

  getters: {
    isDark: (s) => s.mode === 'dark',
  },

  actions: {
    init() {
      const saved = localStorage.getItem(STORAGE_KEY);
      let mode = saved;

      if (!mode) {
        const envDefault = import.meta.env.VITE_DEFAULT_THEME;
        if (envDefault === 'dark' || envDefault === 'light') {
          mode = envDefault;
        } else if (typeof window !== 'undefined' && window.matchMedia) {
          mode = window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
        } else {
          mode = 'dark';
        }
      }
      this.apply(mode);
    },

    toggle() {
      this.apply(this.mode === 'dark' ? 'light' : 'dark');
    },

    apply(mode) {
      this.mode = mode;
      localStorage.setItem(STORAGE_KEY, mode);
      if (typeof document !== 'undefined') {
        document.documentElement.classList.toggle('dark', mode === 'dark');
      }
    },
  },
});
