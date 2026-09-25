import { describe, it, expect, beforeEach, vi } from 'vitest';
import { setActivePinia, createPinia } from 'pinia';

/**
 * 登录态 store 测试。
 *
 * 覆盖三件容易写错、且写错后很难查的事：
 *   1. `loadProfile` 只在 **401** 时清登录态；网络错误必须保留 token 并把异常抛出去。
 *      反过来写（catch 到任何错误就 clear）会把"后端挂了"伪装成"登录过期"。
 *   2. `loadProfile` 幂等：路由守卫每次跳转都会调它。
 *   3. **能力码是权限判断的唯一入口**：`can()` 在能力缺失时必须恒 false（fail-closed），
 *      且 store 不得再暴露任何角色 getter —— 一旦有人加回 `isAdmin`，
 *      「前端不参与鉴权」这条约定就会悄悄失效，故用断言把门焊死。
 */

vi.mock('@/api', () => ({
  authApi: {
    login: vi.fn(),
    logout: vi.fn(),
    capabilities: vi.fn(),
  },
  userApi: { me: vi.fn() },
}));

// eslint-disable-next-line import/first
import { authApi, userApi } from '@/api';
// eslint-disable-next-line import/first
import { useUserStore } from '@/stores/user';
// eslint-disable-next-line import/first
import { TOKEN_STORAGE_KEY } from '@/api/http';

/** 构造一份后端下发的能力画像 */
function caps(codes, extra = {}) {
  return {
    role: 3,
    roleAlias: 'teacher',
    roleLabel: '教师',
    home: '/teacher/problems',
    menus: [{ key: 'problems', name: '题库', path: '/problems', icon: 'Notebook', group: 'primary', perm: 'problem:view' }],
    perms: codes.map((code) => ({ code, name: code })),
    ...extra,
  };
}

let store;

beforeEach(() => {
  setActivePinia(createPinia());
  localStorage.clear();
  sessionStorage.clear();
  // resetAllMocks（而非 clearAllMocks）：连同实现一起清掉，
  // 避免上一个用例 mockResolvedValue 的返回值渗到下一个用例里造成假绿
  vi.resetAllMocks();
  authApi.capabilities.mockResolvedValue(caps([]));
  store = useUserStore();
});

describe('不再暴露角色 getter（前端不参与鉴权）', () => {
  it('canManage / isAdmin / isStudent / isTeacher / isStaff 均已移除', () => {
    expect(store.canManage).toBeUndefined();
    expect(store.isAdmin).toBeUndefined();
    expect(store.isStudent).toBeUndefined();
    expect(store.isTeacher).toBeUndefined();
    expect(store.isStaff).toBeUndefined();
  });

  it('typeLabel 已移除，角色标签改由后端下发（roleLabel）', () => {
    expect(store.typeLabel).toBeUndefined();
    store.capabilities = caps([], { roleLabel: '管理员' });
    expect(store.roleLabel).toBe('管理员');
  });

  it('未取到能力画像时 roleLabel 兜底为「未知」', () => {
    expect(store.roleLabel).toBe('未知');
  });
});

describe('can() —— 能力码判定', () => {
  it('能力画像为 null 时恒 false（fail-closed）', () => {
    expect(store.capabilities).toBeNull();
    expect(store.can('problem:create')).toBe(false);
    expect(store.can('monitor:view')).toBe(false);
  });

  it('只对已下发的码返回 true', () => {
    store.capabilities = caps(['problem:view', 'problem:create']);
    expect(store.can('problem:view')).toBe(true);
    expect(store.can('problem:create')).toBe(true);
    expect(store.can('user:manage')).toBe(false);
  });

  it('空码 / 未定义一律 false，不因"没传参数"而放行', () => {
    store.capabilities = caps(['problem:view']);
    expect(store.can('')).toBe(false);
    expect(store.can(undefined)).toBe(false);
    expect(store.can(null)).toBe(false);
  });

  it('perms 结构异常时不抛错（防御后端返回残缺数据）', () => {
    store.capabilities = { role: 2, perms: null };
    expect(store.can('problem:view')).toBe(false);
  });
});

