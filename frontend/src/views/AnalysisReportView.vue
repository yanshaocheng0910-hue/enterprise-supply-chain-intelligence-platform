<template>
  <div>
    <PageHeader eyebrow="LOCAL AI · READ ONLY" title="供应链经营分析报告" description="以业务库统计快照为依据生成只读报告；保留模型、规则降级、数据指纹和生成记录。">
      <template #actions>
        <el-button :loading="generating" type="primary" @click="generateReport">{{ generating ? '正在生成' : '生成新报告' }}</el-button>
        <el-button :loading="loading" @click="loadReports">刷新记录</el-button>
      </template>
    </PageHeader>

    <div class="governance-note" role="note">
      <Lock />
      <div><strong>数据不离开本机服务边界</strong><span>AI 服务不持有数据库账号，只接收后端汇总的统计快照；报告不会自动审批、下单、入库或确认对账。</span></div>
    </div>

    <div v-if="errorMessage" class="inline-error"><WarningFilled />{{ errorMessage }}</div>

    <div v-if="loading && !report" class="report-skeleton">
      <div class="skeleton"></div><div class="skeleton"></div><div class="skeleton wide"></div>
    </div>
    <EmptyState v-else-if="!report" title="还没有经营分析报告" description="生成后会冻结当时的统计事实，并记录模型或规则降级依据。" action-text="生成第一份报告" @action="generateReport" />

    <template v-else>
      <section class="report-identity surface">
        <div>
          <span class="report-no">{{ report.reportNo }}</span>
          <h2>{{ report.sections[0]?.content }}</h2>
          <p>统计时点 {{ dateTime(report.asOfTime) }} · 生成者 {{ report.createdByName }} · {{ providerLabel }}</p>
        </div>
        <div class="identity-status">
          <StatusBadge :label="report.provider === 'openai-compatible' ? '本地模型辅助排序' : '规则报告'" :tone="report.provider === 'openai-compatible' ? 'olive' : 'warning'" />
          <button class="fingerprint" type="button" :title="report.dataFingerprint" @click="copyFingerprint">数据指纹 {{ report.dataFingerprint.slice(0, 12) }}…</button>
        </div>
      </section>

      <section class="fact-grid" aria-label="报告事实快照">
        <article v-for="fact in facts" :key="fact.label" class="fact-cell">
          <span>{{ fact.label }}</span><strong :class="fact.tone">{{ fact.value }}</strong><small>{{ fact.note }}</small>
        </article>
      </section>

      <div class="report-layout">
        <section class="surface report-body">
          <div class="section-heading"><div><h2>分析正文</h2><p>精确数值以上方事实快照为准，正文用于解释与处置排序。</p></div></div>
          <article v-for="section in narrativeSections" :key="section.key" class="narrative-section">
            <h3>{{ section.title }}</h3><p>{{ section.content }}</p>
          </article>
          <div class="report-boundary"><strong>{{ boundarySection?.title }}</strong><p>{{ boundarySection?.content }}</p></div>
        </section>

        <aside class="report-side">
          <section class="surface side-panel">
            <div class="section-heading"><div><h2>优先行动</h2><p>操作仍需进入原业务模块确认。</p></div></div>
            <button v-for="action in report.priorityActions" :key="action.code" class="action-row" type="button" @click="router.push(action.route)">
              <span class="action-level" :class="action.level.toLowerCase()">{{ levelLabel(action.level) }}</span>
              <span><strong>{{ action.title }}</strong><small>{{ action.rationale }}</small></span>
              <ArrowRight />
            </button>
          </section>
          <section class="surface side-panel evidence-panel">
            <div class="section-heading"><div><h2>生成证据</h2><p>用于复核和论文实验记录。</p></div></div>
            <dl>
              <div><dt>模型</dt><dd>{{ report.modelName }}</dd></div>
              <div><dt>提示协议</dt><dd>{{ report.promptVersion }}</dd></div>
              <div><dt>数据范围</dt><dd>{{ report.context.scope }}</dd></div>
              <div><dt>时区</dt><dd>{{ report.context.timezone }}</dd></div>
              <div v-if="report.fallbackReason"><dt>降级原因</dt><dd>{{ report.fallbackReason }}</dd></div>
            </dl>
            <ul><li v-for="warning in report.warnings" :key="warning">{{ warning }}</li></ul>
          </section>
        </aside>
      </div>
    </template>

    <section class="surface history-panel">
      <div class="section-heading history-heading"><div><h2>历史报告</h2><p>每次生成均保留独立快照；点击可查看当时结论。</p></div><span>{{ reports.length }} 份</span></div>
      <div v-if="!reports.length" class="compact-empty">暂无报告记录。</div>
      <div v-else class="table-wrap"><el-table :data="reports" highlight-current-row @row-click="openReport">
        <el-table-column prop="reportNo" label="报告编号" min-width="190" />
        <el-table-column label="生成方式" width="120"><template #default="scope"><StatusBadge :label="scope.row.provider === 'openai-compatible' ? '本地模型' : '规则降级'" :tone="scope.row.provider === 'openai-compatible' ? 'olive' : 'warning'" /></template></el-table-column>
        <el-table-column prop="modelName" label="模型/规则" min-width="190" />
        <el-table-column prop="createdByName" label="生成者" width="125" />
        <el-table-column label="统计时点" width="180"><template #default="scope">{{ dateTime(scope.row.asOfTime) }}</template></el-table-column>
        <el-table-column label="数据指纹" min-width="150"><template #default="scope"><span class="mono">{{ scope.row.dataFingerprint.slice(0, 12) }}…</span></template></el-table-column>
      </el-table></div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowRight, Lock, WarningFilled } from '@element-plus/icons-vue'
