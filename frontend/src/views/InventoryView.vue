<template>
  <div>
    <PageHeader title="库存" description="同时查看可用量、在途量与安全库存，短缺判断不只依赖颜色，并可追溯到采购建议。">
      <template #actions><el-button :icon="Refresh" :loading="loading" @click="load">刷新库存</el-button><el-button v-if="auth.role === 'BUYER'" type="primary" :icon="Tickets" @click="router.push('/purchase-demands')">创建采购需求</el-button></template>
    </PageHeader>
    <div class="metric-grid inventory-metrics"><article class="metric-item" style="--metric-color: var(--lm-danger)"><div class="metric-label">短缺物料</div><div class="metric-value"><span>{{ shortageCount }}</span><span class="metric-unit">种</span></div><div class="metric-foot"><span class="trend-down">需要处置</span><span>扣除在途后</span></div></article><article class="metric-item" style="--metric-color: var(--lm-olive)"><div class="metric-label">安全库存覆盖率</div><div class="metric-value"><span>{{ coverageRate }}</span><span class="metric-unit">%</span></div><div class="metric-foot"><span>可用量 + 在途量</span><span>相对安全库存</span></div></article><article class="metric-item" style="--metric-color: var(--lm-blue)"><div class="metric-label">在途可用量</div><div class="metric-value"><span>{{ inTransitTotal.toLocaleString() }}</span><span class="metric-unit">件</span></div><div class="metric-foot"><span>服务端库存快照</span><span>{{ inTransitRecordCount }} 条记录</span></div></article><article class="metric-item" style="--metric-color: var(--lm-warning)"><div class="metric-label">库存记录</div><div class="metric-value"><span>{{ inventory.length }}</span><span class="metric-unit">条</span></div><div class="metric-foot"><span>当前可见范围</span><span>服务端返回</span></div></article></div>
    <section class="surface inventory-table">
      <div class="toolbar"><el-input v-model="keyword" clearable placeholder="搜索物料或仓库" :prefix-icon="Search" /><el-select v-model="onlyShortage" placeholder="全部库存状态" style="width: 145px"><el-option label="全部库存状态" value="all" /><el-option label="仅看短缺" value="shortage" /><el-option label="覆盖安全库存" value="safe" /></el-select><span class="toolbar-spacer"></span><span class="toolbar-meta">更新时间：{{ lastUpdated || '服务端返回后显示' }}</span></div>
      <div v-if="errorMessage" class="inline-error"><WarningFilled />{{ errorMessage }}</div>
      <div v-if="loading && !inventory.length" class="table-loading"><div v-for="i in 5" :key="i" class="skeleton" style="height: 42px"></div></div>
      <div v-else-if="!filteredInventory.length"><EmptyState title="暂无库存记录" :description="emptyDescription" :action-text="emptyActionText" @action="handleEmptyAction" /></div>
<div v-else class="table-wrap"><el-table :data="filteredInventory" stripe><el-table-column prop="materialCode" label="物料编码" width="120" /><el-table-column prop="materialName" label="物料" min-width="170" /><el-table-column prop="warehouse" label="仓库" min-width="155" /><el-table-column label="可用量" width="112" align="right"><template #default="scope"><span class="number">{{ scope.row.available.toLocaleString() }}</span> {{ scope.row.unit }}</template></el-table-column><el-table-column label="在途量" width="112" align="right"><template #default="scope"><span class="number">{{ scope.row.inTransit.toLocaleString() }}</span></template></el-table-column><el-table-column label="安全库存" width="112" align="right"><template #default="scope"><span class="number">{{ scope.row.safetyStock.toLocaleString() }}</span></template></el-table-column><el-table-column label="缺口" width="112" align="right"><template #default="scope"><span :class="scope.row.shortage > 0 ? 'danger-number' : 'safe-number'" class="number">{{ scope.row.shortage > 0 ? `-${scope.row.shortage.toLocaleString()}` : '0' }}</span></template></el-table-column><el-table-column label="判断" width="125"><template #default="scope"><StatusBadge :label="scope.row.shortage > 0 ? '低于安全库存' : '覆盖安全库存'" :tone="scope.row.shortage > 0 ? 'danger' : 'success'" /></template></el-table-column><el-table-column v-if="auth.role === 'BUYER'" label="操作" width="125" fixed="right"><template #default="scope"><el-button link type="primary" :disabled="scope.row.shortage <= 0" @click="createDemand(scope.row)">转采购需求</el-button></template></el-table-column></el-table></div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, Search, Tickets, WarningFilled } from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import PageHeader from '@/components/PageHeader.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import EmptyState from '@/components/EmptyState.vue'
import { apiMutate, apiList, extractApiError } from '@/services/api'
import { demoInventory } from '@/services/demo'
import { mapInventory } from '@/services/mappers'
import type { InventoryRecord } from '@/types'

