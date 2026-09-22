import { defineStore } from 'pinia';
import { configureAuth, TOKEN_STORAGE_KEY } from '@/api/http';
import { authApi, userApi } from '@/api';

/**
 * 登录态
 *
 * 关键点：
 *  1. access token 放 localStorage（网关要求 `Authorization: Bearer`，无法用 HttpOnly）；
 *     refresh token 由后端以 HttpOnly Cookie 下发，前端**拿不到也不需要** —— 这正是设计意图。
 *  2. 通过 configureAuth 把 token 读取器与"登录态失效"回调注入 http.js，
 *     避免 api 层反向 import store 造成循环依赖。
 *  3. loadProfile 是幂等的：路由守卫每次跳转都会调，用 loaded 标记避免重复请求。
 */

/** 用户类型（与后端 UserRole 一致：1员工 2学员 3教师） */
export const USER_TYPE = {
  STAFF: 1,
  STUDENT: 2,
  TEACHER: 3,
};

export const useUserStore = defineStore('user', {
  state: () => ({
    accessToken: '',
    profile: null,
    menus: [],
    loaded: false,
    /** 登录请求进行中（防重复提交） */
    loggingIn: false,
  }),

  getters: {
    isLoggedIn: (s) => !!s.accessToken,
    type: (s) => s.profile?.type ?? null,
    isStaff: (s) => s.profile?.type === USER_TYPE.STAFF,
    isTeacher: (s) => s.profile?.type === USER_TYPE.TEACHER,
    isStudent: (s) => s.profile?.type === USER_TYPE.STUDENT,
    /** 可管理题目/竞赛：教师 + 管理员 */
    canManage: (s) => s.profile?.type === USER_TYPE.STAFF || s.profile?.type === USER_TYPE.TEACHER,
    /** 仅管理员：用户管理、标签维护、判题机集群 */
    isAdmin: (s) => s.profile?.type === USER_TYPE.STAFF,
    displayName: (s) =>
      s.profile?.name || s.profile?.username || s.profile?.cellPhone || '未登录',
    typeLabel: (s) => {
      const map = { 1: '管理员', 2: '学员', 3: '教师' };
      return map[s.profile?.type] || '未知';
    },
  },

  actions: {
    /** main.js 在挂载路由前调用 */
    restore() {
      this.accessToken = localStorage.getItem(TOKEN_STORAGE_KEY) || '';
      configureAuth({
        getToken: () => this.accessToken,
        onExpired: (reason, newToken) => {
          if (reason === 'refresh' && newToken) {
            this.setToken(newToken);
          } else {
            this.clear();
          }
        },
      });
    },

    setToken(token) {
      this.accessToken = token || '';
      if (token) {
        localStorage.setItem(TOKEN_STORAGE_KEY, token);
      } else {
        localStorage.removeItem(TOKEN_STORAGE_KEY);
      }
    },

    /**
     * 登录。
     * @param {{cellPhone:string,password:string}} form
     * @param {boolean} admin 走管理端入口（决定后端下发哪个 refresh cookie）
     */
    async login(form, admin = false) {
      this.loggingIn = true;
      try {
        const result = admin ? await authApi.adminLogin(form) : await authApi.login(form);
        this.setToken(result.accessToken);
        this.loaded = false;
        await this.loadProfile(true);
        return result;
      } finally {
        this.loggingIn = false;
      }
    },

    /**
     * 拉取当前用户资料。
     *
     * ⚠️ 只有 **401** 才清登录态；网络错误/网关未启动时保留 token 并抛出，
     * 否则后端一挂就会把用户"自动登出"，把服务故障伪装成登录过期。
     */
    async loadProfile(force = false) {
      if (this.loaded && !force) return this.profile;
      if (!this.accessToken) return null;
      try {
        this.profile = await userApi.me();
        this.loaded = true;
        // 菜单接口对新账号可能为空，失败不影响主流程
        try {
          this.menus = (await authApi.myMenus()) || [];
        } catch {
          this.menus = [];
        }
        return this.profile;
      } catch (e) {
        if (e?.code === 401) {
          this.clear();
          return null;
        }
        throw e;
      }
    },

    async logout() {
      try {
        await authApi.logout();
      } catch {
        // 后端不可达也要让本地登出，否则用户被永久困在"看起来已登录"的状态
      }
      this.clear();
    },

    clear() {
      this.setToken('');
      this.profile = null;
      this.menus = [];
      this.loaded = false;
    },
  },
});