import EmptyState from '@/components/EmptyState.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { apiList, apiMutate, apiRequest, extractApiError } from '@/services/api'
import type { AnalysisReport, AnalysisReportSummary } from '@/types'

const router = useRouter()
const reports = ref<AnalysisReportSummary[]>([])
const report = ref<AnalysisReport>()
const loading = ref(false)
const generating = ref(false)
const errorMessage = ref('')
const metrics = computed(() => report.value?.context.metrics)
const providerLabel = computed(() => report.value?.provider === 'openai-compatible' ? `本地模型 ${report.value.modelName}` : `可复现规则 ${report.value?.modelName}`)
const narrativeSections = computed(() => report.value?.sections.filter((item) => item.key !== 'executive_summary' && item.key !== 'boundary') || [])
const boundarySection = computed(() => report.value?.sections.find((item) => item.key === 'boundary'))
const facts = computed(() => metrics.value ? [
  { label: '执行中订单', value: formatNumber(metrics.value.activeOrders), note: `其中逾期 ${metrics.value.overdueOrders} 笔`, tone: metrics.value.overdueOrders ? 'danger' : '' },
  { label: '库存缺口', value: formatNumber(metrics.value.inventoryShortages), note: '低于安全库存的库存项', tone: metrics.value.inventoryShortages ? 'danger' : '' },
  { label: '未闭环预警', value: formatNumber(metrics.value.openWarnings), note: `高等级 ${metrics.value.highWarnings} 条`, tone: metrics.value.highWarnings ? 'danger' : '' },
  { label: '待审批计划', value: formatNumber(metrics.value.pendingPlans), note: '等待管理审批', tone: metrics.value.pendingPlans ? 'warning' : '' },
  { label: '待闭环对账', value: formatNumber(metrics.value.pendingReconciliations), note: `差异 ¥${money(metrics.value.reconciliationDifferenceAmount)}`, tone: metrics.value.reconciliationDifferenceAmount ? 'warning' : '' },
  { label: '供应商准时率', value: `${(metrics.value.averageOnTimeRate * 100).toFixed(1)}%`, note: `${metrics.value.activeSuppliers} 家活跃供应商`, tone: '' },
] : [])

async function loadReports(selectLatest = true) {
  loading.value = true; errorMessage.value = ''
  try {
    reports.value = (await apiList<AnalysisReportSummary>({ method: 'GET', url: '/intelligence/analysis-reports' })).records
    if (selectLatest && reports.value[0]) await openReport(reports.value[0], false)
  } catch (error) { errorMessage.value = extractApiError(error) }
  finally { loading.value = false }
}
async function openReport(item: AnalysisReportSummary, scroll = true) {
  errorMessage.value = ''
  try {
    report.value = await apiRequest<AnalysisReport>({ method: 'GET', url: `/intelligence/analysis-reports/${item.id}` })
    if (scroll) requestAnimationFrame(() => document.querySelector('.report-identity')?.scrollIntoView({ behavior: 'smooth', block: 'start' }))
  } catch (error) { errorMessage.value = extractApiError(error) }
}
async function generateReport() {
  generating.value = true; errorMessage.value = ''
  try {
    report.value = await apiMutate<AnalysisReport>({ method: 'POST', url: '/intelligence/analysis-reports', data: {}, timeout: 90000 })
    ElMessage.success(report.value.provider === 'openai-compatible' ? '本地模型已完成风险章节排序，事实报告已生成' : '经营分析报告已按规则降级生成')
    await loadReports(false)
  } catch (error) { errorMessage.value = extractApiError(error) }
  finally { generating.value = false }
}
async function copyFingerprint() {
  if (!report.value) return
  await navigator.clipboard.writeText(report.value.dataFingerprint)
  ElMessage.success('数据指纹已复制')
}
function levelLabel(level: string) { return ({ HIGH: '优先', MEDIUM: '随后', LOW: '持续' } as Record<string, string>)[level] || level }
function formatNumber(value: number) { return Number(value || 0).toLocaleString('zh-CN') }
function money(value: number) { return Number(value || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) }
function dateTime(value?: string) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—' }
onMounted(() => loadReports())
</script>