describe('menus / home', () => {
  it('menus 直接取后端下发的（后端已过滤）', () => {
    store.capabilities = caps(['problem:view']);
    expect(store.menus.map((m) => m.key)).toEqual(['problems']);
  });

  it('无能力时 menus 为空数组而非 undefined', () => {
    expect(store.menus).toEqual([]);
  });

  it('home 用后端下发的落地路由；缺失时兜底 /problems', () => {
    store.capabilities = caps([], { home: '/admin/users' });
    expect(store.home).toBe('/admin/users');

    store.capabilities = null;
    expect(store.home).toBe('/problems');
  });
});

describe('token 持久化', () => {
  it('setToken 写入 localStorage，空值则清除', () => {
    store.setToken('T1');
    expect(store.accessToken).toBe('T1');
    expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBe('T1');

    store.setToken('');
    expect(store.accessToken).toBe('');
    expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBeNull();
  });

  it('restore 从 localStorage 恢复（刷新页面后不掉登录）', () => {
    localStorage.setItem(TOKEN_STORAGE_KEY, 'PERSISTED');
    store.restore();
    expect(store.accessToken).toBe('PERSISTED');
    expect(store.isLoggedIn).toBe(true);
  });

  it('isLoggedIn 只看 accessToken', () => {
    expect(store.isLoggedIn).toBe(false);
    store.setToken('T');
    expect(store.isLoggedIn).toBe(true);
  });
});

describe('displayName 取值优先级与兜底', () => {
  it('name > username > cellPhone > 未登录', () => {
    store.profile = { name: '张三', username: 'u', cellPhone: '139' };
    expect(store.displayName).toBe('张三');

    store.profile = { username: 'u', cellPhone: '139' };
    expect(store.displayName).toBe('u');

    store.profile = { cellPhone: '13900000000' };
    expect(store.displayName).toBe('13900000000');

    store.profile = {};
    expect(store.displayName).toBe('未登录');
  });
});

describe('loadProfile 幂等与错误分流', () => {
  it('无 token 时直接返回 null 且不打接口', async () => {
    await expect(store.loadProfile()).resolves.toBeNull();
    expect(userApi.me).not.toHaveBeenCalled();
  });

  it('已加载且非 force 时不再重复请求（路由守卫每次跳转都会调）', async () => {
    store.setToken('T');
    userApi.me.mockResolvedValue({ type: 2, name: '李四' });

    await store.loadProfile();
    await store.loadProfile();
    await store.loadProfile();

    expect(userApi.me).toHaveBeenCalledTimes(1);
  });

  it('force=true 时强制重新拉取', async () => {
    store.setToken('T');
    userApi.me.mockResolvedValue({ type: 2 });

    await store.loadProfile();
    await store.loadProfile(true);

    expect(userApi.me).toHaveBeenCalledTimes(2);
  });

  it('成功时写入 profile、loaded，并顺带拉能力画像', async () => {
    store.setToken('T');
    userApi.me.mockResolvedValue({ type: 3, name: '王老师' });
    authApi.capabilities.mockResolvedValue(caps(['problem:manage']));

    const profile = await store.loadProfile();

    expect(profile).toEqual({ type: 3, name: '王老师' });
    expect(store.loaded).toBe(true);
    expect(store.can('problem:manage')).toBe(true);
    expect(store.permError).toBe(false);
  });

  it('401 才清登录态', async () => {
    store.setToken('T');
    const err = new Error('未登录或登录已过期');
    err.code = 401;
    userApi.me.mockRejectedValue(err);

    await expect(store.loadProfile()).resolves.toBeNull();
    expect(store.accessToken).toBe('');
    expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBeNull();
    expect(store.loaded).toBe(false);
  });

  it('网络错误（code=0）必须保留 token 并把异常抛给调用方', async () => {
    store.setToken('T');
    const err = new Error('无法连接网关');
    err.code = 0;
    userApi.me.mockRejectedValue(err);

    await expect(store.loadProfile()).rejects.toThrow('无法连接网关');
    // 关键断言：token 还在，否则后端一挂用户就被"自动登出"
    expect(store.accessToken).toBe('T');
    expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBe('T');
    expect(store.loaded).toBe(false);
  });
});

