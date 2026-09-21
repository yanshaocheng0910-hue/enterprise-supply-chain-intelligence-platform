<template>
  <div>
    <PageHeader title="采购订单" description="订单是计划审批后的执行单据；状态迁移由后端校验，供应商仅可访问绑定订单。">
      <template #actions><el-button :icon="Refresh" :loading="loading" @click="load">刷新订单</el-button><el-button v-if="auth.role === 'BUYER'" type="primary" :icon="DocumentAdd" @click="createOrder">生成订单</el-button></template>
    </PageHeader>
    <section class="surface order-table">
      <div class="toolbar"><el-input v-model="keyword" clearable placeholder="搜索订单号、供应商" :prefix-icon="Search" /><el-select v-model="statusFilter" clearable placeholder="全部状态" style="width: 155px"><el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" /></el-select><span class="toolbar-spacer"></span><span class="toolbar-meta">{{ filtered.length }} 笔订单</span></div>
      <div v-if="errorMessage" class="inline-error"><WarningFilled />{{ errorMessage }}</div>
      <div v-if="loading && !orders.length" class="table-loading"><div v-for="i in 4" :key="i" class="skeleton" style="height: 44px"></div></div>
      <div v-else-if="!filtered.length"><EmptyState title="暂无采购订单" :description="emptyDescription" :action-text="emptyActionText" @action="handleEmptyAction" /></div>
      <div v-else class="table-wrap"><el-table :data="filtered" stripe><el-table-column prop="orderNo" label="订单号" width="170" /><el-table-column prop="supplierName" label="供应商" min-width="185" /><el-table-column label="物料行" width="90" align="right"><template #default="scope"><span class="number">{{ scope.row.itemCount || '—' }}</span></template></el-table-column><el-table-column label="订单金额" width="140" align="right"><template #default="scope"><span class="number">{{ scope.row.totalAmount ? `¥${scope.row.totalAmount.toLocaleString('zh-CN', { minimumFractionDigits: 2 })}` : '—' }}</span></template></el-table-column><el-table-column prop="promisedDate" label="承诺交期" width="120" /><el-table-column label="状态" width="135"><template #default="scope"><StatusBadge :label="statusMeta(scope.row.status).label" :tone="statusMeta(scope.row.status).tone" /></template></el-table-column><el-table-column prop="sourcePlanNo" label="来源计划" width="150" /><el-table-column prop="updatedAt" label="更新时间" width="170" /><el-table-column label="操作" width="220" fixed="right"><template #default="scope"><el-button link type="primary" @click="showDetail(scope.row)">详情与追踪</el-button><el-button v-if="(scope.row.status === 'PENDING_CONFIRM' || scope.row.status === 'PENDING_CONFIRMATION') && auth.role === 'SUPPLIER'" link type="primary" @click="confirmOrder(scope.row)">确认</el-button><el-button v-if="scope.row.status === 'CONFIRMED' && auth.role === 'SUPPLIER'" link type="primary" @click="startFulfillment(scope.row)">开始履约</el-button></template></el-table-column></el-table></div>
    </section>

    <el-drawer v-model="detailVisible" title="订单详情与履约追踪" size="560px">
      <template v-if="selected">
        <div class="drawer-title"><div class="drawer-code">{{ selected.orderNo }}</div><h2>{{ selected.supplierName || '服务端未返回供应商' }}</h2><StatusBadge :label="statusMeta(selected.status).label" :tone="statusMeta(selected.status).tone" /></div>

        <section class="stage-section" aria-label="订单闭环阶段">
          <div class="section-kicker">闭环阶段</div>
          <div v-if="timeline" class="stage-grid">
            <div v-for="(stage, index) in timeline.stages" :key="stage.key" class="stage-item" :class="stage.status.toLowerCase()">
              <span>{{ index + 1 }}</span><strong>{{ stage.label }}</strong>
            </div>
          </div>
          <div v-else class="stage-skeleton skeleton"></div>
        </section>

        <dl class="drawer-list"><div><dt>物料行</dt><dd>{{ selected.itemCount ? `${selected.itemCount} 行` : '服务端未返回' }}</dd></div><div><dt>订单金额</dt><dd>{{ selected.totalAmount ? `¥${selected.totalAmount.toLocaleString('zh-CN', { minimumFractionDigits: 2 })}` : '服务端未返回' }}</dd></div><div><dt>承诺交期</dt><dd>{{ selected.promisedDate || '服务端未返回' }}</dd></div><div><dt>来源计划</dt><dd>{{ selected.sourcePlanNo || '服务端未返回' }}</dd></div></dl>
        <div v-if="(selected.status === 'PENDING_CONFIRM' || selected.status === 'PENDING_CONFIRMATION') && auth.role === 'SUPPLIER'" class="drawer-actions"><el-button type="primary" :loading="updating" @click="confirmOrder(selected)">确认订单</el-button></div>
        <div v-if="selected.status === 'CONFIRMED' && auth.role === 'SUPPLIER'" class="drawer-actions"><el-button type="primary" :loading="updating" @click="startFulfillment(selected)">开始履约</el-button></div>

        <section class="timeline-section">
          <div class="timeline-heading"><div><div class="section-kicker">TRACEABLE EVENTS</div><h3>履约时间线</h3></div><span v-if="timeline">{{ timeline.events.length }} 条证据</span></div>
          <div v-if="timelineError" class="inline-error"><WarningFilled />{{ timelineError }}</div>
          <div v-if="timelineLoading" class="timeline-loading"><div v-for="i in 4" :key="i" class="skeleton"></div></div>
          <div v-else-if="timeline" class="timeline-list">
            <article v-for="event in timeline.events" :key="event.eventKey" class="timeline-event">
              <span class="event-dot" :class="event.stage.toLowerCase()"></span>
              <div class="event-body">
                <div class="event-top"><strong>{{ event.title }}</strong><time>{{ dateTime(event.eventAt) }}</time></div>
                <p>{{ event.description }}</p>
                <div class="event-meta"><span>{{ sourceLabel(event.source) }}</span><span v-if="event.referenceNo">{{ event.referenceNo }}</span><span v-if="event.actorName">{{ event.actorName }} · {{ roleLabel(event.actorRole) }}</span><span v-if="event.beforeState || event.afterState">{{ event.beforeState || '—' }} → {{ event.afterState || '—' }}</span></div>
              </div>
            </article>
          </div>
        </section>
        <div class="inline-note"><strong>只读追踪：</strong>时间线来自业务记录、审计日志和库存流水；任何处理仍由原业务接口执行。</div>
      </template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { DocumentAdd, Refresh, Search, WarningFilled } from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'
