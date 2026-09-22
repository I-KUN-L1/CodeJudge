import { describe, it, expect, beforeEach, vi } from 'vitest';
import { setActivePinia, createPinia } from 'pinia';

/**
 * 路由守卫测试。
 *
 * 守卫是**前端侧的权限边界**：即便后端每个接口都做了校验，前端漏掉这一层
 * 也会让学员点开管理页面、看到空表格再加一句"怎么什么都没有"。反过来，
 * 这里一旦误判成"未登录"，用户会莫名其妙被踢到登录页，是最容易被投诉的体验问题。
 *
 * 说明：这里刻意走**真实 router 实例**（而不是把守卫抽出来单测），
 * 因为守卫的返回值语义（返回 {name:'login'} 与返回 true 的区别、query.redirect
 * 是否带上）只有经过 vue-router 的导航流程才能被验证。
 */

vi.mock('@/api', () => ({
  authApi: { login: vi.fn(), adminLogin: vi.fn(), logout: vi.fn(), myMenus: vi.fn().mockResolvedValue([]) },
  userApi: { me: vi.fn() },
}));

// eslint-disable-next-line import/first
import router from '@/router';
// eslint-disable-next-line import/first
import { useUserStore } from '@/stores/user';

/** 直接构造"已登录 + 已加载资料"的状态，避免测试里真的去打接口 */
function loginAs(type) {
  const store = useUserStore();
  store.setToken('TEST_TOKEN');
  store.profile = { type, name: '测试用户' };
  store.loaded = true;
  return store;
}

async function go(path) {
  await router.push(path);
  return router.currentRoute.value;
}

beforeEach(async () => {
  setActivePinia(createPinia());
  localStorage.clear();
  // 回到一个已知的起点，避免上一个用例停留的页面影响 navigation 判定
  router.push('/login');
  await router.isReady();
});

describe('公开路由', () => {
  it('未登录可访问 /login 与 /register', async () => {
    expect((await go('/login')).name).toBe('login');
    expect((await go('/register')).name).toBe('register');
  });

  it('已登录访问 /login 会被弹回题库（避免登录后按返回键又回到登录页）', async () => {
    loginAs(2);
    const cur = await go('/login');
    expect(cur.name).toBe('problems');
  });

  it('已登录仍可访问 /register（注册页不拦，由后端决定是否允许开号）', async () => {
    loginAs(2);
    expect((await go('/register')).name).toBe('register');
  });
});

describe('需要登录的路由', () => {
  it('未登录访问题目详情 → 跳登录并带上 redirect', async () => {
    const cur = await go('/problems/42');
    expect(cur.name).toBe('login');
    expect(cur.query.redirect).toBe('/problems/42');
  });

  it('未登录访问提交记录 → 同样带 redirect', async () => {
    const cur = await go('/submissions');
    expect(cur.name).toBe('login');
    expect(cur.query.redirect).toBe('/submissions');
  });

  it('已登录学员可正常进入题库', async () => {
    loginAs(2);
    expect((await go('/problems')).name).toBe('problems');
  });
});

describe('角色限制路由', () => {
  it('学员访问教师侧 → 403（不是跳登录页）', async () => {
    loginAs(2);
    expect((await go('/teacher/problems')).name).toBe('forbidden');
  });

  it('学员访问管理侧 → 403', async () => {
    loginAs(2);
    expect((await go('/admin/users')).name).toBe('forbidden');
  });

  it('教师可进教师侧，但进不了管理侧', async () => {
    loginAs(3);
    expect((await go('/teacher/problems')).name).toBe('teacher-problems');
    expect((await go('/admin/workers')).name).toBe('forbidden');
  });

  it('教师可进题目编辑页（新建与编辑同属教师侧）', async () => {
    loginAs(3);
    expect((await go('/teacher/problems/new')).name).toBe('teacher-problem-new');
  });

  it('管理员管理侧全部可达', async () => {
    loginAs(1);
    expect((await go('/admin/users')).name).toBe('admin-users');
    expect((await go('/admin/tags')).name).toBe('admin-tags');
    expect((await go('/admin/workers')).name).toBe('admin-workers');
    expect((await go('/admin/monitor')).name).toBe('admin-monitor');
  });

  it('管理员同时也具备教师权限（STACK 包含关系）', async () => {
    loginAs(1);
    expect((await go('/teacher/knowledge')).name).toBe('teacher-knowledge');
  });
});

describe('兜底与首页', () => {
  it('根路径重定向到题库', async () => {
    loginAs(2);
    expect((await go('/')).name).toBe('problems');
  });

  it('未知路径 → 404 页面', async () => {
    loginAs(2);
    const cur = await go('/this/does/not/exist');
    expect(cur.name).toBe('not-found');
  });
});
