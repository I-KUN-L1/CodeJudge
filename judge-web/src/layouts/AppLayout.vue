<template>
  <div class="layout">
    <!-- ================= 顶栏 ================= -->
    <header class="topbar">
      <div class="topbar__inner">
        <!-- 移动端汉堡按钮（桌面隐藏） -->
        <button class="icon-btn only-mobile" type="button" aria-label="打开导航" @click="drawer = true">
          <el-icon :size="20"><Menu /></el-icon>
        </button>

        <router-link to="/problems" class="brand">
          <span class="brand__mark">CJ</span>
          <span class="brand__text cj-hide-sm">CodeJudge</span>
        </router-link>

        <!-- 桌面端导航 -->
        <nav class="nav only-desktop">
          <router-link
            v-for="item in primaryNav"
            :key="item.key"
            :to="item.path"
            class="nav__link"
            :class="{ 'is-active': isActive(item) }"
          >
            {{ item.name }}
          </router-link>

          <el-dropdown v-if="manageNav.length" trigger="hover" popper-class="nav-dropdown">
            <span class="nav__link nav__link--drop">
              教学
              <el-icon :size="12"><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item v-for="item in manageNav" :key="item.key" @click="go(item.path)">
                  {{ item.name }}
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>

          <el-dropdown v-if="adminNav.length" trigger="hover" popper-class="nav-dropdown">
            <span class="nav__link nav__link--drop">
              系统
              <el-icon :size="12"><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item v-for="item in adminNav" :key="item.key" @click="go(item.path)">
                  {{ item.name }}
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </nav>

        <div class="cj-spacer" />

        <!-- 主题切换 -->
        <button class="icon-btn" type="button" :aria-label="theme.isDark ? '切换为浅色' : '切换为深色'" @click="theme.toggle()">
          <el-icon :size="18"><Moon v-if="theme.isDark" /><Sunny v-else /></el-icon>
        </button>

        <!-- 用户区 -->
        <template v-if="user.isLoggedIn">
          <el-dropdown trigger="click">
            <span class="user">
              <el-avatar :size="28" :src="user.profile?.icon || undefined">
                {{ (user.displayName || 'U').slice(0, 1) }}
              </el-avatar>
              <span class="user__meta cj-hide-sm">
                <span class="user__name">{{ user.displayName }}</span>
                <span class="user__role">{{ user.roleLabel }}</span>
              </span>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item @click="go('/profile')">个人中心</el-dropdown-item>
                <el-dropdown-item divided @click="onLogout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </template>
        <el-button v-else type="primary" size="small" @click="go('/login')">登录</el-button>
      </div>
    </header>

    <!-- 能力画像加载失败：受限入口会全部消失，必须给出可见原因。
         否则用户只会看到"按钮不见了"，而这类静默失效最容易排查成"权限被收回"。 -->
    <div v-if="user.permError" class="perm-warn" role="status">
      <el-icon :size="14"><WarningFilled /></el-icon>
      <span>权限信息加载失败，受限操作入口已隐藏 —— 请刷新页面重试</span>
    </div>

    <!-- 引导态提醒：首个管理员的初始凭据文件还没被消费掉。
         这条**必须挂在布局层**：登录页里那条同名提示会在登录成功的同一拍
         被 router.replace 连带卸载，实测用户根本看不到（见
         logs/tmp/verify-bootstrap.cjs 的记录）。不阻断登录，但每次进页面都提醒，
         直到改密成功（后端删除 .bootstrap-credentials）后本提示自行消失。 -->
    <div v-if="user.mustChangePassword" class="boot-warn" role="status">
      <el-icon :size="14"><WarningFilled /></el-icon>
      <span>
        当前仍是初始管理员口令（<code>.bootstrap-credentials</code> 尚未删除）——
        请到<router-link class="boot-warn__link" to="/profile">个人中心</router-link>修改密码
      </span>
    </div>

    <!-- ================= 移动端抽屉导航 ================= -->
    <el-drawer v-model="drawer" direction="ltr" size="248px" :with-header="false">
      <div class="drawer">
        <div class="drawer__brand">
          <span class="brand__mark">CJ</span>
          <span class="drawer__title">CodeJudge</span>
        </div>
        <router-link
          v-for="item in allNav"
          :key="item.key"
          :to="item.path"
          class="drawer__link"
          :class="{ 'is-active': isActive(item) }"
          @click="drawer = false"
        >
          <el-icon :size="16" class="drawer__icon"><component :is="item.icon" /></el-icon>
          {{ item.name }}
        </router-link>
      </div>
    </el-drawer>

    <!-- ================= 主区 ================= -->
    <main class="main">
      <router-view v-slot="{ Component }">
        <keep-alive :include="keepAliveViews">
          <component :is="Component" />
        </keep-alive>
      </router-view>
    </main>

    <footer class="footer">
      <span>CodeJudge · 分布式在线编程评测平台</span>
      <span class="cj-dim">
        网关 {{ apiBase }} · {{ theme.mode === 'dark' ? '深色' : '浅色' }}主题
      </span>
    </footer>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { useUserStore } from '@/stores/user';