describe('loadCapabilities 的失败姿态', () => {
  it('能力接口失败不抛出（资料已拿到，不该整页判失败）', async () => {
    store.setToken('T');
    userApi.me.mockResolvedValue({ type: 2 });
    authApi.capabilities.mockRejectedValue(new Error('500'));

    await expect(store.loadProfile()).resolves.toEqual({ type: 2 });
    expect(store.loaded).toBe(true);
  });

  it('能力接口失败时 capabilities 为 null、permError 置位 —— 按钮全部不渲染而不是全渲染', async () => {
    store.setToken('T');
    userApi.me.mockResolvedValue({ type: 2 });
    authApi.capabilities.mockRejectedValue(new Error('500'));

    await store.loadProfile();

    expect(store.capabilities).toBeNull();
    expect(store.permError).toBe(true);
    expect(store.can('problem:create')).toBe(false);
  });

  it('能力接口返回 401 时清登录态（与 401 分流规则一致）', async () => {
    store.setToken('T');
    userApi.me.mockResolvedValue({ type: 2 });
    const err = new Error('登录已过期');
    err.code = 401;
    authApi.capabilities.mockRejectedValue(err);

    await store.loadProfile();

    expect(store.accessToken).toBe('');
    expect(store.capabilities).toBeNull();
  });

  it('重新加载成功后 permError 复位', async () => {
    store.setToken('T');
    userApi.me.mockResolvedValue({ type: 2 });
    authApi.capabilities.mockRejectedValueOnce(new Error('500'));
    await store.loadProfile();
    expect(store.permError).toBe(true);

    authApi.capabilities.mockResolvedValue(caps(['problem:view']));
    await store.loadCapabilities();
    expect(store.permError).toBe(false);
    expect(store.can('problem:view')).toBe(true);
  });
});

describe('login / logout', () => {
  it('登录只打一个入口，且不带角色参数（角色由后端按账号属性判定）', async () => {
    authApi.login.mockResolvedValue({ accessToken: 'AT', role: 1, roleLabel: '管理员' });
    userApi.me.mockResolvedValue({ type: 1, name: '管理员甲' });

    const r = await store.login({ cellPhone: '139', password: 'x' });

    expect(r.accessToken).toBe('AT');
    expect(store.accessToken).toBe('AT');
    expect(store.profile).toEqual({ type: 1, name: '管理员甲' });
    // 只传了表单，没有第二个参数（旧的 admin=true 分支已删除）
    expect(authApi.login).toHaveBeenCalledWith({ cellPhone: '139', password: 'x' });
  });

  it('登录失败时 loggingIn 必须复位（否则按钮永久转圈）', async () => {
    authApi.login.mockRejectedValue(new Error('用户名或密码错误'));

    await expect(store.login({ cellPhone: '139', password: 'bad' })).rejects.toThrow('用户名或密码错误');
    expect(store.loggingIn).toBe(false);
  });

  it('登录过程中 loggingIn 为 true（防重复提交）', async () => {
    let seen = null;
    authApi.login.mockImplementation(async () => {
      seen = store.loggingIn;
      return { accessToken: 'AT' };
    });
    userApi.me.mockResolvedValue({ type: 2 });

    await store.login({ cellPhone: '139', password: 'x' });
    expect(seen).toBe(true);
  });

  it('login 会强制重拉能力画像（换账号后不能沿用上一个账号的码）', async () => {
    store.capabilities = caps(['user:manage']);
    authApi.login.mockResolvedValue({ accessToken: 'AT' });
    userApi.me.mockResolvedValue({ type: 2 });
    authApi.capabilities.mockResolvedValue(caps(['problem:view']));

    await store.login({ cellPhone: '139', password: 'x' });

    expect(authApi.capabilities).toHaveBeenCalled();
    expect(store.can('user:manage')).toBe(false);
    expect(store.can('problem:view')).toBe(true);
  });

  it('logout 即使后端不可达也要清干净本地登录态', async () => {
    store.setToken('T');
    store.profile = { type: 2 };
    store.capabilities = caps(['problem:view']);
    store.loaded = true;
    authApi.logout.mockRejectedValue(new Error('网关不可达'));

    await store.logout();

    expect(store.accessToken).toBe('');
    expect(store.profile).toBeNull();
    expect(store.capabilities).toBeNull();
    expect(store.loaded).toBe(false);
    expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBeNull();
  });

  it('clear 同时清 token / profile / capabilities / permError / loaded', () => {
    store.setToken('T');
    store.profile = { type: 1 };
    store.capabilities = caps(['user:manage']);
    store.permError = true;
    store.loaded = true;

    store.clear();

    expect(store.accessToken).toBe('');
    expect(store.profile).toBeNull();
    expect(store.capabilities).toBeNull();
    expect(store.permError).toBe(false);
    expect(store.loaded).toBe(false);
    expect(store.can('user:manage')).toBe(false);
  });
});

