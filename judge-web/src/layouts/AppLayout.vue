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
            :key="item.path"
            :to="item.path"
            class="nav__link"
            :class="{ 'is-active': isActive(item) }"
          >
            {{ item.label }}
          </router-link>

          <el-dropdown v-if="manageNav.length" trigger="hover" popper-class="nav-dropdown">
            <span class="nav__link nav__link--drop">
              教学
              <el-icon :size="12"><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item v-for="item in manageNav" :key="item.path" @click="go(item.path)">
                  {{ item.label }}
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
                <el-dropdown-item v-for="item in adminNav" :key="item.path" @click="go(item.path)">
                  {{ item.label }}
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
                <span class="user__role">{{ user.typeLabel }}</span>
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

    <!-- ================= 移动端抽屉导航 ================= -->
    <el-drawer v-model="drawer" direction="ltr" size="248px" :with-header="false">
      <div class="drawer">
        <div class="drawer__brand">
          <span class="brand__mark">CJ</span>
          <span class="drawer__title">CodeJudge</span>
        </div>
        <router-link
          v-for="item in allNav"
          :key="item.path"
          :to="item.path"
          class="drawer__link"
          :class="{ 'is-active': isActive(item) }"
          @click="drawer = false"
        >
          <el-icon :size="16" class="drawer__icon"><component :is="item.icon" /></el-icon>
          {{ item.label }}
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
import { useUserStore, USER_TYPE } from '@/stores/user';
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

const primaryNav = computed(() => [
  { path: '/problems', label: '题库', icon: 'Notebook', match: /^\/problems/ },
  { path: '/contests', label: '竞赛', icon: 'Trophy', match: /^\/contests/ },
  { path: '/submissions', label: '提交记录', icon: 'Tickets', match: /^\/submissions/ },
]);

const manageNav = computed(() => {
  if (!user.canManage) return [];
  return [
    { path: '/teacher/problems', label: '题目管理', icon: 'EditPen', match: /^\/teacher\/problems/ },
    { path: '/teacher/contests/new', label: '创建竞赛', icon: 'CirclePlus', match: /^\/teacher\/contests/ },
    { path: '/teacher/knowledge', label: 'AI 知识库', icon: 'Collection', match: /^\/teacher\/knowledge/ },
  ];
});

const adminNav = computed(() => {
  if (!user.isAdmin) return [];
  return [
    { path: '/admin/users', label: '用户管理', icon: 'User', match: /^\/admin\/users/ },
    { path: '/admin/tags', label: '标签管理', icon: 'PriceTag', match: /^\/admin\/tags/ },
    { path: '/admin/workers', label: '判题集群', icon: 'Cpu', match: /^\/admin\/workers/ },
    { path: '/admin/monitor', label: '系统监控', icon: 'Odometer', match: /^\/admin\/monitor/ },
  ];
});

const allNav = computed(() => [...primaryNav.value, ...manageNav.value, ...adminNav.value]);

function isActive(item) {
  const target = item.match || new RegExp(`^${item.path}`);
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

// USER_TYPE 在模板里用不到，但保留 import 以便后续扩展角色判断；显式引用避免 lint 报未使用
void USER_TYPE;
</script>

<style scoped>
.layout {
  min-height: 100%;
  display: flex;
  flex-direction: column;
}

.topbar {
  position: sticky;
  top: 0;
  z-index: 100;
  background: color-mix(in srgb, var(--cj-panel) 88%, transparent);
  backdrop-filter: blur(10px);
  border-bottom: 1px solid var(--cj-border);
  height: var(--cj-header-h);
}

.topbar__inner {
  max-width: 1320px;
  height: 100%;
  margin: 0 auto;
  padding: 0 16px;
  display: flex;
  align-items: center;
  gap: 14px;
}

.brand {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--cj-text);
  font-weight: 700;
  font-size: 16px;
  text-decoration: none;
  flex: 0 0 auto;
}
.brand:hover {
  text-decoration: none;
}
.brand__mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: 8px;
  background: linear-gradient(135deg, #3b6ef6, #7c4dff);
  color: #fff;
  font-size: 13px;
  letter-spacing: 0.5px;
}

.nav {
  display: flex;
  align-items: center;
  gap: 2px;
  margin-left: 6px;
}
.nav__link {
  padding: 6px 12px;
  border-radius: 8px;
  color: var(--cj-text-sub);
  font-size: 14px;
  text-decoration: none;
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
  white-space: nowrap;
}
.nav__link:hover {
  background: var(--cj-panel-2);
  color: var(--cj-text);
  text-decoration: none;
}
.nav__link.is-active {
  color: var(--cj-accent);
  background: color-mix(in srgb, var(--cj-accent) 12%, transparent);
  font-weight: 600;
}
.nav__link--drop {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  outline: none;
}

.icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--cj-text-sub);
  cursor: pointer;
  flex: 0 0 auto;
}
.icon-btn:hover {
  background: var(--cj-panel-2);
  color: var(--cj-text);
}

.user {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  outline: none;
  padding: 3px 6px;
  border-radius: 8px;
}
.user:hover {
  background: var(--cj-panel-2);
}
.user__meta {
  display: flex;
  flex-direction: column;
  line-height: 1.15;
}
.user__name {
  font-size: 13px;
  font-weight: 600;
}
.user__role {
  font-size: 11px;
  color: var(--cj-text-dim);
}

.main {
  flex: 1 1 auto;
  min-height: 0;
}

.footer {
  max-width: 1320px;
  width: 100%;
  margin: 0 auto;
  padding: 18px 16px 26px;
  display: flex;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
  color: var(--cj-text-sub);
  font-size: 12px;
}

.drawer {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 4px;
}
.drawer__brand {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px 14px;
}
.drawer__title {
  font-weight: 700;
}
.drawer__link {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 10px 10px;
  border-radius: 8px;
  color: var(--cj-text-sub);
  text-decoration: none;
  font-size: 14px;
}
.drawer__link:hover {
  background: var(--cj-panel-2);
  text-decoration: none;
}
.drawer__link.is-active {
  color: var(--cj-accent);
  background: color-mix(in srgb, var(--cj-accent) 12%, transparent);
  font-weight: 600;
}
.drawer__icon {
  color: inherit;
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
    gap: 8px;
    padding: 0 12px;
  }
}
</style>
