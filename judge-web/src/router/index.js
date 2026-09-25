import { createRouter, createWebHistory } from 'vue-router';
import { useUserStore } from '@/stores/user';

/**
 * 路由表
 *
 * ── meta 约定 ─────────────────────────────────────────────────────────────
 *   public : true      匿名可达（仅登录 / 注册 / 错误页）
 *   perm   : string    打开该页所需的**能力码**（后端下发），如 'problem:manage'
 *   title  : string    浏览器标题与面包屑
 *
 * ── 关于「前端不参与鉴权」的边界 ────────────────────────────────────────────
 * 本文件里**没有任何角色语义**：不再有 `roles: [1, 3]` 这种写法，也没有
 * `user.type === 1` 这类判定。`meta.perm` 里写的是能力码，它由后端
 * （`GET /accounts/me/capabilities`）下发，前端只做「有码就放行、没码就去 403」的翻译。
 *
 * 于是「谁能进哪个页面」这件事只有一个真相来源：后端的能力码表。新增角色或调整
 * 权限时改的都是后端，本文件一行都不用动。
 *
 * 保留的最小判定只有**登录态**（`isLoggedIn`）—— 那不是鉴权，是导航态：
 * 没有 token 时任何业务接口都会 401，先跳登录页只是省掉一次必然失败的请求。
 *
 * 后端仍会在接口层拒绝越权调用（`@RequireRole` + 归属校验），被拒绝时
 * `api/http.js` 会统一把用户带到 403 页，这是本层的兜底。
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
      // 站点根路径固定进题库：所有人（含管理员）都有 problem:view，
      // 而"登录后的落地页"是另一件事 —— 那个由后端 capabilities.home 决定，见 LoginView。
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

      // ---------------- 教学侧（能力码见后端 Capabilities） ----------------
      {
        path: 'teacher/problems',
        name: 'teacher-problems',
        component: () => import('@/views/teacher/ProblemManageView.vue'),
        meta: { title: '题目管理', perm: 'problem:manage' },
      },
      {
        path: 'teacher/problems/new',
        name: 'teacher-problem-new',
        component: () => import('@/views/teacher/ProblemEditView.vue'),
        meta: { title: '新建题目', perm: 'problem:create' },
      },
      {
        path: 'teacher/problems/:id/edit',
        name: 'teacher-problem-edit',
        component: () => import('@/views/teacher/ProblemEditView.vue'),
        meta: { title: '编辑题目', perm: 'problem:edit' },
      },
      {
        path: 'teacher/contests/new',
        name: 'teacher-contest-new',
        component: () => import('@/views/teacher/ContestCreateView.vue'),
        meta: { title: '创建竞赛', perm: 'contest:create' },
      },
      {
        path: 'teacher/knowledge',
        name: 'teacher-knowledge',
        component: () => import('@/views/teacher/KnowledgeView.vue'),
        meta: { title: 'AI 知识库', perm: 'knowledge:manage' },
      },

      // ---------------- 管理侧 ----------------
      {
        path: 'admin/users',
        name: 'admin-users',
        component: () => import('@/views/admin/UserAdminView.vue'),
        meta: { title: '用户管理', perm: 'user:manage' },
      },
      {
        path: 'admin/tags',
        name: 'admin-tags',
        component: () => import('@/views/admin/TagAdminView.vue'),
        meta: { title: '标签管理', perm: 'tag:manage' },
      },
      {
        path: 'admin/workers',
        name: 'admin-workers',
        component: () => import('@/views/admin/WorkerAdminView.vue'),
        meta: { title: '判题集群', perm: 'worker:view' },
      },
      {
        path: 'admin/monitor',
        name: 'admin-monitor',
        component: () => import('@/views/admin/MonitorView.vue'),
        meta: { title: '系统监控', perm: 'monitor:view' },
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
    // 已登录还去登录页 → 回该角色的工作台（落地页同样由后端给，前端不写死）
    if (user.isLoggedIn && to.name === 'login') {
      return { path: user.home };
    }
    return true;
  }

  if (!user.isLoggedIn) {
    return { name: 'login', query: { redirect: to.fullPath } };
  }

  // 首次进入或刷新页面后补拉资料与能力画像（loadProfile 内部幂等）。
  // 这一步必须在能力判定**之前**完成，否则 `can()` 会因能力码尚未到手而全判 false。
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

  // 能力门槛：码由后端起，前端只翻译「有/没有」。
  // 能力画像加载失败时 can() 恒 false ⇒ 受限页面进不去，这是 fail-closed 的正确表现
  // （顶栏会同时给出「权限信息加载失败」的提示，避免看起来像权限被收回）。
  const required = to.meta.perm;
  if (required && !user.can(required)) {
    return { name: 'forbidden' };
  }

  return true;
});

router.afterEach((to) => {
  const base = 'CodeJudge';
  document.title = to.meta?.title ? `${to.meta.title} · ${base}` : base;
});

export default router;
