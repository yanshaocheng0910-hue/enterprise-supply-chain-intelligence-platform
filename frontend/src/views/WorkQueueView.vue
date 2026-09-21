<template>
  <div>
    <PageHeader eyebrow="ROLE WORK QUEUE" title="我的待办" description="按当前账号职责汇总跨模块待处理事项；处理动作仍回到原业务单据完成。">
      <template #actions><el-button :loading="loading" @click="loadQueue">刷新待办</el-button></template>
    </PageHeader>

    <div v-if="errorMessage" class="inline-error"><WarningFilled />{{ errorMessage }}</div>

    <section v-if="queue" class="queue-summary surface" aria-label="待办摘要">
      <div class="summary-main"><span>{{ roleLabel(queue.role) }}</span><strong>{{ queue.summary.total }}</strong><small>当前待办</small></div>
      <div class="summary-item urgent"><span>优先处理</span><strong>{{ queue.summary.urgent }}</strong><small>严重或高优先级</small></div>
      <div class="summary-item"><span>业务类别</span><strong>{{ typeOptions.length }}</strong><small>由现有状态实时汇总</small></div>
      <div class="summary-time"><span>生成时间</span><strong>{{ dateTime(queue.generatedAt) }}</strong><small>刷新后重新计算，不复制业务状态</small></div>
    </section>

    <section class="surface queue-panel">
      <div class="queue-toolbar">
        <div class="filter-group" aria-label="待办筛选">
          <button :class="{ active: filter === 'ALL' }" type="button" @click="filter = 'ALL'">全部 <span>{{ queue?.summary.total || 0 }}</span></button>
          <button :class="{ active: filter === 'URGENT' }" type="button" @click="filter = 'URGENT'">优先 <span>{{ queue?.summary.urgent || 0 }}</span></button>
          <button v-for="option in typeOptions" :key="option.value" :class="{ active: filter === option.value }" type="button" @click="filter = option.value">{{ option.label }} <span>{{ option.count }}</span></button>
        </div>
        <span class="result-count">显示 {{ filteredTasks.length }} 项</span>
      </div>

      <div v-if="loading && !queue" class="queue-loading"><div v-for="item in 5" :key="item" class="skeleton"></div></div>
      <EmptyState v-else-if="!filteredTasks.length" title="当前筛选下没有待办" description="这里不会制造新的流程状态；事项完成后会随原单据状态自动移出。" />
      <div v-else class="task-list">
        <article v-for="task in filteredTasks" :key="task.taskKey" class="task-row">
          <span class="severity-line" :class="task.severity.toLowerCase()"></span>
          <div class="task-copy">
            <div class="task-title"><StatusBadge :label="severityLabel(task.severity)" :tone="severityTone(task.severity)" /><h2>{{ task.title }}</h2></div>
            <p>{{ task.description }}</p>
            <div class="task-meta"><span>{{ typeLabel(task.taskType) }}</span><span>{{ task.targetType }} {{ task.referenceNo }}</span><span v-if="task.dueAt">期限 {{ dateOnly(task.dueAt) }}</span><span>记录于 {{ dateTime(task.createdAt) }}</span></div>
          </div>
          <el-button class="task-action" @click="router.push(task.route)">{{ task.actionLabel }}<ArrowRight /></el-button>
        </article>
      </div>
    </section>

    <div class="queue-boundary"><InfoFilled /><span><strong>待办中心是只读投影。</strong> 它不改变审批链和单据状态，也不代替原模块的权限、版本与幂等校验。</span></div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowRight, InfoFilled, WarningFilled } from '@element-plus/icons-vue'
import EmptyState from '@/components/EmptyState.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { apiRequest, extractApiError } from '@/services/api'
import type { UserRole, WorkQueue, WorkQueueTask } from '@/types'

const router = useRouter()
const queue = ref<WorkQueue>()
const loading = ref(false)
const errorMessage = ref('')
const filter = ref('ALL')
const typeOptions = computed(() => Object.entries(queue.value?.summary.byType || {}).map(([value, count]) => ({ value, count, label: typeLabel(value) })))
const filteredTasks = computed(() => {
  const tasks = queue.value?.tasks || []
  if (filter.value === 'ALL') return tasks
  if (filter.value === 'URGENT') return tasks.filter((task) => ['CRITICAL', 'HIGH'].includes(task.severity))
  return tasks.filter((task) => task.taskType === filter.value)
})