/**
 * 引导态标记（`mustChangePassword`）。
 *
 * 它决定顶栏那条「仍在用初始管理员口令」的提醒条是否渲染，而它**只在登录响应里出现**、
 * 没有二次查询接口 —— 所以这里要焊死两件事：
 *   ① 登录时必须从响应里取到，并落进 sessionStorage（否则按一次 F5 提醒就永久消失）；
 *   ② 退出登录 / 改密成功必须撤掉（否则会拿过期结论继续提醒）。
 */
describe('mustChangePassword —— 引导态标记', () => {
  const KEY = 'cj_bootstrap_pending';

  it('登录响应带 mustChangePassword=true 时写入 state 与 sessionStorage', async () => {
    authApi.login.mockResolvedValue({ accessToken: 'AT', mustChangePassword: true });
    userApi.me.mockResolvedValue({ type: 1 });

    await store.login({ cellPhone: '13800000000', password: 'x' });

    expect(store.mustChangePassword).toBe(true);
    expect(sessionStorage.getItem(KEY)).toBe('1');
  });

  it('登录响应缺该字段（普通账号）时为 false，且不留痕', async () => {
    authApi.login.mockResolvedValue({ accessToken: 'AT' });
    userApi.me.mockResolvedValue({ type: 2 });

    await store.login({ cellPhone: '139', password: 'x' });

    expect(store.mustChangePassword).toBe(false);
    expect(sessionStorage.getItem(KEY)).toBeNull();
  });

  it('restore 从 sessionStorage 恢复 —— 刷新页面后提醒不消失', () => {
    sessionStorage.setItem(KEY, '1');
    store.restore();
    expect(store.mustChangePassword).toBe(true);
  });

  it('setMustChangePassword(false) 必须同时清掉持久化，否则下次刷新又"诈尸"', () => {
    store.setMustChangePassword(true);
    expect(sessionStorage.getItem(KEY)).toBe('1');

    store.setMustChangePassword(false);
    expect(store.mustChangePassword).toBe(false);
    expect(sessionStorage.getItem(KEY)).toBeNull();
  });

  it('退出登录清除标记（下次登录由后端按服务端实况重新下发）', () => {
    store.setToken('T');
    store.setMustChangePassword(true);

    store.clear();

    expect(store.mustChangePassword).toBe(false);
    expect(sessionStorage.getItem(KEY)).toBeNull();
  });

  it('刷新 token 的路径不得误清标记（setToken 与它无关）', () => {
    store.setMustChangePassword(true);
    store.setToken('NEW_TOKEN');
    expect(store.mustChangePassword).toBe(true);
  });
});
