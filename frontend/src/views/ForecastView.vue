<template>
  <div>
    <PageHeader title="14 天需求预测" description="用 MA7 基线与条件模型辅助补货判断；预测结果包含窗口、模型与降级说明，不自动下单。">
      <template #actions><el-select v-model="materialCode" style="width: 250px" :disabled="!materialOptions.length" @change="load"><el-option v-for="item in materialOptions" :key="item.code" :label="`${item.code} ${item.name}`" :value="item.code" /></el-select><el-button v-if="auth.role === 'BUYER'" :icon="Refresh" :loading="loading" :disabled="!materialCode" @click="runForecast">重新计算</el-button><el-button v-else :icon="Refresh" :loading="loading" :disabled="!materialCode" @click="load">刷新运行批次</el-button></template>
    </PageHeader>
    <div v-if="errorMessage" class="inline-error"><WarningFilled />{{ errorMessage }}</div>
    <section v-if="forecast.length" class="surface surface-pad forecast-hero">
      <div class="section-heading"><div><div class="eyebrow">FORECAST WINDOW · 14 DAYS</div><h2>{{ forecast[0]?.materialName }}（{{ forecast[0]?.materialCode }}）</h2><p>预测区间 {{ forecast[0]?.date }} 至 {{ forecast[forecast.length - 1]?.date }} · 结果以服务端预测批次为准</p></div><StatusBadge label="建议输入" tone="olive" /></div>
      <div ref="chartRef" class="forecast-chart" aria-label="14 天需求预测图"></div>
      <div class="forecast-legend"><span><i class="line olive"></i>模型预测</span><span v-if="hasBaseline"><i class="line dashed"></i>MA7 基线</span><span v-if="hasInterval"><i class="band"></i>预测区间</span><span class="legend-note">仅展示服务端实际返回的序列字段</span></div>
      <div v-if="runMetadata && selectedMetrics" class="forecast-suggestion">
        <div class="suggestion-copy">
          <span>本批次补货建议</span>
          <strong>{{ suggestionQuantityText }}</strong>
          <p>{{ suggestionExplanation }}</p>
        </div>
        <div class="suggestion-actions">
          <StatusBadge :label="suggestionStatusMeta.label" :tone="suggestionStatusMeta.tone" />
          <el-button v-if="canAdoptSuggestion" :loading="adopting" @click="rejectSuggestion">不采纳</el-button>
          <el-button v-if="canAdoptSuggestion" type="primary" @click="openAdoptDialog">采纳为采购需求</el-button>
          <el-button v-else-if="selectedMetrics.adoptedDemand" @click="router.push('/purchase-demands')">查看采购需求</el-button>
        </div>
      </div>
    </section>
    <div v-else-if="loading" class="surface skeleton" style="height: 390px"></div>
    <EmptyState v-else title="暂无逐日预测结果" :description="emptyDescription" action-text="重新加载" @action="load" />

    <section v-if="runMetadata && runRecords.length" class="surface surface-pad forecast-runs"><div class="section-heading"><div><h2>预测运行批次</h2><p>{{ forecast.length ? '已读取选中批次详情并绘制服务端逐日结果。' : '服务端返回了运行批次，但详情没有逐日结果，因此不绘制虚构曲线。' }}</p></div><StatusBadge label="运行记录" tone="blue" /></div><el-table :data="runRecords" size="small"><el-table-column prop="runNo" label="批次号" min-width="175" /><el-table-column prop="date" label="基准日期" width="125" /><el-table-column prop="horizonDays" label="窗口" width="90"><template #default="scope">{{ scope.row.horizonDays || '—' }}{{ scope.row.horizonDays ? ' 天' : '' }}</template></el-table-column><el-table-column prop="modelName" label="模型" min-width="155"><template #default="scope">{{ scope.row.modelName || '服务端未返回' }}</template></el-table-column><el-table-column prop="dataLabel" label="数据标记" min-width="150"><template #default="scope">{{ scope.row.dataLabel || '—' }}</template></el-table-column><el-table-column label="建议量" width="110" align="right"><template #default="scope"><span class="number">{{ scope.row.suggestedOrderQty === undefined ? '—' : scope.row.suggestedOrderQty.toLocaleString('zh-CN') }}</span></template></el-table-column><el-table-column label="建议状态" width="105"><template #default="scope"><StatusBadge :label="suggestionMeta(scope.row.suggestionStatus, scope.row.suggestedOrderQty).label" :tone="suggestionMeta(scope.row.suggestionStatus, scope.row.suggestedOrderQty).tone" /></template></el-table-column><el-table-column prop="status" label="运行状态" width="110" /><el-table-column prop="fallbackReason" label="降级说明" min-width="190"><template #default="scope">{{ scope.row.fallbackReason || '—' }}</template></el-table-column></el-table><div v-if="selectedMetrics" class="run-metrics"><span>MAE {{ selectedMetrics.mae ?? '—' }}</span><span>RMSE {{ selectedMetrics.rmse ?? '—' }}</span><span>MAPE {{ selectedMetrics.mape ?? '—' }}</span><span>特征版本 {{ selectedMetrics.featureVersion || '—' }}</span></div></section>
    <div v-if="forecast.length && !runMetadata" class="split-grid forecast-detail">
      <section class="surface surface-pad"><div class="section-heading"><div><h2>模型与口径</h2><p>每次运行都需要保留可复现信息</p></div></div><dl class="detail-grid detail-grid--two"><div class="detail-item"><dt>主模型</dt><dd>{{ modelName }}</dd></div><div class="detail-item"><dt>基线</dt><dd>MA7</dd></div><div class="detail-item"><dt>训练窗口</dt><dd>最近 90 天</dd></div><div class="detail-item"><dt>更新批次</dt><dd>FC-20260824-01</dd></div></dl><div class="inline-note">若条件模型不可用，系统会回退到 MA7，并在预测批次与审计记录中说明原因。</div></section>
      <section class="surface surface-pad"><div class="section-heading"><div><h2>未来明细</h2><p>首 5 个预测日，完整结果可从接口导出</p></div><el-button link type="primary" @click="router.push('/purchase-demands')">查看采购建议</el-button></div><el-table :data="forecast.slice(0, 5)" size="small"><el-table-column prop="date" label="日期" width="120" /><el-table-column label="预测" align="right"><template #default="scope"><span class="number">{{ scope.row.forecast }}</span></template></el-table-column><el-table-column label="MA7" align="right"><template #default="scope"><span class="number muted">{{ scope.row.baseline }}</span></template></el-table-column><el-table-column label="区间" align="right"><template #default="scope"><span class="number muted">{{ scope.row.lower }} - {{ scope.row.upper }}</span></template></el-table-column></el-table></section>
    </div>
    <el-dialog v-model="adoptDialogVisible" title="采纳预测建议" width="500px" destroy-on-close>
      <div v-if="selectedMetrics" class="adopt-context"><span>{{ selectedMetrics.runNo }}</span><strong>{{ selectedMetrics.materialName }}（{{ selectedMetrics.materialCode }}）</strong><small>建议采购 {{ suggestionQuantityText }}；确认后由服务端创建一条来源为“预测建议”的采购需求草稿。</small></div>
      <el-form ref="adoptFormRef" :model="adoptForm" :rules="adoptRules" label-position="top"><el-form-item label="期望到货日期" prop="expectedDate"><el-date-picker v-model="adoptForm.expectedDate" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item><el-form-item label="优先级" prop="priority"><el-select v-model="adoptForm.priority" style="width:100%"><el-option label="普通" value="NORMAL" /><el-option label="高" value="HIGH" /><el-option label="紧急" value="URGENT" /></el-select></el-form-item><el-form-item label="采纳说明"><el-input v-model="adoptForm.note" type="textarea" :rows="3" maxlength="500" show-word-limit placeholder="可选，说明采购背景或交付要求" /></el-form-item></el-form>
      <template #footer><el-button @click="adoptDialogVisible = false">取消</el-button><el-button type="primary" :loading="adopting" @click="adoptSuggestion">确认采纳</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import * as echarts from 'echarts'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Refresh, WarningFilled } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import EmptyState from '@/components/EmptyState.vue'
