import { defineStore } from 'pinia';
import { configureAuth, TOKEN_STORAGE_KEY } from '@/api/http';
import { authApi, userApi } from '@/api';

/**
 * 登录态 store
 *
 * ── 鉴权信息的两条约定（本文件是「前端不参与鉴权」的落点）──────────────────────
 *
 * 1. **能力码由后端下发，前端只做渲染。**
 *    本 store 不再暴露 `isStudent / canManage / isAdmin` 这类**角色 getter**。
 *    原因：角色 → 能做什么 是权限模型，把它写在前端意味着
 *    ① 角色语义一变就要改一堆 .vue；② 前端与后端的判定是两套独立规则，迟早不一致；
 *    ③ 前端能算出权限，等于把权限模型公开给客户端。
 *    现在唯一可用的判定入口是 `can(code)`（以及按钮上的 `v-perm`），
 *    而 code 的来源是 `GET /accounts/me/capabilities`。
 *
 * 2. **能力缺失时 fail-closed。**
 *    `capabilities` 为 null（未登录 / 接口失败）时 `can()` 恒 false ⇒ 受限按钮全部不渲染。
 *    宁可不给入口，也不给一个点了报 403 的入口。
 *
 * ── 其余两条既有设计（保持不变）────────────────────────────────────────────────
 * · access token 放 localStorage（网关要求 `Authorization: Bearer`，无法用 HttpOnly）；
 *   refresh token 由后端以 HttpOnly Cookie 下发，前端**拿不到也不需要**。
 * · `loadProfile` 幂等：路由守卫每次跳转都会调，用 loaded 标记避免重复请求。
 */
/**
 * 引导态标记的持久化键。
 *
 * 用 sessionStorage 而**不是** localStorage：它提示的是「本次部署仍在用初始管理员口令」，
 * 属于跟随这一次访问的提醒，没有必要跨标签页共享 —— 跨标签共享反而会在
 * 「A 标签页改完密、B 标签页仍在提醒」时给出过期结论。
 */
const BOOTSTRAP_STORAGE_KEY = 'cj_bootstrap_pending';

