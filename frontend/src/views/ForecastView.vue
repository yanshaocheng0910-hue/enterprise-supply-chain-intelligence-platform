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
    </section>
    <div v-else-if="loading" class="surface skeleton" style="height: 390px"></div>
    <EmptyState v-else title="暂无逐日预测结果" :description="emptyDescription" action-text="重新加载" @action="load" />

    <section v-if="runMetadata && runRecords.length" class="surface surface-pad forecast-runs"><div class="section-heading"><div><h2>预测运行批次</h2><p>{{ forecast.length ? '已读取选中批次详情并绘制服务端逐日结果。' : '服务端返回了运行批次，但详情没有逐日结果，因此不绘制虚构曲线。' }}</p></div><StatusBadge label="运行记录" tone="blue" /></div><el-table :data="runRecords" size="small"><el-table-column prop="runNo" label="批次号" min-width="175" /><el-table-column prop="date" label="基准日期" width="125" /><el-table-column prop="horizonDays" label="窗口" width="90"><template #default="scope">{{ scope.row.horizonDays || '—' }}{{ scope.row.horizonDays ? ' 天' : '' }}</template></el-table-column><el-table-column prop="modelName" label="模型" min-width="155"><template #default="scope">{{ scope.row.modelName || '服务端未返回' }}</template></el-table-column><el-table-column prop="dataLabel" label="数据标记" min-width="150"><template #default="scope">{{ scope.row.dataLabel || '—' }}</template></el-table-column><el-table-column prop="status" label="状态" width="110" /><el-table-column prop="fallbackReason" label="降级说明" min-width="190"><template #default="scope">{{ scope.row.fallbackReason || '—' }}</template></el-table-column></el-table><div v-if="selectedMetrics" class="run-metrics"><span>MAE {{ selectedMetrics.mae ?? '—' }}</span><span>RMSE {{ selectedMetrics.rmse ?? '—' }}</span><span>MAPE {{ selectedMetrics.mape ?? '—' }}</span><span>特征版本 {{ selectedMetrics.featureVersion || '—' }}</span></div></section>
    <div v-if="forecast.length && !runMetadata" class="split-grid forecast-detail">
      <section class="surface surface-pad"><div class="section-heading"><div><h2>模型与口径</h2><p>每次运行都需要保留可复现信息</p></div></div><dl class="detail-grid detail-grid--two"><div class="detail-item"><dt>主模型</dt><dd>{{ modelName }}</dd></div><div class="detail-item"><dt>基线</dt><dd>MA7</dd></div><div class="detail-item"><dt>训练窗口</dt><dd>最近 90 天</dd></div><div class="detail-item"><dt>更新批次</dt><dd>FC-20260824-01</dd></div></dl><div class="inline-note">若条件模型不可用，系统会回退到 MA7，并在预测批次与审计记录中说明原因。</div></section>
      <section class="surface surface-pad"><div class="section-heading"><div><h2>未来明细</h2><p>首 5 个预测日，完整结果可从接口导出</p></div><el-button link type="primary" @click="router.push('/purchase-demands')">查看采购建议</el-button></div><el-table :data="forecast.slice(0, 5)" size="small"><el-table-column prop="date" label="日期" width="120" /><el-table-column label="预测" align="right"><template #default="scope"><span class="number">{{ scope.row.forecast }}</span></template></el-table-column><el-table-column label="MA7" align="right"><template #default="scope"><span class="number muted">{{ scope.row.baseline }}</span></template></el-table-column><el-table-column label="区间" align="right"><template #default="scope"><span class="number muted">{{ scope.row.lower }} - {{ scope.row.upper }}</span></template></el-table-column></el-table></section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import * as echarts from 'echarts'
import { ElMessage } from 'element-plus'
import { Refresh, WarningFilled } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import EmptyState from '@/components/EmptyState.vue'
import { apiList, apiMutate, apiRequest, extractApiError } from '@/services/api'
import { demoForecast, demoMaterials } from '@/services/demo'
import { mapForecast, mapForecastDetail, mapMaterial } from '@/services/mappers'
import { useAuthStore } from '@/stores/auth'
import type { ForecastPoint, Material } from '@/types'

const router = useRouter(); const auth = useAuthStore(); const loading = ref(false); const errorMessage = ref(''); const materialCode = ref(''); const forecast = ref<ForecastPoint[]>([]); const runRecords = ref<ForecastPoint[]>([]); const materialOptions = ref<Material[]>([]); const chartRef = ref<HTMLElement>(); const runMetadata = ref(false); const selectedMetrics = ref<ForecastPoint>(); let chart: echarts.ECharts | undefined
const modelName = ref('XGBoost 条件模型')
const emptyDescription = computed(() => runMetadata.value ? '服务端已返回运行批次，但选中批次没有 results/sequence 逐日数据。' : '请确认历史需求数据已经导入，并检查服务端预测任务状态。')
const hasBaseline = computed(() => forecast.value.some((item) => item.baseline !== undefined))
const hasInterval = computed(() => forecast.value.some((item) => item.lower !== undefined && item.upper !== undefined))
async function load() { loading.value = true; errorMessage.value = ''; chart?.dispose(); chart = undefined; try { const result = await apiList<ForecastPoint>({ method: 'GET', url: '/intelligence/forecast-runs', params: { materialCode: materialCode.value } }, mapForecast, () => demoForecast.filter((item) => item.materialCode === materialCode.value)); runRecords.value = result.records; runMetadata.value = runRecords.value.some((item) => Boolean(item.runNo)); selectedMetrics.value = runRecords.value[0]; if (runMetadata.value && runRecords.value[0]?.id) { const detailRaw = await apiRequest<unknown>({ method: 'GET', url: `/intelligence/forecast-runs/${runRecords.value[0].id}` }); const detail = mapForecastDetail(detailRaw); forecast.value = detail.points; selectedMetrics.value = detail.metadata; modelName.value = detail.metadata.modelName || '服务端批次模型' } else { forecast.value = result.records; modelName.value = forecast.value[0]?.modelName || '服务端批次模型' } await nextTick(); drawChart() } catch (error) { errorMessage.value = extractApiError(error); forecast.value = []; runRecords.value = []; runMetadata.value = false } finally { loading.value = false } }
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
.forecast-detail { margin-top: 14px; }
.forecast-runs { margin-bottom: 14px; }
.run-metrics { display: flex; flex-wrap: wrap; gap: 8px 16px; margin-top: 14px; color: var(--lm-muted); font-size: 11px; }
.detail-grid--two { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.number { font-variant-numeric: tabular-nums; }
.muted { color: var(--lm-muted); }
@media (max-width: 640px) { .legend-note { margin-left: 0; width: 100%; } }
</style>
