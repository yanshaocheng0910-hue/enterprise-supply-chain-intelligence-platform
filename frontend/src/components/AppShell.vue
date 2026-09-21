<template>
  <div class="app-shell">
    <aside class="app-sidebar" :class="{ 'is-open': sidebarOpen }">
      <div class="sidebar-brand">
        <div class="brand-mark">SCIC</div>
        <div>
          <div class="sidebar-brand-name">SCIC</div>
          <div class="sidebar-brand-sub">供应链数智化协同平台</div>
        </div>
        <button class="sidebar-close" aria-label="关闭导航" @click="sidebarOpen = false">
          <Close />
        </button>
      </div>

      <nav class="sidebar-nav" aria-label="主导航">
        <template v-for="group in visibleGroups" :key="group.label">
          <div class="nav-group-label">{{ group.label }}</div>
          <RouterLink v-for="item in group.items" :key="item.path" :to="item.path" class="nav-item" @click="sidebarOpen = false">
            <component :is="item.icon" class="nav-icon" />
            <span>{{ item.label }}</span>
            <span v-if="item.badge" class="nav-badge">{{ item.badge }}</span>
          </RouterLink>
        </template>
      </nav>

      <div class="sidebar-bottom">
        <div class="sidebar-system-note">
          <span class="system-dot" :class="{ demo: demoFallback }"></span>
          <div>
            <strong>{{ demoFallback ? '演示回退模式' : '后端连接模式' }}</strong>
            <small>{{ demoFallback ? '数据为明确标注的预览样例' : '数据来自 /api/v1' }}</small>
          </div>
        </div>
        <div class="sidebar-help">数据口径与状态以服务端返回为准，不宣称实时。</div>
      </div>
    </aside>

    <div v-if="sidebarOpen" class="sidebar-backdrop" @click="sidebarOpen = false"></div>

    <section class="app-content">
      <header class="app-topbar">
        <div class="topbar-left">
          <button class="menu-button" aria-label="打开导航" @click="sidebarOpen = true"><Menu /></button>
          <div class="breadcrumb"><span>{{ currentSection }}</span><ArrowRight /><strong>{{ currentTitle }}</strong></div>
        </div>
        <div class="topbar-right">
          <span v-if="demoFallback" class="mode-chip">DEMO FALLBACK</span>
          <el-dropdown trigger="click" @command="handleRoleChange">
            <button class="identity-button" aria-label="打开角色菜单">
              <span class="avatar">{{ avatarText }}</span>
              <span class="identity-copy"><strong>{{ auth.user?.displayName || '用户' }}</strong><small>{{ auth.roleInfo.label }}</small></span>
              <ArrowDown class="identity-arrow" />
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item v-for="role in availableRoles" :key="role.value" :command="role.value" :disabled="role.value === auth.role">
                  <span class="role-menu-row"><strong>{{ role.label }}</strong><small>{{ role.description }}</small></span>
                </el-dropdown-item>
                <el-dropdown-item divided command="logout"><span class="logout-item">退出当前账号</span></el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <div v-if="demoFallback" class="demo-banner" role="status">
        <InfoFilled />
        <span><strong>前端预览已启用演示回退。</strong> 当前展示的数据是本地样例，联调时请设置 <code>VITE_ENABLE_DEMO_FALLBACK=false</code>，所有请求失败会明确提示后端错误。</span>
      </div>
      <main class="page-main"><div class="page-container"><RouterView :key="`${auth.role}-${route.fullPath}`" /></div></main>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter, RouterLink, RouterView } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { demoModeState } from '@/services/api'
import type { UserRole } from '@/types'
import {
  ArrowDown, ArrowRight, Bell, Box, Calendar, Checked, Close, Coin, DataAnalysis, Document, Files, FolderOpened, Goods, House, InfoFilled,
  Menu, Operation, Setting, ShoppingBag, Switch, Tickets, TrendCharts, User, Van, Warning,
} from '@element-plus/icons-vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const sidebarOpen = ref(false)
const demoFallback = demoModeState

type NavItem = { label: string; path: string; icon: typeof House; badge?: string; roles?: UserRole[] }
type NavGroup = { label: string; items: NavItem[] }