export const useUserStore = defineStore('user', {
  state: () => ({
    accessToken: '',
    profile: null,
    /**
     * 后端下发的「本部署仍处于引导态」标记（`POST /accounts/login` 响应里的
     * `mustChangePassword`；判据是 `.bootstrap-credentials` 文件还在不在）。
     *
     * 为什么必须持久化：这个字段**只在登录响应里出现**，没有任何接口能二次查询。
     * 不持久化的话，用户按一次 F5 提醒就永久消失 —— 而它提醒的恰恰是
     * 「管理员口令还是文件里那个明文初始口令」这件事，最不该静默。
     */
    mustChangePassword: false,
    /**
     * 后端下发的能力画像：
     * `{ role, roleAlias, roleLabel, home, menus: [{key,name,path,icon,group,perm}], perms: [{code,name}] }`
     * 为 null 表示「尚未取到 / 取失败」—— 一切 `can()` 判定此时恒为 false。
     */
    capabilities: null,
    /** 能力画像加载失败标记：界面据此给出可见提示，避免"按钮凭空消失"被当成权限被收回 */
    permError: false,
    loaded: false,
    /** 登录请求进行中（防重复提交） */
    loggingIn: false,
  }),

  getters: {
    isLoggedIn: (s) => !!s.accessToken,

    /** 角色值仅用于展示（顶栏标签）。**不得**用它做任何权限判断，判断一律走 can() */
    role: (s) => s.capabilities?.role ?? s.profile?.type ?? null,
    roleLabel: (s) => s.capabilities?.roleLabel || '未知',

    /** 登录后默认落地路由，由后端按角色给出 */
    home: (s) => s.capabilities?.home || '/problems',

    /** 后端已过滤好的导航项 */
    menus: (s) => s.capabilities?.menus || [],

    /**
     * 能力码判定函数。用作 `user.can('problem:create')`。
     * 返回闭包而不是布尔：getter 本身被缓存，Set 只在 capabilities 变化时重建一次，
     * 模板里成百次调用不会每次都做数组遍历。
     */
    can: (s) => {
      const granted = new Set((s.capabilities?.perms || []).map((p) => p.code));
      return (code) => !!code && granted.has(code);
    },

    displayName: (s) =>
      s.profile?.name || s.profile?.username || s.profile?.cellPhone || '未登录',
  },

  actions: {
    /** main.js 在挂载路由前调用 */
    restore() {
      this.accessToken = localStorage.getItem(TOKEN_STORAGE_KEY) || '';
      this.mustChangePassword = sessionStorage.getItem(BOOTSTRAP_STORAGE_KEY) === '1';
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
     * 设置引导态标记。
     *
     * **唯一写入点** —— state 与 sessionStorage 必须同进同退，分成两处写迟早各说各话。
     * 写入方只有两个：登录响应、改密成功。
     */
    setMustChangePassword(pending) {
      this.mustChangePassword = !!pending;
      if (pending) {
        sessionStorage.setItem(BOOTSTRAP_STORAGE_KEY, '1');
      } else {
        sessionStorage.removeItem(BOOTSTRAP_STORAGE_KEY);
      }
    },

    /**
     * 登录。
     *
     * **只有一个入口，没有 `admin` 参数。** 角色是账号的属性，不是用户的选择：
     * 后端校验账号密码后按 `user.type` 自行判定角色、登录类型与 refresh cookie 形态，
     * 前端既不需要知道、也不该知道这些分支。
     *
     * @param {{cellPhone:string,password:string}} form
     */
    async login(form) {
      this.loggingIn = true;
      try {
        const result = await authApi.login(form);
        this.setToken(result.accessToken);
        this.setMustChangePassword(result?.mustChangePassword);
        this.loaded = false;
        await this.loadProfile(true);
        return result;
      } finally {
        this.loggingIn = false;
      }
    },

    /**
     * 拉取当前用户资料 + 能力画像。
     *
     * 资料失败的分流规则（保持既有设计）：只有 **401** 才清登录态；
     * 网络错误/网关未启动时保留 token 并抛出，否则后端一挂就会把用户"自动登出"，
     * 把服务故障伪装成登录过期。
     */
    async loadProfile(force = false) {
      if (this.loaded && !force) return this.profile;
      if (!this.accessToken) return null;
      try {
        this.profile = await userApi.me();
        this.loaded = true;
        await this.loadCapabilities();
        return this.profile;
      } catch (e) {
        if (e?.code === 401) {
          this.clear();
          return null;
        }
        throw e;
      }
    },

    /**
     * 拉取能力画像。
     *
     * 失败**不抛出**：资料已经拿到了，不该因为这一个接口把整页判成加载失败。
     * 但也不能静默 —— 静默的后果是"受限按钮全部消失"，看起来就像权限被收回了，
     * 是本次改造里最容易埋雷的地方。故置 `permError` 标记，由顶栏给出可见提示。
     */
    async loadCapabilities() {
      if (!this.accessToken) {
        this.capabilities = null;
        return null;
      }
      try {
        this.capabilities = await authApi.capabilities();
        this.permError = false;
        return this.capabilities;
      } catch (e) {
        if (e?.code === 401) {
          this.clear();
          return null;
        }
        this.capabilities = null;
        this.permError = true;
        console.warn('[user] 能力画像加载失败，本次会话不渲染任何受限入口', e);
        return null;
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
      // 退出登录后不必再提醒：下次登录后端会按服务端实况重新下发，
      // 留着反而会在"已经改完密、只是退个登录"的场景里给出过期结论。
      this.setMustChangePassword(false);
      this.profile = null;
      this.capabilities = null;
      this.permError = false;
      this.loaded = false;
    },
  },
});
