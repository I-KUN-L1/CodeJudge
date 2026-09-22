import { createRouter, createWebHistory } from 'vue-router';
import { useUserStore, USER_TYPE } from '@/stores/user';

/**
 * 路由表
 *
 * ── meta 约定 ─────────────────────────────────────────────────────────────
 *   public : true      匿名可达（仅登录 / 注册 / 错误页）
 *   roles  : number[]  允许访问的用户类型（1员工 2学员 3教师）；不写=所有登录用户
 *   title  : string    浏览器标题与面包屑
 *
 * ── 为什么除登录注册外**全部**要求登录 ────────────────────────────────────
 *   网关的 JwtProperties.excludePaths 只放行了 /accounts/login、
 *   /accounts/admin/login、/accounts/refresh、/accounts/password/first-change、
 *   /students/register、/jwks、/v3/api-docs、/doc.html —— **题目列表不在其中**，
 *   匿名访问 /problems/page 会被网关 401。
 *   （P2 遗留第 5 项记录过这个"网关注释与配置不一致"的点，此处不做产品决策，
 *     前端一律按"需登录"处理，避免出现"页面打开了但列表永远为空"的假象。）
 */

const routes = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { public: true, title: '登录' },
  },
  {
    path: '/register',
    name: 'register',
    component: () => import('@/views/RegisterView.vue'),
    meta: { public: true, title: '注册' },
  },
  {
    path: '/',
    component: () => import('@/layouts/AppLayout.vue'),
    children: [
      { path: '', redirect: '/problems' },
      {
        path: 'problems',
        name: 'problems',
        component: () => import('@/views/ProblemListView.vue'),
        meta: { title: '题库' },
      },
      {
        path: 'problems/:id',
        name: 'problem-detail',
        component: () => import('@/views/ProblemDetailView.vue'),
        meta: { title: '题目详情' },
      },
      {
        path: 'submissions',
        name: 'submissions',
        component: () => import('@/views/SubmissionListView.vue'),
        meta: { title: '提交记录' },
      },
      {
        path: 'submissions/:id',
        name: 'submission-detail',
        component: () => import('@/views/SubmissionDetailView.vue'),
        meta: { title: '判题详情' },
      },
      {
        path: 'contests',
        name: 'contests',
        component: () => import('@/views/ContestListView.vue'),
        meta: { title: '竞赛' },
      },
      {
        path: 'contests/:id',
        name: 'contest-detail',
        component: () => import('@/views/ContestDetailView.vue'),
        meta: { title: '竞赛详情' },
      },
      {
        path: 'profile',
        name: 'profile',
        component: () => import('@/views/ProfileView.vue'),
        meta: { title: '个人中心' },
      },

      // ---------------- 教学侧（教师 / 管理员） ----------------
      {
        path: 'teacher/problems',
        name: 'teacher-problems',
        component: () => import('@/views/teacher/ProblemManageView.vue'),
        meta: { title: '题目管理', roles: [USER_TYPE.STAFF, USER_TYPE.TEACHER] },
      },
      {
        path: 'teacher/problems/new',
        name: 'teacher-problem-new',
        component: () => import('@/views/teacher/ProblemEditView.vue'),
        meta: { title: '新建题目', roles: [USER_TYPE.STAFF, USER_TYPE.TEACHER] },
      },
      {
        path: 'teacher/problems/:id/edit',
        name: 'teacher-problem-edit',
        component: () => import('@/views/teacher/ProblemEditView.vue'),
        meta: { title: '编辑题目', roles: [USER_TYPE.STAFF, USER_TYPE.TEACHER] },
      },
      {
        path: 'teacher/contests/new',
        name: 'teacher-contest-new',
        component: () => import('@/views/teacher/ContestCreateView.vue'),
        meta: { title: '创建竞赛', roles: [USER_TYPE.STAFF, USER_TYPE.TEACHER] },
      },
      {
        path: 'teacher/knowledge',
        name: 'teacher-knowledge',
        component: () => import('@/views/teacher/KnowledgeView.vue'),
        meta: { title: 'AI 知识库', roles: [USER_TYPE.STAFF, USER_TYPE.TEACHER] },
      },

      // ---------------- 管理侧（仅管理员） ----------------
      {
        path: 'admin/users',
        name: 'admin-users',
        component: () => import('@/views/admin/UserAdminView.vue'),
        meta: { title: '用户管理', roles: [USER_TYPE.STAFF] },
      },
      {
        path: 'admin/tags',
        name: 'admin-tags',
        component: () => import('@/views/admin/TagAdminView.vue'),
        meta: { title: '标签管理', roles: [USER_TYPE.STAFF] },
      },
      {
        path: 'admin/workers',
        name: 'admin-workers',
        component: () => import('@/views/admin/WorkerAdminView.vue'),
        meta: { title: '判题集群', roles: [USER_TYPE.STAFF] },
      },
      {
        path: 'admin/monitor',
        name: 'admin-monitor',
        component: () => import('@/views/admin/MonitorView.vue'),
        meta: { title: '系统监控', roles: [USER_TYPE.STAFF] },
      },

      {
        path: '403',
        name: 'forbidden',
        component: () => import('@/views/ErrorView.vue'),
        meta: { title: '无权访问', code: 403 },
      },
      {
        path: ':pathMatch(.*)*',
        name: 'not-found',
        component: () => import('@/views/ErrorView.vue'),
        meta: { title: '页面不存在', code: 404 },
      },
    ],
  },
];

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior(to, from, savedPosition) {
    // 浏览器前进/后退恢复位置，普通跳转回顶部
    return savedPosition || { top: 0 };
  },
});

router.beforeEach(async (to) => {
  const user = useUserStore();

  if (to.meta.public) {
    // 已登录还去登录页 → 直接回首页，避免"登录后按返回键又回到登录页"
    if (user.isLoggedIn && to.name === 'login') {
      return { path: '/problems' };
    }
    return true;
  }

  if (!user.isLoggedIn) {
    return { name: 'login', query: { redirect: to.fullPath } };
  }

  // 首次进入或刷新页面后补拉资料（loadProfile 内部幂等）
  if (!user.loaded) {
    try {
      await user.loadProfile();
    } catch {
      // 后端不可达：保留 token 与当前页面，由页面自身的错误提示告知用户，
      // 不在这里跳登录页 —— 把"服务挂了"伪装成"登录过期"是最难查的一类问题
    }
  }
  if (!user.isLoggedIn) {
    return { name: 'login', query: { redirect: to.fullPath } };
  }

  const required = to.meta.roles;
  if (Array.isArray(required) && required.length && !required.includes(user.type)) {
    return { name: 'forbidden' };
  }

  return true;
});

router.afterEach((to) => {
  const base = 'CodeJudge';
  document.title = to.meta?.title ? `${to.meta.title} · ${base}` : base;
});

export default router;