import { useThemeStore } from '@/stores/theme';
import { API_BASE } from '@/api/base';

const route = useRoute();
const router = useRouter();
const user = useUserStore();
const theme = useThemeStore();

const drawer = ref(false);
const apiBase = API_BASE;

/** 题库列表页做 keep-alive：从详情页返回时保留筛选条件，是高频操作 */
const keepAliveViews = ['ProblemListView', 'ContestListView'];

/**
 * 导航项的**可见性由后端决定**：`user.menus` 是 `GET /accounts/me/capabilities`
 * 按能力码过滤后的结果，本组件只负责「按 group 分栏画出来」。
 * 因此这里不再出现 `if (user.canManage)` / `if (user.isAdmin)` 这类角色判定。
 *
 * 下方这张表只解决一件事：**高亮哪个 tab**（`/teacher/contests/new` 应当点亮「教学」
 * 而不是只有自身）。它纯属呈现层，与权限无关 —— 所以即便某个 key 对不上，
 * 也只是高亮不准，不会出现"有权限却没有入口"。
 */
const ACTIVE_PATTERNS = {
  problems: /^\/problems/,
  contests: /^\/contests/,
  submissions: /^\/submissions/,
  'problem-manage': /^\/teacher\/problems/,
  'contest-create': /^\/teacher\/contests/,
  knowledge: /^\/teacher\/knowledge/,
  'user-manage': /^\/admin\/users/,
  'tag-manage': /^\/admin\/tags/,
  'worker-cluster': /^\/admin\/workers/,
  monitor: /^\/admin\/monitor/,
};

function navOf(group) {
  return user.menus.filter((item) => item.group === group);
}

const primaryNav = computed(() => navOf('primary'));
const manageNav = computed(() => navOf('teach'));
const adminNav = computed(() => navOf('system'));

const allNav = computed(() => [...primaryNav.value, ...manageNav.value, ...adminNav.value]);

function isActive(item) {
  const target = ACTIVE_PATTERNS[item.key]
    || new RegExp(`^${item.path.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}`);
  return target.test(route.path);
}

function go(path) {
  router.push(path);
}

async function onLogout() {
  try {
    await ElMessageBox.confirm('确定要退出登录吗？', '退出登录', {
      confirmButtonText: '退出',
      cancelButtonText: '取消',
      type: 'warning',
    });
  } catch {
    return; // 用户取消
  }
  await user.logout();
  ElMessage.success('已退出登录');
  router.push({ name: 'login' });
}
</script>

<style scoped>
.layout {
  min-height: 100%;
  display: flex;
  flex-direction: column;
}

/* 顶栏：实底 + 一条刻线。不做毛玻璃 —— 半透明模糊在滚动内容上方
   会持续制造对比度抖动，且是本项目要清除的模板化痕迹之一 */
.topbar {
  position: sticky;
  top: 0;
  z-index: var(--z-sticky);
  background: var(--surface-1);
  border-bottom: 1px solid var(--line-1);
  height: var(--header-h);
}

.topbar__inner {
  max-width: var(--page-max);
  height: 100%;
  margin: 0 auto;
  padding: 0 var(--sp-4);
  display: flex;
  align-items: center;
  gap: var(--sp-3);
}

.brand {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  color: var(--fg);
  font-size: var(--fs-md);
  font-weight: var(--fw-semi);
  text-decoration: none;
  flex: 0 0 auto;
  align-self: stretch;
}
.brand:hover {
  text-decoration: none;
}
/* 品牌标记：硬边方块 + 等宽字，取代原来的圆角渐变块。
   渐变是这套视觉里最没有信息量的一件装饰，先去掉它 */
.brand__mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border-radius: var(--r-sm);
  background: var(--ink);
  color: var(--fg-on-ink);
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  font-weight: var(--fw-semi);
  letter-spacing: var(--ls-tight);
}
.brand__text {
  letter-spacing: var(--ls-tight);
}

