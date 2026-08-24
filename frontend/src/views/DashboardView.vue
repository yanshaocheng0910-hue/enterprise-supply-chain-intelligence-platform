<template>
  <div>
    <PageHeader title="总览" description="围绕采购闭环组织今天需要判断的事项。数据更新以服务端返回的同步时间为准。">
      <template #actions>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新数据</el-button>
        <el-button v-if="auth.role === 'BUYER'" type="primary" :icon="Plus" @click="router.push('/purchase-demands')">创建采购需求</el-button>
      </template>
    </PageHeader>

    <div v-if="errorMessage" class="inline-error" role="alert"><WarningFilled />{{ errorMessage }}</div>
    <template v-if="loading && !summary">
      <div class="metric-grid"><div v-for="item in 4" :key="item" class="metric-item"><div class="skeleton" style="width: 72px; height: 13px"></div><div class="skeleton" style="width: 110px; height: 31px; margin-top: 13px"></div><div class="skeleton" style="width: 170px; height: 12px; margin-top: 12px"></div></div></div>
      <div class="split-grid"><div class="surface surface-pad skeleton" style="height: 340px"></div><div class="surface surface-pad skeleton" style="height: 340px"></div></div>
    </template>
    <template v-else-if="summary">
      <section class="metric-grid" aria-label="关键指标">
        <article v-for="metric in summary.kpis" :key="metric.key" class="metric-item" :style="{ '--metric-color': metricColor(metric.tone) }">
          <div class="metric-label">{{ metric.label }}</div>
          <div class="metric-value"><span>{{ metric.value }}</span><span class="metric-unit">{{ metric.unit }}</span></div>
          <div class="metric-foot"><span :class="metric.trend && metric.trend > 0 ? 'trend-up' : metric.trend && metric.trend < 0 ? 'trend-down' : ''">{{ trendText(metric) }}</span><span>{{ metric.description }}</span></div>
        </article>
      </section>

      <div class="dashboard-grid">
        <div class="dashboard-stack">
          <section class="surface surface-pad">
            <div class="section-heading"><div><h2>未来 14 天需求总量</h2><p>预测曲线与已发生实际值，单位：件</p></div><StatusBadge label="预测窗口" tone="blue" /></div>
            <div v-if="summary.demandTrend.length" ref="chartRef" class="chart-box" aria-label="未来 14 天需求预测折线图"></div>
            <div v-else class="chart-empty">服务端当前未返回需求趋势序列，页面不根据汇总指标补造曲线。请进入“需求预测”查看可用的预测运行批次。</div>
            <div class="chart-caption">最近同步 {{ summary.lastSyncedAt || '未提供' }}。{{ summary.dataNotice || '预测为建议输入，不直接生成订单。' }}</div>
          </section>
          <section class="surface list-panel">
            <div class="section-heading"><div><h2>最近协同事件</h2><p>保留对象、操作者和发生时间</p></div><el-button link type="primary" @click="router.push('/audit')">查看审计记录</el-button></div>
            <div class="event-list"><div v-for="event in summary.recentEvents" :key="event.id" class="event-row"><div class="event-kind">{{ eventKind(event.kind) }}</div><div><div class="event-title">{{ event.title }}</div><div class="event-desc">{{ event.description }}</div></div><div class="event-meta">{{ event.occurredAt }}<br />{{ event.actor }}</div></div></div>
          </section>
        </div>
        <div class="dashboard-stack">
          <section class="surface list-panel">
            <div class="section-heading"><div><h2>需要处理</h2><p>按风险与截止时间排序</p></div><el-button link type="primary" @click="router.push('/warnings')">进入预警中心</el-button></div>
            <div class="task-list"><div v-for="task in summary.urgentTasks" :key="task.id" class="task-row"><span class="task-signal" :class="task.severity"></span><div><div class="task-title">{{ task.title }}</div><div class="task-desc">{{ task.description }}</div></div><div class="task-meta">{{ task.dueAt }}<br /><el-button link type="primary" @click="task.route && router.push(task.route)">处理</el-button></div></div></div>
          </section>
          <section class="surface surface-pad">
            <div class="section-heading"><div><h2>订单履约结构</h2><p>当前有效采购订单</p></div><el-button link type="primary" @click="router.push('/orders')">查看订单</el-button></div>
            <div ref="pieRef" class="chart-box chart-box--small" aria-label="订单履约结构环形图"></div>
          </section>
        </div>
      </div>
    </template>
    <EmptyState v-else title="暂时没有总览数据" description="后端未返回可展示的数据，请检查服务状态后重试。" action-text="重新加载" @action="load" />
  </div>