import { apiList, apiMutate, apiRequest, extractApiError } from '@/services/api'
import { demoForecast, demoMaterials } from '@/services/demo'
import { mapForecast, mapForecastDetail, mapMaterial } from '@/services/mappers'
import { useAuthStore } from '@/stores/auth'
import type { ForecastPoint, Material } from '@/types'

const router = useRouter(); const auth = useAuthStore(); const loading = ref(false); const errorMessage = ref(''); const materialCode = ref(''); const forecast = ref<ForecastPoint[]>([]); const runRecords = ref<ForecastPoint[]>([]); const materialOptions = ref<Material[]>([]); const chartRef = ref<HTMLElement>(); const runMetadata = ref(false); const selectedMetrics = ref<ForecastPoint>(); const adoptDialogVisible = ref(false); const adopting = ref(false); const adoptFormRef = ref<FormInstance>(); let chart: echarts.ECharts | undefined
const adoptForm = reactive({ expectedDate: '', priority: 'NORMAL', note: '' })
const adoptRules: FormRules = { expectedDate: [{ required: true, message: '请选择期望到货日期', trigger: 'change' }], priority: [{ required: true, message: '请选择优先级', trigger: 'change' }] }
const modelName = ref('XGBoost 条件模型')
const emptyDescription = computed(() => runMetadata.value ? '服务端已返回运行批次，但选中批次没有 results/sequence 逐日数据。' : '请确认历史需求数据已经导入，并检查服务端预测任务状态。')
const hasBaseline = computed(() => forecast.value.some((item) => item.baseline !== undefined))
const hasInterval = computed(() => forecast.value.some((item) => item.lower !== undefined && item.upper !== undefined))
const suggestedOrderQty = computed(() => selectedMetrics.value?.suggestedOrderQty)
const suggestionStatusMeta = computed(() => suggestionMeta(selectedMetrics.value?.suggestionStatus, suggestedOrderQty.value))
const suggestionQuantityText = computed(() => suggestedOrderQty.value === undefined ? '服务端未返回' : `${suggestedOrderQty.value.toLocaleString('zh-CN')} ${selectedMetrics.value?.unit || '单位'}`)
const canAdoptSuggestion = computed(() => auth.role === 'BUYER' && Boolean(selectedMetrics.value?.id) && (suggestedOrderQty.value ?? 0) > 0 && selectedMetrics.value?.suggestionStatus === 'PENDING' && selectedMetrics.value?.version !== undefined)
const suggestionExplanation = computed(() => {
  const run = selectedMetrics.value
  if (!run || suggestedOrderQty.value === undefined) return '当前详情没有返回建议量，因此不提供采纳动作。'
  if (run.warningCode === 'HORIZON_INSUFFICIENT') return '采购提前期超过 14 天预测窗口，需要人工扩展判断周期，不能直接采纳。'
  if (suggestedOrderQty.value <= 0) return '现有库存与在途供给已覆盖预测需求和安全库存，本批次无需新增采购。'
  if (run.suggestionStatus === 'ADOPTED') return `已生成采购需求 ${run.adoptedDemand?.demandNo || ''}，可前往采购需求继续处理。`
  if (run.suggestionStatus === 'REJECTED') return run.suggestionDecisionNote || '该建议已由采购人员记录为不采纳。'
  return '根据 14 天预测、安全库存、现有与在途库存计算，并按最小采购量和包装量取整；采纳前仍需确认到货日期。'
})
async function load() {
  loading.value = true
  errorMessage.value = ''
  forecast.value = []
  runRecords.value = []
  selectedMetrics.value = undefined
  runMetadata.value = false
  chart?.dispose()
  chart = undefined
  try {
    const result = await apiList<ForecastPoint>({ method: 'GET', url: '/intelligence/forecast-runs', params: { materialCode: materialCode.value } }, mapForecast, () => demoForecast.filter((item) => item.materialCode === materialCode.value))
    runRecords.value = result.records
    runMetadata.value = runRecords.value.some((item) => Boolean(item.runNo))
    selectedMetrics.value = runRecords.value[0]
    if (runMetadata.value && runRecords.value[0]?.id) {
      const detailRaw = await apiRequest<unknown>({ method: 'GET', url: `/intelligence/forecast-runs/${runRecords.value[0].id}` })
      const detail = mapForecastDetail(detailRaw)
      forecast.value = detail.points
      selectedMetrics.value = detail.metadata
      runRecords.value = [detail.metadata, ...runRecords.value.slice(1)]
      modelName.value = detail.metadata.modelName || '服务端批次模型'
    } else {
      forecast.value = result.records
      modelName.value = forecast.value[0]?.modelName || '服务端批次模型'
    }
    await nextTick()
    drawChart()
  } catch (error) {
    errorMessage.value = extractApiError(error)
    forecast.value = []
    runRecords.value = []
    runMetadata.value = false
  } finally {
    loading.value = false
  }
}
async function initialize() {
  loading.value = true
  errorMessage.value = ''
  try {
    const materials = await apiList<Material>({ method: 'GET', url: '/master-data/materials' }, mapMaterial, () => demoMaterials)
    materialOptions.value = materials.records.filter((item) => item.status === 'ACTIVE')
    const runs = await apiList<ForecastPoint>({ method: 'GET', url: '/intelligence/forecast-runs' }, mapForecast, () => demoForecast)
    const latestMaterial = runs.records.find((item) => materialOptions.value.some((material) => material.code === item.materialCode))?.materialCode
    materialCode.value = latestMaterial || materialOptions.value[0]?.code || ''
  } catch (error) {
    errorMessage.value = extractApiError(error)
    materialOptions.value = []
    materialCode.value = ''
  } finally {
    loading.value = false
  }
  if (materialCode.value) await load()
}
async function runForecast() { loading.value = true; errorMessage.value = ''; try { await apiMutate<unknown>({ method: 'POST', url: '/forecast/14d', data: { materialCode: materialCode.value } }); ElMessage.success('预测运行已提交'); await load() } catch (error) { errorMessage.value = extractApiError(error) } finally { loading.value = false } }
function openAdoptDialog() {
  if (!canAdoptSuggestion.value) return
  const today = localDateText(new Date())
  const horizonDate = forecast.value.at(-1)?.date || today
  Object.assign(adoptForm, { expectedDate: horizonDate >= today ? horizonDate : today, priority: 'NORMAL', note: '' })
  adoptDialogVisible.value = true
}
async function adoptSuggestion() {
  const valid = await adoptFormRef.value?.validate().catch(() => false)
  if (!valid || !canAdoptSuggestion.value || !selectedMetrics.value?.id) return
  adopting.value = true
  try {
    await apiMutate<unknown>({ method: 'POST', url: `/intelligence/forecast-runs/${selectedMetrics.value.id}/adopt`, data: { expectedVersion: selectedMetrics.value.version, expectedDate: adoptForm.expectedDate, priority: adoptForm.priority, note: adoptForm.note || undefined } })
    adoptDialogVisible.value = false
    ElMessage.success('预测建议已采纳，采购需求草稿已生成')
    await load()
  } catch (error) {
    ElMessage.error(extractApiError(error))
  } finally {
    adopting.value = false
  }
}
async function rejectSuggestion() {
  if (!canAdoptSuggestion.value || !selectedMetrics.value?.id) return
  try {
    const { value } = await ElMessageBox.prompt('请记录本次不采纳的业务原因，便于后续优化建议规则。', '不采纳预测建议', {
      confirmButtonText: '确认不采纳', cancelButtonText: '取消', inputType: 'textarea', inputPlaceholder: '例如：已有临时调拨计划覆盖本次缺口',
      inputValidator: (input) => input.trim().length > 0 && input.length <= 500 ? true : '请输入 1–500 字原因',
    })
    adopting.value = true
    await apiMutate<unknown>({ method: 'POST', url: `/intelligence/forecast-runs/${selectedMetrics.value.id}/reject`, data: { expectedVersion: selectedMetrics.value.version, note: value.trim() } })
    ElMessage.success('已记录不采纳原因')
    await load()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(extractApiError(error))
  } finally {
    adopting.value = false
  }
}
function suggestionMeta(status?: string, quantity?: number): { label: string; tone: 'neutral' | 'warning' | 'success' | 'blue' | 'danger' | 'olive' } {
  if (quantity !== undefined && quantity <= 0) return { label: '无需采购', tone: 'neutral' }
  return ({ PENDING: { label: '待决策', tone: 'warning' }, ADOPTED: { label: '已采纳', tone: 'success' }, REJECTED: { label: '不采纳', tone: 'neutral' } } as const)[status || ''] || { label: status || '未返回', tone: 'neutral' }
}
function localDateText(date: Date) { const year = date.getFullYear(); const month = String(date.getMonth() + 1).padStart(2, '0'); const day = String(date.getDate()).padStart(2, '0'); return `${year}-${month}-${day}` }
function drawChart() { if (!chartRef.value || !forecast.value.length) return; chart?.dispose(); chart = echarts.init(chartRef.value); const series: echarts.SeriesOption[] = [{ name: '模型预测', type: 'line', data: forecast.value.map((item) => item.forecast), symbol: 'circle', symbolSize: 5, smooth: 0.18, lineStyle: { width: 2, color: '#66783b' }, itemStyle: { color: '#66783b' } }]; if (hasInterval.value) series.unshift({ name: '预测上界', type: 'line', data: forecast.value.map((item) => item.upper), symbol: 'none', lineStyle: { opacity: 0 }, stack: 'band', areaStyle: { color: 'rgba(102,120,59,0.08)' } }, { name: '预测下界', type: 'line', data: forecast.value.map((item) => item.lower), symbol: 'none', lineStyle: { opacity: 0 }, areaStyle: { color: '#fff' }, stack: 'band' }); if (hasBaseline.value) series.push({ name: 'MA7 基线', type: 'line', data: forecast.value.map((item) => item.baseline), symbol: 'none', smooth: 0.18, lineStyle: { width: 1.5, color: '#315e89', type: 'dashed' } }); chart.setOption({ animation: false, tooltip: { trigger: 'axis' }, legend: { show: false }, grid: { left: 15, right: 17, top: 18, bottom: 24, containLabel: true }, xAxis: { type: 'category', data: forecast.value.map((item) => item.date.slice(5)), axisLabel: { color: '#858a82', fontSize: 10 }, axisLine: { lineStyle: { color: '#dfe2dc' } } }, yAxis: { type: 'value', axisLabel: { color: '#858a82', fontSize: 10 }, splitLine: { lineStyle: { color: '#edf0eb' } } }, series }) }
function resizeChart() { chart?.resize() }
onMounted(() => { initialize(); window.addEventListener('resize', resizeChart) })
onBeforeUnmount(() => { chart?.dispose(); window.removeEventListener('resize', resizeChart) })
</script>