import PageHeader from '@/components/PageHeader.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import EmptyState from '@/components/EmptyState.vue'
import { useAuthStore } from '@/stores/auth'
import { apiList, apiMutate, apiRequest, extractApiError } from '@/services/api'
import { demoOrders } from '@/services/demo'
import { mapOrder } from '@/services/mappers'
import type { OrderTimeline, PurchaseOrder } from '@/types'

const router = useRouter(); const auth = useAuthStore(); const orders = ref<PurchaseOrder[]>([]); const keyword = ref(''); const statusFilter = ref(''); const loading = ref(false); const updating = ref(false); const errorMessage = ref(''); const selected = ref<PurchaseOrder>(); const detailVisible = ref(false); const timeline = ref<OrderTimeline>(); const timelineLoading = ref(false); const timelineError = ref('')
const statusOptions = [{ value: 'PENDING_CONFIRM', label: '待供应商确认' }, { value: 'CONFIRMED', label: '已确认' }, { value: 'PENDING_SHIPMENT', label: '待发货' }, { value: 'SHIPPED', label: '已发货' }, { value: 'ARRIVED', label: '已到达' }, { value: 'PARTIALLY_RECEIVED', label: '部分收货' }, { value: 'RECEIVED', label: '已收货' }, { value: 'RECONCILING', label: '对账中' }, { value: 'COMPLETED', label: '已完成' }, { value: 'CANCELLED', label: '已取消' }]
const filtered = computed(() => orders.value.filter((item) => (!statusFilter.value || item.status === statusFilter.value) && (!keyword.value || `${item.orderNo}${item.supplierName}`.toLowerCase().includes(keyword.value.toLowerCase()))))
const hasFilters = computed(() => Boolean(keyword.value || statusFilter.value))
const emptyDescription = computed(() => hasFilters.value ? '当前筛选条件下没有匹配记录。' : auth.role === 'SUPPLIER' ? '当前绑定供应商还没有可见采购订单。' : '请先审批采购计划，再生成订单。')
const emptyActionText = computed(() => hasFilters.value ? '清空筛选' : auth.role === 'SUPPLIER' ? '重新加载' : '查看采购计划')
async function load() { loading.value = true; errorMessage.value = ''; try { const result = await apiList<PurchaseOrder>({ method: 'GET', url: '/purchase-orders' }, mapOrder, () => demoOrders); orders.value = result.records } catch (error) { errorMessage.value = extractApiError(error) } finally { loading.value = false } }
function handleEmptyAction() { if (hasFilters.value) { keyword.value = ''; statusFilter.value = '' } else if (auth.role === 'SUPPLIER') load(); else router.push('/purchase-plans') }
function createOrder() { ElMessage.info('订单生成需要已批准的采购计划，服务端会执行幂等检查。请先在采购计划中选择已批准计划。') }
async function showDetail(item: PurchaseOrder) { selected.value = item; timeline.value = undefined; timelineError.value = ''; detailVisible.value = true; await loadTimeline(item.id) }
async function loadTimeline(orderId: string) { timelineLoading.value = true; timelineError.value = ''; try { timeline.value = await apiRequest<OrderTimeline>({ method: 'GET', url: `/purchase-orders/${orderId}/timeline` }) } catch (error) { timelineError.value = extractApiError(error) } finally { timelineLoading.value = false } }
async function confirmOrder(item: PurchaseOrder) { updating.value = true; try { const raw = await apiMutate<unknown>({ method: 'POST', url: `/purchase-orders/${item.id}/actions`, data: { action: 'CONFIRM', expectedVersion: item.version ?? 0, note: '供应商确认订单' } }, () => ({ ...item, status: 'CONFIRMED', updatedAt: new Date().toLocaleString('zh-CN', { hour12: false }) })); const result = mapOrder(raw); orders.value = orders.value.map((entry) => entry.id === result.id ? result : entry); selected.value = result; await loadTimeline(result.id); ElMessage.success('订单确认已提交，并保留审计记录') } catch (error) { ElMessage.error(extractApiError(error)) } finally { updating.value = false } }
async function startFulfillment(item: PurchaseOrder) { updating.value = true; try { const raw = await apiMutate<unknown>({ method: 'POST', url: `/purchase-orders/${item.id}/actions`, data: { action: 'PREPARE_SHIPMENT', expectedVersion: item.version ?? 0, note: '供应商开始履约' } }, () => ({ ...item, status: 'PENDING_SHIPMENT', updatedAt: new Date().toLocaleString('zh-CN', { hour12: false }) })); const result = mapOrder(raw); orders.value = orders.value.map((entry) => entry.id === result.id ? result : entry); selected.value = result; await loadTimeline(result.id); ElMessage.success('订单已进入待发货履约阶段') } catch (error) { ElMessage.error(extractApiError(error)) } finally { updating.value = false } }
function statusMeta(value: PurchaseOrder['status']) { return ({ PENDING_CONFIRM: { label: '待确认', tone: 'warning' }, PENDING_CONFIRMATION: { label: '待供应商确认', tone: 'warning' }, CONFIRMED: { label: '已确认', tone: 'blue' }, PENDING_SHIPMENT: { label: '待发货', tone: 'warning' }, SHIPPED: { label: '已发货', tone: 'blue' }, ARRIVED: { label: '已到达', tone: 'success' }, PARTIAL: { label: '部分到货', tone: 'warning' }, PARTIALLY_RECEIVED: { label: '部分收货', tone: 'warning' }, DELIVERED: { label: '已到货', tone: 'success' }, RECEIVED: { label: '已收货', tone: 'success' }, RECONCILING: { label: '对账中', tone: 'warning' }, COMPLETED: { label: '已完成', tone: 'success' }, REJECTED: { label: '已拒绝', tone: 'danger' }, CLOSED: { label: '已关闭', tone: 'neutral' }, CANCELLED: { label: '已取消', tone: 'neutral' } } as Record<string, { label: string; tone: 'neutral' | 'warning' | 'success' | 'blue' | 'danger' }>)[value] || { label: value, tone: 'neutral' } }
function sourceLabel(value: string) { return ({ AUDIT_LOG: '审计日志', BUSINESS_RECORD: '业务记录', INVENTORY_TRANSACTION: '库存流水' } as Record<string, string>)[value] || value }
function roleLabel(value?: string) { return ({ BUYER: '采购', SUPPLIER: '供应商', MANAGER: '管理者', ADMIN: '管理员' } as Record<string, string>)[value || ''] || value || '系统' }
function dateTime(value?: string) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—' }
onMounted(load)
</script>

