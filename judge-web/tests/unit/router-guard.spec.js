import { describe, it, expect, beforeEach, vi } from 'vitest';
import { setActivePinia, createPinia } from 'pinia';

/**
 * 路由守卫测试。
 *
 * ── 这里断言的边界是什么 ──────────────────────────────────────────────────
 * 守卫只做两件事：**登录态** 与 **能力码翻译**。
 * 它不判角色 —— 测试里也不应出现 `loginAs(2)` 这种写法。所以下面的辅助函数
 * 直接构造「后端下发了哪些能力码」，而不是构造「你是几号角色」：
 * 后者会把「角色 → 能力」的推导搬回前端测试里，正是本次改造要消掉的东西。
 *
 * 说明：刻意走**真实 router 实例**（而不是把守卫抽出来单测），
 * 因为守卫的返回值语义（返回 {name:'login'} 与返回 true 的区别、query.redirect
 * 是否带上）只有经过 vue-router 的导航流程才能被验证。
 */

vi.mock('@/api', () => ({
  authApi: { login: vi.fn(), logout: vi.fn(), capabilities: vi.fn() },
  userApi: { me: vi.fn() },
}));

// eslint-disable-next-line import/first
import router from '@/router';
// eslint-disable-next-line import/first
import { useUserStore } from '@/stores/user';

/**
 * 构造"已登录 + 已加载资料 + 已拿到能力画像"的状态，避免测试里真的去打接口。
 * @param {string[]} codes 后端下发的能力码
 * @param {string} home    后端下发的落地路由
 */
function loginWith(codes, home = '/problems') {
  const store = useUserStore();
  store.setToken('TEST_TOKEN');
  store.profile = { type: 9, name: '测试用户' };
  store.capabilities = {
    role: 9,
    roleAlias: 'test',
    roleLabel: '测试',
    home,
    menus: [],
    perms: codes.map((code) => ({ code, name: code })),
  };
  store.loaded = true;
  return store;
}

/** 学员的能力码（与后端 Capabilities.STUDENT 同集合，但本测试不依赖该推导关系） */
const LEARNER = ['problem:view', 'contest:view', 'contest:register', 'submission:create', 'submission:view-own', 'ai:review'];
/** 教学侧能力码 */
const TEACHING = [...LEARNER, 'problem:create', 'problem:edit', 'problem:manage', 'problem:testcase', 'contest:create', 'contest:manage', 'knowledge:manage'];
/** 管理面能力码 */
const ADMIN = [...TEACHING, 'user:manage', 'tag:manage', 'worker:view', 'monitor:view'];

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

  it('已登录访问 /login 会被弹回该账号的落地页（落地页由后端给）', async () => {
    loginWith(LEARNER, '/problems');
    expect((await go('/login')).name).toBe('problems');
  });

  it('教师的落地页是教学侧（后端 capabilities.home = /teacher/problems）', async () => {
    loginWith(TEACHING, '/teacher/problems');
    expect((await go('/login')).name).toBe('teacher-problems');
  });

  it('已登录仍可访问 /register（注册页不拦，由后端决定是否允许开号）', async () => {
    loginWith(LEARNER);
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
    loginWith(LEARNER);
    expect((await go('/problems')).name).toBe('problems');
  });
});

describe('能力码限制路由（前端不含任何角色语义）', () => {
  it('只有题库能力 → 教学侧进不去，落到 403（不是跳登录页）', async () => {
    loginWith(LEARNER);
    expect((await go('/teacher/problems')).name).toBe('forbidden');
  });

  it('只有题库能力 → 管理侧进不去', async () => {
    loginWith(LEARNER);
    expect((await go('/admin/users')).name).toBe('forbidden');
  });

  it('拿到 problem:manage 后可进教学侧，但仍进不了管理侧', async () => {
    loginWith(TEACHING, '/teacher/problems');
    expect((await go('/teacher/problems')).name).toBe('teacher-problems');
    expect((await go('/admin/workers')).name).toBe('forbidden');
  });

  it('新建题目与编辑题目各自只看自己的码', async () => {
    loginWith(['problem:view', 'problem:create'], '/problems');
    expect((await go('/teacher/problems/new')).name).toBe('teacher-problem-new');
    // 只有 create 没有 edit 时，编辑页进不去
    expect((await go('/teacher/problems/7/edit')).name).toBe('forbidden');
  });

  it('教学侧三个入口互相独立（创建竞赛不看 problem:manage）', async () => {
    loginWith(['problem:view', 'contest:create'], '/problems');
    expect((await go('/teacher/contests/new')).name).toBe('teacher-contest-new');
    expect((await go('/teacher/problems')).name).toBe('forbidden');
  });

  it('管理侧四个入口各自看自己的码', async () => {
    loginWith(ADMIN, '/admin/users');
    expect((await go('/admin/users')).name).toBe('admin-users');
    expect((await go('/admin/tags')).name).toBe('admin-tags');
    expect((await go('/admin/workers')).name).toBe('admin-workers');
    expect((await go('/admin/monitor')).name).toBe('admin-monitor');
  });

  it('只有 user:manage 时进不了标签管理（不同能力码不能互相顶替）', async () => {
    loginWith(['problem:view', 'user:manage'], '/admin/users');
    expect((await go('/admin/users')).name).toBe('admin-users');
    expect((await go('/admin/tags')).name).toBe('forbidden');
  });

  it('能力画像加载失败（capabilities 为 null）→ 受限页面一律 403，fail-closed', async () => {
    const store = useUserStore();
    store.setToken('TEST_TOKEN');
    store.profile = { type: 1 };
    store.capabilities = null;
    store.loaded = true;

    expect((await go('/admin/users')).name).toBe('forbidden');
    // 无能力要求的页面仍可访问 —— 不能因为能力接口挂了就把整个站点锁死
    expect((await go('/problems')).name).toBe('problems');
  });
});

describe('兜底与首页', () => {
  it('根路径固定重定向到题库（与角色的落地页无关）', async () => {
    loginWith(ADMIN, '/admin/users');
    expect((await go('/')).name).toBe('problems');
  });

  it('未知路径 → 404 页面', async () => {
    loginWith(LEARNER);
    const cur = await go('/this/does/not/exist');
    expect(cur.name).toBe('not-found');
  });
});