<style scoped>
.forecast-hero { margin-bottom: 14px; }
.forecast-chart { height: 320px; }
.forecast-legend { display: flex; align-items: center; gap: 17px; flex-wrap: wrap; color: var(--lm-muted); font-size: 11px; }
.forecast-legend span { display: inline-flex; align-items: center; gap: 6px; }
.line { display: inline-block; width: 22px; height: 2px; background: var(--lm-olive); }
.line.dashed { height: 0; border-top: 2px dashed var(--lm-blue); background: transparent; }
.band { display: inline-block; width: 22px; height: 8px; background: oklch(80% 0.06 112 / 0.3); }
.legend-note { margin-left: auto; color: var(--lm-faint); }
.forecast-suggestion { display: flex; align-items: flex-end; justify-content: space-between; gap: 22px; margin-top: 18px; padding-top: 17px; border-top: 1px solid var(--lm-border); }
.suggestion-copy { display: grid; gap: 5px; min-width: 0; }
.suggestion-copy > span { color: var(--lm-muted); font-size: 11px; }
.suggestion-copy > strong { color: var(--lm-ink); font-size: 20px; font-variant-numeric: tabular-nums; }
.suggestion-copy p { max-width: 72ch; margin: 0; color: var(--lm-muted); font-size: 11px; line-height: 1.55; }
.suggestion-actions { display: flex; align-items: center; justify-content: flex-end; gap: 9px; flex: 0 0 auto; }
.adopt-context { display: grid; gap: 5px; margin-bottom: 18px; padding: 12px 13px; border: 1px solid var(--lm-border); background: var(--lm-surface); }
.adopt-context span { color: var(--lm-blue); font-size: 11px; font-weight: 700; }
.adopt-context strong { font-size: 14px; }
.adopt-context small { color: var(--lm-muted); font-size: 11px; line-height: 1.5; }
.forecast-detail { margin-top: 14px; }
.forecast-runs { margin-bottom: 14px; }
.run-metrics { display: flex; flex-wrap: wrap; gap: 8px 16px; margin-top: 14px; color: var(--lm-muted); font-size: 11px; }
.detail-grid--two { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.number { font-variant-numeric: tabular-nums; }
.muted { color: var(--lm-muted); }
@media (max-width: 640px) { .legend-note { margin-left: 0; width: 100%; }.forecast-suggestion { align-items: flex-start; flex-direction: column; }.suggestion-actions { justify-content: flex-start; } }
</style>