<style scoped>
.governance-note { display: flex; gap: 12px; align-items: flex-start; margin-bottom: 18px; padding: 13px 15px; border: 1px solid var(--lm-border); background: var(--lm-blue-soft); color: var(--lm-blue); }
.governance-note svg { flex: 0 0 auto; width: 17px; margin-top: 2px; }
.governance-note div { display: grid; gap: 3px; }
.governance-note strong { font-size: 13px; }
.governance-note span { font-size: 11px; line-height: 1.55; }
.report-skeleton { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin: 22px 0; }
.report-skeleton .skeleton { height: 100px; }.report-skeleton .wide { grid-column: 1 / -1; height: 260px; }
.report-identity { display: flex; align-items: flex-start; justify-content: space-between; gap: 24px; padding: 21px 23px; border-top: 3px solid var(--lm-olive); }
.report-no { color: var(--lm-olive); font-size: 11px; font-weight: 750; letter-spacing: .05em; }
.report-identity h2 { max-width: 920px; margin: 8px 0 7px; font-size: 18px; font-weight: 650; line-height: 1.55; }
.report-identity p { margin: 0; color: var(--lm-muted); font-size: 11px; }
.identity-status { display: grid; justify-items: end; gap: 10px; flex: 0 0 auto; }
.fingerprint { padding: 0; border: 0; background: transparent; color: var(--lm-blue); font-size: 10px; font-family: ui-monospace, SFMono-Regular, Consolas, monospace; }
.fact-grid { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); margin: 14px 0; border: 1px solid var(--lm-border); background: #fff; }
.fact-cell { min-width: 0; padding: 15px; border-right: 1px solid var(--lm-border); }.fact-cell:last-child { border-right: 0; }
.fact-cell span,.fact-cell small { display: block; color: var(--lm-muted); font-size: 10px; }.fact-cell strong { display: block; margin: 7px 0 5px; font-size: 22px; font-weight: 720; font-variant-numeric: tabular-nums; }.fact-cell strong.danger { color: var(--lm-danger); }.fact-cell strong.warning { color: var(--lm-warning); }
.report-layout { display: grid; grid-template-columns: minmax(0, 1.55fr) minmax(320px, .72fr); gap: 14px; align-items: start; }
.report-body,.side-panel { padding: 20px; }.narrative-section { padding: 16px 0; border-top: 1px solid var(--lm-border); }.narrative-section h3 { margin: 0 0 7px; font-size: 14px; }.narrative-section p,.report-boundary p { margin: 0; color: var(--lm-ink-soft); font-size: 12px; line-height: 1.8; }
.report-boundary { margin-top: 12px; padding: 13px 15px; background: var(--lm-surface); border: 1px solid var(--lm-border); }.report-boundary strong { display: block; margin-bottom: 5px; font-size: 12px; }
.report-side { display: grid; gap: 14px; }.action-row { display: grid; grid-template-columns: auto 1fr auto; gap: 10px; align-items: start; width: 100%; padding: 13px 0; border: 0; border-top: 1px solid var(--lm-border); background: transparent; text-align: left; }.action-row:hover strong { color: var(--lm-blue); }.action-row svg { width: 14px; margin-top: 3px; color: var(--lm-muted); }.action-row span:nth-child(2) { display: grid; gap: 4px; }.action-row strong { font-size: 12px; }.action-row small { color: var(--lm-muted); font-size: 10px; line-height: 1.55; }
.action-level { padding: 2px 5px; border: 1px solid var(--lm-border); color: var(--lm-muted); font-size: 9px; font-weight: 700; }.action-level.high { border-color: color-mix(in srgb, var(--lm-danger) 35%, transparent); color: var(--lm-danger); }.action-level.medium { border-color: color-mix(in srgb, var(--lm-warning) 35%, transparent); color: var(--lm-warning); }
.evidence-panel dl { margin: 0; }.evidence-panel dl div { display: grid; grid-template-columns: 72px minmax(0,1fr); gap: 10px; padding: 8px 0; border-top: 1px solid var(--lm-border); font-size: 10px; }.evidence-panel dt { color: var(--lm-muted); }.evidence-panel dd { margin: 0; overflow-wrap: anywhere; }.evidence-panel ul { margin: 12px 0 0; padding-left: 18px; color: var(--lm-muted); font-size: 10px; line-height: 1.65; }
.history-panel { margin-top: 14px; }.history-heading { padding: 18px 20px 0; }.history-heading > span { color: var(--lm-muted); font-size: 11px; }.compact-empty { padding: 26px 20px; color: var(--lm-muted); font-size: 12px; text-align: center; }.mono { font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 10px; }
@media (max-width: 1100px) { .fact-grid { grid-template-columns: repeat(3,1fr); }.fact-cell:nth-child(3) { border-right: 0; }.fact-cell:nth-child(-n+3) { border-bottom: 1px solid var(--lm-border); }.report-layout { grid-template-columns: 1fr; } }
@media (max-width: 640px) { .report-identity { display: grid; padding: 17px; }.identity-status { justify-items: start; }.report-identity h2 { font-size: 15px; }.fact-grid { grid-template-columns: repeat(2,1fr); }.fact-cell:nth-child(3) { border-right: 1px solid var(--lm-border); }.fact-cell:nth-child(even) { border-right: 0; }.fact-cell:nth-child(-n+4) { border-bottom: 1px solid var(--lm-border); }.report-body,.side-panel { padding: 16px; } }
</style>