.nav {
  display: flex;
  align-items: center;
  align-self: stretch;
  gap: var(--sp-1);
  margin-left: var(--sp-2);
}
.nav__link {
  position: relative;
  display: flex;
  align-items: center;
  padding: 0 var(--sp-3);
  color: var(--fg-2);
  font-size: var(--fs-sm);
  font-weight: var(--fw-medium);
  text-decoration: none;
  cursor: pointer;
  white-space: nowrap;
  transition: color var(--dur-1) var(--ease-out);
}
.nav__link:hover {
  color: var(--fg);
  text-decoration: none;
}
.nav__link:focus-visible {
  outline-offset: -2px;
}
/* 激活态用「底部刻线」而不是整块高亮底：前者是仪表的分段指示，
   后者是常见的 SaaS 药丸高亮。文字同时变色，不靠单一通道传达状态 */
.nav__link.is-active {
  color: var(--accent);
  font-weight: var(--fw-semi);
}
.nav__link.is-active::after {
  content: '';
  position: absolute;
  left: var(--sp-3);
  right: var(--sp-3);
  bottom: -1px;
  height: 2px;
  background: var(--accent);
}
.nav__link--drop {
  display: inline-flex;
  align-items: center;
  gap: var(--sp-1);
  outline: none;
  height: 100%;
}

.icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: none;
  border-radius: var(--r-sm);
  background: transparent;
  color: var(--fg-2);
  cursor: pointer;
  flex: 0 0 auto;
  transition: background-color var(--dur-1) var(--ease-out),
    color var(--dur-1) var(--ease-out);
}
.icon-btn:hover {
  background: var(--surface-2);
  color: var(--fg);
}

.user {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  cursor: pointer;
  outline: none;
  padding: 3px var(--sp-2);
  border-radius: var(--r-sm);
  transition: background-color var(--dur-1) var(--ease-out);
}
.user:hover {
  background: var(--surface-2);
}
.user__meta {
  display: flex;
  flex-direction: column;
  line-height: 1.2;
}
.user__name {
  font-size: var(--fs-xs);
  font-weight: var(--fw-semi);
  color: var(--fg);
}
/* 角色标签用等宽小字：它是元数据，不该和用户昵称争视觉重量 */
.user__role {
  font-family: var(--font-mono);
  font-size: 11px;
  letter-spacing: var(--ls-wide);
  color: var(--fg-3);
}

.main {
  flex: 1 1 auto;
  min-height: 0;
}

/* 顶栏下提示条（能力画像加载失败 / 引导期未改密）。
   两条都属于"不显式说明就会被误判"的状态，故共用一套样式，
   避免同一处视觉写两遍后各自漂移。 */
.perm-warn,
.boot-warn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--sp-2);
  padding: var(--sp-2) var(--sp-4);
  background: var(--surface-2);
  border-bottom: 1px solid var(--line-1);
  color: var(--tle);
  font-size: var(--fs-xs);
}
/* 凭据文件名按代码呈现：它是用户要去磁盘上找的东西，字形要能与正文区分 */
.boot-warn code {
  font-family: var(--font-mono);
  padding: 0 3px;
  border-radius: var(--r-sm);
  background: var(--surface-3);
}
.boot-warn__link {
  color: inherit;
  text-decoration: underline;
  text-underline-offset: 2px;
}

.footer {
  max-width: var(--page-max);
  width: 100%;
  margin: 0 auto;
  padding: var(--sp-5) var(--sp-4) var(--sp-6);
  display: flex;
  justify-content: space-between;
  gap: var(--sp-2);
  flex-wrap: wrap;
  color: var(--fg-3);
  font-size: var(--fs-xs);
  border-top: 1px solid var(--line-1);
}
.footer .cj-dim {
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
}

.drawer {
  display: flex;
  flex-direction: column;
  gap: 1px;
  padding: var(--sp-1);
}
.drawer__brand {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  padding: var(--sp-2) var(--sp-2) var(--sp-4);
  border-bottom: 1px solid var(--line-1);
  margin-bottom: var(--sp-2);
}
.drawer__title {
  font-weight: var(--fw-semi);
  letter-spacing: var(--ls-tight);
}
.drawer__link {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  padding: var(--sp-2) var(--sp-2);
  border-radius: var(--r-sm);
  color: var(--fg-2);
  text-decoration: none;
  font-size: var(--fs-sm);
  transition: background-color var(--dur-1) var(--ease-out),
    color var(--dur-1) var(--ease-out);
}
.drawer__link:hover {
  background: var(--surface-2);
  color: var(--fg);
  text-decoration: none;
}
.drawer__link.is-active {
  color: var(--accent);
  font-weight: var(--fw-semi);
}
.drawer__icon {
  color: inherit;
  flex: 0 0 auto;
}

.only-mobile {
  display: none;
}
.only-desktop {
  display: flex;
}

@media (max-width: 900px) {
  .only-mobile {
    display: inline-flex;
  }
  .only-desktop {
    display: none;
  }
  .topbar__inner {
    gap: var(--sp-2);
    padding: 0 var(--sp-3);
  }
}
</style>