</template>

<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import * as echarts from 'echarts'
import { Plus, Refresh, WarningFilled } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import EmptyState from '@/components/EmptyState.vue'
import { apiRequest, extractApiError } from '@/services/api'
import { demoDashboard } from '@/services/demo'
import { mapDashboard } from '@/services/mappers'
import type { DashboardSummary, KpiMetric } from '@/types'

const router = useRouter()
const auth = useAuthStore()
const loading = ref(false)
const errorMessage = ref('')
const summary = ref<DashboardSummary | null>(null)
const chartRef = ref<HTMLElement>()
const pieRef = ref<HTMLElement>()
let chart: echarts.ECharts | undefined
let pie: echarts.ECharts | undefined

async function load() {
  loading.value = true
  errorMessage.value = ''
  try {
    const raw = await apiRequest<unknown>({ method: 'GET', url: '/dashboard/summary' }, () => demoDashboard)
    summary.value = mapDashboard(raw)
    await nextTick()
    drawCharts()
  } catch (error) {
    errorMessage.value = extractApiError(error)
  } finally {
    loading.value = false
  }
}

function drawCharts() {
  if (!summary.value || !pieRef.value) return
  chart?.dispose(); pie?.dispose()
  if (chartRef.value && summary.value.demandTrend.length) {
    chart = echarts.init(chartRef.value)
    chart.setOption({
    animation: false,
    tooltip: { trigger: 'axis' },
    legend: { right: 0, top: 0, textStyle: { color: '#6f736c', fontSize: 11 } },
    grid: { left: 18, right: 12, top: 38, bottom: 22, containLabel: true },
    xAxis: { type: 'category', boundaryGap: false, data: summary.value.demandTrend.map((item) => item.date), axisLine: { lineStyle: { color: '#dfe2dc' } }, axisLabel: { color: '#858a82', fontSize: 10 } },
    yAxis: { type: 'value', splitNumber: 4, axisLabel: { color: '#858a82', fontSize: 10 }, splitLine: { lineStyle: { color: '#edf0eb' } } },
    series: [
      { name: '预测', type: 'line', smooth: 0.2, symbol: 'none', data: summary.value.demandTrend.map((item) => item.forecast), lineStyle: { width: 2, color: '#66783b' }, itemStyle: { color: '#66783b' }, areaStyle: { color: 'rgba(102,120,59,0.07)' } },
      { name: '实际', type: 'line', smooth: 0.2, connectNulls: false, symbol: 'circle', symbolSize: 5, data: summary.value.demandTrend.map((item) => item.actual ?? null), lineStyle: { width: 1.5, color: '#315e89', type: 'dashed' }, itemStyle: { color: '#315e89' } },
    ],
    })
  }
  pie = echarts.init(pieRef.value)
  pie.setOption({
    animation: false,
    tooltip: { trigger: 'item' },
    legend: { bottom: 0, left: 'center', textStyle: { color: '#6f736c', fontSize: 10 } },
    series: [{ type: 'pie', radius: ['44%', '68%'], center: ['50%', '43%'], label: { show: false }, data: summary.value.fulfilment.map((item, index) => ({ ...item, itemStyle: { color: ['#66783b', '#315e89', '#c28d34', '#b44f3d'][index] } })) }],
  })
}

function metricColor(tone?: KpiMetric['tone']) { return tone === 'blue' ? 'var(--lm-blue)' : tone === 'warning' ? 'var(--lm-warning)' : tone === 'danger' ? 'var(--lm-danger)' : 'var(--lm-olive)' }
function trendText(metric: KpiMetric) { return metric.trend === undefined ? '暂无趋势' : `${metric.trend > 0 ? '↑' : metric.trend < 0 ? '↓' : '→'} ${Math.abs(metric.trend)}% ${metric.trendLabel || ''}` }
function eventKind(kind: string) { return ({ forecast: '预', delivery: '运', approval: '审', import: '入' } as Record<string, string>)[kind] || '记' }
function resizeCharts() { chart?.resize(); pie?.resize() }

onMounted(() => { load(); window.addEventListener('resize', resizeCharts) })
onBeforeUnmount(() => { chart?.dispose(); pie?.dispose(); window.removeEventListener('resize', resizeCharts) })
</script>

<style scoped>
.chart-caption { color: var(--lm-faint); font-size: 11px; }
.chart-box--small { height: 230px; }
</style>
