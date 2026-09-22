import { describe, it, expect, beforeEach, vi } from 'vitest';
import { setActivePinia, createPinia } from 'pinia';

/**
 * 登录态 store 测试。
 *
 * 核心是两件容易写错、且写错后很难查的事：
 *   1. loadProfile 只在 **401** 时清登录态；网络错误必须保留 token 并把异常抛出去。
 *      反过来写（catch 到任何错误就 clear）会把"后端挂了"伪装成"登录过期"，
 *      用户看到的是莫名其妙被登出，排障方向全错。
 *   2. loadProfile 幂等：路由守卫每次跳转都会调它，不能每次都打接口。
 */

vi.mock('@/api', () => ({
  authApi: {
    login: vi.fn(),
    adminLogin: vi.fn(),
    logout: vi.fn(),
    myMenus: vi.fn(),
  },
  userApi: { me: vi.fn() },
}));

// eslint-disable-next-line import/first
import { authApi, userApi } from '@/api';
// eslint-disable-next-line import/first
import { useUserStore, USER_TYPE } from '@/stores/user';
// eslint-disable-next-line import/first
import { TOKEN_STORAGE_KEY } from '@/api/http';

let store;

beforeEach(() => {
  setActivePinia(createPinia());
  localStorage.clear();
  // resetAllMocks（而非 clearAllMocks）：连同实现一起清掉，
  // 避免上一个用例 mockResolvedValue 的返回值渗到下一个用例里造成假绿
  vi.resetAllMocks();
  authApi.myMenus.mockResolvedValue([]);
  store = useUserStore();
});

describe('用户类型常量', () => {
  it('与后端 UserRole 对齐：1 员工 2 学员 3 教师', () => {
    expect(USER_TYPE).toEqual({ STAFF: 1, STUDENT: 2, TEACHER: 3 });
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

describe('角色 getter', () => {
  const withProfile = (type) => {
    store.profile = { type };
  };

  it('学员：只能做题，不能管题也不能管用户', () => {
    withProfile(USER_TYPE.STUDENT);
    expect(store.isStudent).toBe(true);
    expect(store.isTeacher).toBe(false);
    expect(store.isStaff).toBe(false);
    expect(store.canManage).toBe(false);
    expect(store.isAdmin).toBe(false);
  });

  it('教师：可管题目/竞赛，但不是管理员', () => {
    withProfile(USER_TYPE.TEACHER);
    expect(store.isTeacher).toBe(true);
    expect(store.canManage).toBe(true);
    expect(store.isAdmin).toBe(false);
  });

  it('管理员：canManage 与 isAdmin 同时成立', () => {
    withProfile(USER_TYPE.STAFF);
    expect(store.isAdmin).toBe(true);
    expect(store.canManage).toBe(true);
  });

  it('未登录时所有角色判断为假，type 为 null', () => {
    expect(store.type).toBeNull();
    expect(store.canManage).toBe(false);
    expect(store.isAdmin).toBe(false);
  });

  it('typeLabel / displayName 的取值优先级与兜底', () => {
    store.profile = { type: 2, name: '张三', username: 'u', cellPhone: '139' };
    expect(store.typeLabel).toBe('学员');
    expect(store.displayName).toBe('张三');

    store.profile = { type: 3, username: 'u', cellPhone: '139' };
    expect(store.displayName).toBe('u');

    store.profile = { type: 3, cellPhone: '13900000000' };
    expect(store.displayName).toBe('13900000000');

    store.profile = { type: 9 };
    expect(store.typeLabel).toBe('未知');
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

  it('成功时写入 profile、loaded，并顺带拉菜单', async () => {
    store.setToken('T');
    userApi.me.mockResolvedValue({ type: 3, name: '王老师' });
    authApi.myMenus.mockResolvedValue([{ id: 1, name: '题目管理' }]);

    const profile = await store.loadProfile();

    expect(profile).toEqual({ type: 3, name: '王老师' });
    expect(store.loaded).toBe(true);
    expect(store.menus).toEqual([{ id: 1, name: '题目管理' }]);
  });

  it('菜单接口失败不影响主流程（新账号菜单为空是正常情况）', async () => {
    store.setToken('T');
    userApi.me.mockResolvedValue({ type: 2 });
    authApi.myMenus.mockRejectedValue(new Error('500'));

    await expect(store.loadProfile()).resolves.toEqual({ type: 2 });
    expect(store.menus).toEqual([]);
    expect(store.loaded).toBe(true);
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

describe('login / logout', () => {
  it('登录成功后写入 token 并立刻拉资料', async () => {
    authApi.login.mockResolvedValue({ accessToken: 'AT', refreshToken: 'ignored' });
    userApi.me.mockResolvedValue({ type: 2, name: '学员甲' });

    const r = await store.login({ cellPhone: '139', password: 'x' });

    expect(r.accessToken).toBe('AT');
    expect(store.accessToken).toBe('AT');
    expect(store.profile).toEqual({ type: 2, name: '学员甲' });
    expect(authApi.adminLogin).not.toHaveBeenCalled();
  });

  it('admin=true 走管理端登录入口', async () => {
    authApi.adminLogin.mockResolvedValue({ accessToken: 'AT2' });
    userApi.me.mockResolvedValue({ type: 1 });

    await store.login({ cellPhone: '139', password: 'x' }, true);

    expect(authApi.adminLogin).toHaveBeenCalledTimes(1);
    expect(authApi.login).not.toHaveBeenCalled();
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

  it('logout 即使后端不可达也要清干净本地登录态', async () => {
    store.setToken('T');
    store.profile = { type: 2 };
    store.loaded = true;
    authApi.logout.mockRejectedValue(new Error('网关不可达'));

    await store.logout();

    expect(store.accessToken).toBe('');
    expect(store.profile).toBeNull();
    expect(store.loaded).toBe(false);
    expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBeNull();
  });

  it('clear 同时清 token / profile / menus / loaded', () => {
    store.setToken('T');
    store.profile = { type: 1 };
    store.menus = [{ id: 1 }];
    store.loaded = true;

    store.clear();

    expect(store.accessToken).toBe('');
    expect(store.profile).toBeNull();
    expect(store.menus).toEqual([]);
    expect(store.loaded).toBe(false);
  });
});
