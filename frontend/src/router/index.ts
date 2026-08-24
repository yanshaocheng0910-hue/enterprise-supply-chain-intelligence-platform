import { createRouter, createWebHistory, type RouteLocationNormalized, type RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import type { UserRole } from '@/types'

type RoleRouteMeta = { roles?: UserRole[]; public?: boolean }

const shellChildren: RouteRecordRaw[] = [
  { path: '', redirect: 'dashboard' },
  { path: 'dashboard', component: () => import('@/views/DashboardView.vue'), meta: { title: '总览', section: '工作台' } },
  { path: 'data-import', component: () => import('@/views/DataImportView.vue'), meta: { title: '数据导入', section: '数据与主档', roles: ['BUYER'] } },
  { path: 'admin-users', component: () => import('@/views/AdminUsersView.vue'), meta: { title: '用户与角色', section: '系统管理', roles: ['ADMIN'] } },
  { path: 'suppliers', component: () => import('@/views/SuppliersView.vue'), meta: { title: '供应商', section: '数据与主档', roles: ['BUYER', 'MANAGER'] } },
  { path: 'materials', component: () => import('@/views/MaterialsView.vue'), meta: { title: '物料', section: '数据与主档', roles: ['BUYER', 'MANAGER'] } },
  { path: 'warehouses', component: () => import('@/views/WarehousesView.vue'), meta: { title: '仓库', section: '数据与主档', roles: ['BUYER', 'MANAGER'] } },
  { path: 'inventory', component: () => import('@/views/InventoryView.vue'), meta: { title: '库存', section: '数据与主档', roles: ['BUYER', 'MANAGER'] } },
  { path: 'forecast', component: () => import('@/views/ForecastView.vue'), meta: { title: '14 天需求预测', section: '决策与预警', roles: ['BUYER', 'MANAGER'] } },
  { path: 'warnings', component: () => import('@/views/WarningsView.vue'), meta: { title: '预警中心', section: '决策与预警', roles: ['ADMIN', 'BUYER', 'MANAGER'] } },
  { path: 'purchase-demands', component: () => import('@/views/PurchaseDemandsView.vue'), meta: { title: '采购需求', section: '采购执行', roles: ['BUYER', 'MANAGER'] } },
  { path: 'purchase-plans', component: () => import('@/views/PurchasePlansView.vue'), meta: { title: '采购计划', section: '采购执行', roles: ['BUYER', 'MANAGER'] } },
  { path: 'orders', component: () => import('@/views/OrdersView.vue'), meta: { title: '采购订单', section: '采购执行', roles: ['BUYER', 'SUPPLIER', 'MANAGER'] } },
  { path: 'deliveries', component: () => import('@/views/DeliveriesView.vue'), meta: { title: '交付通知', section: '协同履约', roles: ['BUYER', 'SUPPLIER', 'MANAGER'] } },
  { path: 'receipts', component: () => import('@/views/ReceiptsView.vue'), meta: { title: '收货', section: '协同履约', roles: ['BUYER', 'SUPPLIER', 'MANAGER'] } },
  { path: 'reconciliation', component: () => import('@/views/ReconciliationView.vue'), meta: { title: '对账', section: '协同履约', roles: ['BUYER', 'SUPPLIER', 'MANAGER'] } },
  { path: 'ai-parse', component: () => import('@/views/AiParseView.vue'), meta: { title: 'AI 语义解析', section: '受控智能', roles: ['BUYER', 'SUPPLIER'] } },
  { path: 'audit', component: () => import('@/views/AuditView.vue'), meta: { title: '审计记录', section: '系统管理', roles: ['ADMIN', 'MANAGER'] } },
]

const routes: RouteRecordRaw[] = [
  { path: '/login', component: () => import('@/views/LoginView.vue'), meta: { public: true } },
  { path: '/', component: () => import('@/components/AppShell.vue'), children: shellChildren },
  { path: '/:pathMatch(.*)*', redirect: '/dashboard' },
]

const router = createRouter({ history: createWebHistory(), routes, scrollBehavior: () => ({ top: 0 }) })

function routeRoles(to: RouteLocationNormalized) {
  const guarded = to.matched.find((record) => Array.isArray((record.meta as RoleRouteMeta).roles))
  return guarded ? (guarded.meta as RoleRouteMeta).roles : undefined
}

router.beforeEach((to) => {
  const auth = useAuthStore()
  const isPublic = to.matched.some((record) => (record.meta as RoleRouteMeta).public)
  if (!isPublic && !auth.isAuthenticated) return { path: '/login', query: { redirect: to.fullPath } }
  if (to.path === '/login' && auth.isAuthenticated) return '/dashboard'
  const roles = routeRoles(to)
  if (roles && !roles.includes(auth.role)) return { path: '/dashboard', replace: true }
  return true
})

export default router