async function loadQueue() {
  loading.value = true; errorMessage.value = ''
  try {
    queue.value = await apiRequest<WorkQueue>({ method: 'GET', url: '/work-queue' })
    if (filter.value !== 'ALL' && filter.value !== 'URGENT' && !queue.value.summary.byType[filter.value]) filter.value = 'ALL'
  } catch (error) { errorMessage.value = extractApiError(error) }
  finally { loading.value = false }
}
function roleLabel(role: UserRole) { return ({ BUYER: '采购协同', SUPPLIER: '供应商协同', MANAGER: '管理审批', ADMIN: '系统管理' } as Record<UserRole, string>)[role] }
function typeLabel(type: string) { return ({ WARNING_ACTION: '风险预警', DEMAND_PLANNING: '需求计划', PLAN_SUBMISSION: '计划提交', PLAN_APPROVAL: '计划审批', ORDER_CONFIRMATION: '订单确认', DELIVERY_NOTICE: '交付通知', ORDER_OVERDUE: '逾期订单', RECEIPT_PENDING: '收货验收', RECONCILIATION_CONFIRM: '对账确认', RECONCILIATION_DISPUTE: '对账争议' } as Record<string, string>)[type] || type }
function severityLabel(level: string) { return ({ CRITICAL: '严重', HIGH: '优先', MEDIUM: '常规', LOW: '关注' } as Record<string, string>)[level] || level }
function severityTone(level: string): 'danger' | 'warning' | 'blue' | 'neutral' { return level === 'CRITICAL' ? 'danger' : level === 'HIGH' ? 'warning' : level === 'MEDIUM' ? 'blue' : 'neutral' }
function dateOnly(value?: string) { return value ? new Date(value).toLocaleDateString('zh-CN') : '—' }
function dateTime(value?: string) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—' }
onMounted(loadQueue)
</script>

<style scoped>
.queue-summary { display: grid; grid-template-columns: 1fr 1fr 1fr 1.45fr; margin-bottom: 14px; }
.queue-summary > div { min-width: 0; padding: 17px 19px; border-right: 1px solid var(--lm-border); }.queue-summary > div:last-child { border-right: 0; }
.queue-summary span,.queue-summary small { display: block; color: var(--lm-muted); font-size: 10px; }.queue-summary strong { display: block; margin: 6px 0 5px; font-size: 23px; font-weight: 720; font-variant-numeric: tabular-nums; }.summary-main { border-top: 3px solid var(--lm-olive); }.summary-item.urgent strong { color: var(--lm-danger); }.summary-time strong { font-size: 14px; }
.queue-panel { overflow: hidden; }.queue-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 18px; padding: 13px 16px; border-bottom: 1px solid var(--lm-border); }.filter-group { display: flex; gap: 6px; flex-wrap: wrap; }.filter-group button { padding: 6px 9px; border: 1px solid var(--lm-border); background: #fff; color: var(--lm-ink-soft); font-size: 10px; }.filter-group button span { margin-left: 3px; color: var(--lm-muted); }.filter-group button:hover,.filter-group button.active { border-color: var(--lm-olive); background: var(--lm-olive-soft); color: var(--lm-olive-deep); }.result-count { flex: 0 0 auto; color: var(--lm-muted); font-size: 10px; }
.task-list { padding: 0 18px; }.task-row { position: relative; display: grid; grid-template-columns: minmax(0,1fr) auto; gap: 18px; align-items: center; padding: 17px 3px 17px 14px; border-bottom: 1px solid var(--lm-border); }.task-row:last-child { border-bottom: 0; }.severity-line { position: absolute; top: 17px; bottom: 17px; left: 0; width: 3px; background: var(--lm-faint); }.severity-line.high { background: var(--lm-warning); }.severity-line.critical { background: var(--lm-danger); }.severity-line.medium { background: var(--lm-blue); }
.task-copy { min-width: 0; }.task-title { display: flex; align-items: center; gap: 10px; }.task-title h2 { margin: 0; font-size: 13px; font-weight: 680; }.task-copy p { margin: 7px 0; color: var(--lm-ink-soft); font-size: 11px; line-height: 1.55; }.task-meta { display: flex; gap: 7px 13px; flex-wrap: wrap; color: var(--lm-muted); font-size: 9px; }.task-meta span:not(:last-child)::after { content: ''; display: inline-block; width: 2px; height: 2px; margin: 0 0 2px 13px; border-radius: 50%; background: var(--lm-faint); }.task-action :deep(svg) { width: 12px; margin-left: 5px; }
.queue-loading { display: grid; gap: 10px; padding: 20px; }.queue-loading .skeleton { height: 76px; }.queue-boundary { display: flex; align-items: flex-start; gap: 8px; margin-top: 12px; color: var(--lm-muted); font-size: 10px; line-height: 1.55; }.queue-boundary svg { width: 14px; flex: 0 0 auto; }.queue-boundary strong { color: var(--lm-ink-soft); }
@media (max-width: 850px) { .queue-summary { grid-template-columns: repeat(2,1fr); }.queue-summary > div:nth-child(2) { border-right: 0; }.queue-summary > div:nth-child(-n+2) { border-bottom: 1px solid var(--lm-border); } }
@media (max-width: 600px) { .queue-toolbar { align-items: flex-start; }.result-count { padding-top: 6px; }.task-list { padding-inline: 14px; }.task-row { grid-template-columns: 1fr; gap: 12px; }.task-action { justify-self: start; }.task-meta span:not(:last-child)::after { display: none; }.task-meta { display: grid; gap: 4px; }.queue-summary > div { padding: 14px; }.queue-summary strong { font-size: 20px; }.summary-time strong { font-size: 12px; } }
</style>