<style scoped>
.order-table { overflow: hidden; }.toolbar-meta { color: var(--lm-muted); font-size: 11px; }.number { font-variant-numeric: tabular-nums; }.drawer-title { padding-bottom: 18px; border-bottom: 1px solid var(--lm-border); }.drawer-code { color: var(--lm-olive); font-size: 11px; font-weight: 750; }.drawer-title h2 { margin: 8px 0 11px; font-size: 20px; }.drawer-list { margin: 5px 0 0; }.drawer-list > div { display: flex; justify-content: space-between; gap: 20px; padding: 12px 0; border-bottom: 1px solid var(--lm-border); }.drawer-list dt { color: var(--lm-muted); font-size: 12px; }.drawer-list dd { margin: 0; font-size: 12px; font-weight: 650; text-align: right; }.drawer-actions { margin-top: 18px; }.inline-note { margin-top: 20px; }.inline-note strong { color: var(--lm-ink-soft); }
.section-kicker { color: var(--lm-olive); font-size: 9px; font-weight: 750; letter-spacing: .12em; }.stage-section { padding: 18px 0 7px; border-bottom: 1px solid var(--lm-border); }.stage-grid { display: grid; grid-template-columns: repeat(3,1fr); gap: 7px; margin-top: 10px; }.stage-item { display: flex; align-items: center; gap: 7px; min-width: 0; padding: 8px; border: 1px solid var(--lm-border); color: var(--lm-muted); font-size: 10px; }.stage-item span { display: grid; place-items: center; width: 18px; height: 18px; flex: 0 0 auto; border: 1px solid var(--lm-faint); font-size: 8px; }.stage-item strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.stage-item.done { border-color: color-mix(in srgb,var(--lm-success) 38%,var(--lm-border)); color: var(--lm-ink-soft); }.stage-item.done span { border-color: var(--lm-success); color: var(--lm-success); }.stage-item.current { border-color: var(--lm-olive); background: var(--lm-olive-soft); color: var(--lm-olive-deep); }.stage-item.current span { border-color: var(--lm-olive); background: var(--lm-olive); color: #fff; }.stage-item.exception { border-color: var(--lm-danger); color: var(--lm-danger); }.stage-skeleton { height: 67px; margin-top: 10px; }
.timeline-section { padding-top: 24px; }.timeline-heading { display: flex; align-items: flex-end; justify-content: space-between; gap: 18px; margin-bottom: 16px; }.timeline-heading h3 { margin: 4px 0 0; font-size: 17px; }.timeline-heading > span { color: var(--lm-muted); font-size: 10px; }.timeline-loading { display: grid; gap: 9px; }.timeline-loading .skeleton { height: 68px; }.timeline-list { position: relative; padding-left: 8px; }.timeline-list::before { content: ''; position: absolute; top: 6px; bottom: 6px; left: 12px; width: 1px; background: var(--lm-border); }.timeline-event { position: relative; display: grid; grid-template-columns: 10px minmax(0,1fr); gap: 13px; padding: 0 0 20px; }.event-dot { z-index: 1; width: 9px; height: 9px; margin-top: 5px; border: 2px solid #fff; outline: 1px solid var(--lm-olive); border-radius: 50%; background: var(--lm-olive); }.event-dot.inventory { outline-color: var(--lm-blue); background: var(--lm-blue); }.event-dot.reconciliation { outline-color: var(--lm-warning); background: var(--lm-warning); }.event-body { min-width: 0; }.event-top { display: flex; justify-content: space-between; gap: 12px; }.event-top strong { font-size: 12px; }.event-top time { flex: 0 0 auto; color: var(--lm-muted); font-size: 9px; font-variant-numeric: tabular-nums; }.event-body p { margin: 5px 0 7px; color: var(--lm-ink-soft); font-size: 10px; line-height: 1.55; overflow-wrap: anywhere; }.event-meta { display: flex; flex-wrap: wrap; gap: 5px 10px; color: var(--lm-muted); font-size: 8px; }.event-meta span { padding: 2px 5px; background: var(--lm-canvas); }
:deep(.el-drawer) { max-width: 100vw; }
@media (max-width: 600px) { .stage-grid { grid-template-columns: repeat(2,1fr); }.event-top { display: grid; gap: 3px; }.event-top time { order: -1; }.timeline-event { gap: 10px; } }
</style>