const groups: NavGroup[] = [
  { label: '工作台', items: [{ label: '总览', path: '/dashboard', icon: House }, { label: '我的待办', path: '/work-queue', icon: Bell, roles: ['BUYER', 'SUPPLIER', 'MANAGER'] }, { label: '预警中心', path: '/warnings', icon: Warning, roles: ['ADMIN', 'BUYER', 'MANAGER'] }] },
  { label: '数据与主档', items: [{ label: '数据导入', path: '/data-import', icon: FolderOpened, roles: ['BUYER'] }, { label: '供应商', path: '/suppliers', icon: User, roles: ['BUYER', 'MANAGER'] }, { label: '物料', path: '/materials', icon: Box, roles: ['BUYER', 'MANAGER'] }, { label: '仓库', path: '/warehouses', icon: Box, roles: ['BUYER', 'MANAGER'] }, { label: '库存', path: '/inventory', icon: Goods, roles: ['BUYER', 'MANAGER'] }] },
  { label: '决策与预警', items: [{ label: '14 天需求预测', path: '/forecast', icon: TrendCharts, roles: ['BUYER', 'MANAGER'] }, { label: '采购情景推演', path: '/scenario-simulation', icon: Operation, roles: ['BUYER', 'MANAGER'] }] },
  { label: '采购执行', items: [{ label: '采购需求', path: '/purchase-demands', icon: Tickets, roles: ['BUYER', 'MANAGER'] }, { label: '采购计划', path: '/purchase-plans', icon: Calendar, roles: ['BUYER', 'MANAGER'] }, { label: '采购订单', path: '/orders', icon: ShoppingBag, roles: ['BUYER', 'SUPPLIER', 'MANAGER'] }] },
  { label: '协同履约', items: [{ label: '交付通知', path: '/deliveries', icon: Van, roles: ['BUYER', 'SUPPLIER', 'MANAGER'] }, { label: '收货', path: '/receipts', icon: Checked, roles: ['BUYER', 'SUPPLIER', 'MANAGER'] }, { label: '对账', path: '/reconciliation', icon: Coin, roles: ['BUYER', 'SUPPLIER', 'MANAGER'] }] },
  { label: '受控智能', items: [{ label: '经营分析报告', path: '/analysis-reports', icon: Files, roles: ['BUYER', 'MANAGER'] }, { label: 'AI 语义解析', path: '/ai-parse', icon: DataAnalysis, roles: ['BUYER', 'SUPPLIER'] }] },
  { label: '系统管理', items: [{ label: '用户与角色', path: '/admin-users', icon: Setting, roles: ['ADMIN'] }, { label: '审计记录', path: '/audit', icon: Document, roles: ['ADMIN', 'MANAGER'] }] },
]

const visibleGroups = computed(() => groups.map((group) => ({ ...group, items: group.items.filter((item) => !item.roles || item.roles.includes(auth.role)) })).filter((group) => group.items.length))
const currentTitle = computed(() => String(route.meta.title || '总览'))
const currentSection = computed(() => String(route.meta.section || '工作台'))
const avatarText = computed(() => (auth.user?.displayName || '用户').slice(0, 1))
const availableRoles = Object.entries({ ADMIN: '系统管理员', BUYER: '采购协同人员', SUPPLIER: '供应商', MANAGER: '企业管理人员' }).map(([value, label]) => ({ value: value as UserRole, label, description: ({ ADMIN: '维护权限、数据接入与审计', BUYER: '负责需求、订单、收货与对账', SUPPLIER: '查看订单并协同交付', MANAGER: '查看看板、风险与审批' } as Record<UserRole, string>)[value as UserRole] }))

function handleRoleChange(command: UserRole | 'logout') {
  if (command === 'logout') {
    auth.logout()
    router.push('/login')
    return
  }
  if (!auth.session?.token.startsWith('demo-')) {
    ElMessage.info('真实登录不会在浏览器内切换角色，请退出后使用对应账号重新登录。')
    return
  }
  auth.switchRole(command)
  if (!visibleGroups.value.some((group) => group.items.some((item) => item.path === route.path))) router.push('/dashboard')
}
</script>