const router = useRouter(); const auth = useAuthStore(); const inventory = ref<InventoryRecord[]>([]); const keyword = ref(''); const onlyShortage = ref('all'); const loading = ref(false); const errorMessage = ref(''); const lastUpdated = ref('')
const shortageCount = computed(() => inventory.value.filter((item) => item.shortage > 0).length)
const inTransitTotal = computed(() => inventory.value.reduce((sum, item) => sum + item.inTransit, 0))
const inTransitRecordCount = computed(() => inventory.value.filter((item) => item.inTransit > 0).length)
const coverageRate = computed(() => {
  const safety = inventory.value.reduce((sum, item) => sum + item.safetyStock, 0)
  if (!safety) return '—'
  return ((inventory.value.reduce((sum, item) => sum + item.available + item.inTransit, 0) / safety) * 100).toFixed(1)
})
const filteredInventory = computed(() => inventory.value.filter((item) => { const hit = !keyword.value || `${item.materialCode}${item.materialName}${item.warehouse}`.toLowerCase().includes(keyword.value.toLowerCase()); const state = onlyShortage.value === 'all' || (onlyShortage.value === 'shortage' ? item.shortage > 0 : item.shortage <= 0); return hit && state }))
const hasFilters = computed(() => Boolean(keyword.value || onlyShortage.value !== 'all'))
const emptyDescription = computed(() => hasFilters.value ? '当前筛选条件下没有匹配记录。' : auth.role === 'BUYER' ? '请先导入库存快照。' : '当前可见范围内还没有库存记录；管理角色不能导入数据。')
const emptyActionText = computed(() => hasFilters.value ? '清空筛选' : auth.role === 'BUYER' ? '前往数据导入' : '重新加载')
async function load() { loading.value = true; errorMessage.value = ''; try { const result = await apiList<InventoryRecord>({ method: 'GET', url: '/inventory' }, mapInventory, () => demoInventory); inventory.value = result.records; lastUpdated.value = result.records[0]?.updatedAt || '' } catch (error) { errorMessage.value = extractApiError(error) } finally { loading.value = false } }
function handleEmptyAction() { if (hasFilters.value) { keyword.value = ''; onlyShortage.value = 'all' } else if (auth.role === 'BUYER') router.push('/data-import'); else load() }
async function createDemand(item: InventoryRecord) { try { await apiMutate({ method: 'POST', url: '/purchase-demands', data: { materialCode: item.materialCode, quantity: Math.max(item.shortage, item.safetyStock), expectedDate: new Date(Date.now() + 7 * 86400000).toISOString().slice(0, 10), priority: 'HIGH', notes: '由库存风险生成' } }); ElMessage.success(`${item.materialName} 已生成采购需求草稿`); router.push('/purchase-demands') } catch (error) { ElMessage.error(extractApiError(error)) } }
onMounted(load)
</script>

<style scoped>
.inventory-metrics { grid-template-columns: repeat(4, minmax(0, 1fr)); }
.inventory-table { overflow: hidden; }
.toolbar-meta { color: var(--lm-muted); font-size: 11px; }
.danger-number { color: var(--lm-danger); }
.safe-number { color: var(--lm-success); }
.number { font-variant-numeric: tabular-nums; }
@media (max-width: 900px) { .inventory-metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 600px) { .inventory-metrics { grid-template-columns: 1fr; } }
</style>