<style scoped>
.app-sidebar { position: fixed; z-index: 30; inset: 0 auto 0 0; display: flex; flex-direction: column; width: var(--lm-sidebar); border-right: 1px solid var(--lm-border); background: #fff; }
.sidebar-brand { display: flex; align-items: center; gap: 10px; min-height: 70px; padding: 0 17px; border-bottom: 1px solid var(--lm-border); }
.sidebar-brand .brand-mark { width: 31px; height: 31px; background: var(--lm-olive-deep); font-size: 11px; }
.sidebar-brand-name { font-size: 16px; font-weight: 760; letter-spacing: -0.02em; }
.sidebar-brand-sub { margin-top: 3px; color: var(--lm-muted); font-size: 10px; }
.sidebar-close { display: none; margin-left: auto; border: 0; background: transparent; color: var(--lm-muted); }
.sidebar-nav { flex: 1; overflow-y: auto; padding: 14px 10px 18px; }
.nav-group-label { margin: 15px 8px 6px; color: var(--lm-faint); font-size: 10px; font-weight: 750; letter-spacing: 0.08em; }
.nav-item { display: flex; align-items: center; gap: 10px; min-height: 36px; margin: 2px 0; padding: 0 10px; border-left: 2px solid transparent; color: var(--lm-ink-soft); font-size: 12px; font-weight: 600; }
.nav-item:hover { background: var(--lm-surface); color: var(--lm-olive-deep); }
.nav-item.router-link-active { border-left-color: var(--lm-olive); background: var(--lm-olive-soft); color: var(--lm-olive-deep); font-weight: 720; }
.nav-icon { width: 16px; height: 16px; }
.nav-badge { display: inline-grid; place-items: center; min-width: 19px; height: 18px; margin-left: auto; padding: 0 5px; border: 1px solid oklch(69% 0.09 30); color: var(--lm-danger); font-size: 10px; line-height: 1; }
.sidebar-bottom { padding: 14px 15px 17px; border-top: 1px solid var(--lm-border); }
.sidebar-system-note { display: flex; align-items: flex-start; gap: 8px; }
.system-dot { width: 7px; height: 7px; margin-top: 4px; border-radius: 50%; background: var(--lm-success); }
.system-dot.demo { background: var(--lm-warning); }
.sidebar-system-note strong { display: block; color: var(--lm-ink-soft); font-size: 11px; }
.sidebar-system-note small { display: block; margin-top: 3px; color: var(--lm-muted); font-size: 10px; line-height: 1.4; }
.sidebar-help { margin-top: 11px; color: var(--lm-faint); font-size: 10px; line-height: 1.45; }
.app-content { min-width: 0; margin-left: var(--lm-sidebar); }
.app-topbar { position: sticky; z-index: 20; top: 0; display: flex; align-items: center; justify-content: space-between; min-height: 64px; padding: 0 30px; border-bottom: 1px solid var(--lm-border); background: oklch(99.2% 0.004 92 / 0.97); }
.topbar-left, .topbar-right, .breadcrumb, .identity-button { display: flex; align-items: center; }
.breadcrumb { gap: 8px; color: var(--lm-muted); font-size: 12px; }
.breadcrumb svg { width: 12px; }
.breadcrumb strong { color: var(--lm-ink); font-weight: 700; }
.menu-button { display: none; margin-right: 12px; border: 0; background: transparent; color: var(--lm-ink-soft); }
.topbar-right { gap: 17px; }
.mode-chip { padding: 4px 7px; border: 1px solid oklch(70% 0.1 70); color: var(--lm-warning); font-size: 9px; font-weight: 800; letter-spacing: 0.08em; }
.identity-button { gap: 8px; border: 0; background: transparent; text-align: left; }
.avatar { display: grid; place-items: center; width: 29px; height: 29px; background: var(--lm-olive-soft); color: var(--lm-olive-deep); font-size: 12px; font-weight: 750; }
.identity-copy { display: grid; gap: 2px; }
.identity-copy strong { font-size: 12px; }
.identity-copy small { color: var(--lm-muted); font-size: 10px; }
.identity-arrow { width: 13px; margin-left: 2px; color: var(--lm-muted); }
.role-menu-row { display: grid; gap: 3px; min-width: 170px; }
.role-menu-row strong { font-size: 12px; }
.role-menu-row small { color: var(--lm-muted); font-size: 10px; }
.logout-item { color: var(--lm-danger); font-size: 12px; }
.demo-banner { display: flex; align-items: flex-start; gap: 8px; margin: 15px 30px 0; padding: 10px 13px; border: 1px solid oklch(79% 0.08 70); background: var(--lm-warning-soft); color: oklch(37% 0.08 70); font-size: 11px; line-height: 1.5; }
.demo-banner svg { flex: 0 0 auto; width: 15px; margin-top: 1px; }
.demo-banner strong { font-weight: 750; }
.demo-banner code { padding: 1px 4px; border: 1px solid oklch(82% 0.07 70); font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 10px; }
.sidebar-backdrop { display: none; }
@media (max-width: 860px) {
  .app-sidebar { transform: translateX(-100%); transition: transform 160ms ease; box-shadow: var(--lm-shadow); }
  .app-sidebar.is-open { transform: translateX(0); }
  .sidebar-close, .menu-button { display: inline-flex; align-items: center; justify-content: center; }
  .sidebar-backdrop { position: fixed; z-index: 25; inset: 0; display: block; background: oklch(25% 0.03 92 / 0.28); }
  .app-content { margin-left: 0; }
  .app-topbar { padding-inline: 18px; }
  .demo-banner { margin-inline: 18px; }
  .identity-copy { display: none; }
}
@media (prefers-reduced-motion: reduce) { .app-sidebar { transition: none; } }
</style>
