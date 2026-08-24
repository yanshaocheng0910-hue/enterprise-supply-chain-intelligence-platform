<template>
  <div>
    <PageHeader title="预警中心" description="每条预警都说明对象、原因、影响与建议动作；处理结果会写入审计记录。">
      <template #actions><el-button :icon="Refresh" :loading="loading" @click="load">刷新预警</el-button></template>
    </PageHeader>
    <div class="warning-summary"><div v-for="item in summaryItems" :key="item.key" class="summary-chip"><span class="summary-count" :class="item.tone">{{ item.count }}</span><span>{{ item.label }}</span></div></div>
    <section class="surface warning-table">
      <div class="toolbar"><el-input v-model="keyword" clearable placeholder="搜索预警对象或原因" :prefix-icon="Search" /><el-select v-model="statusFilter" clearable placeholder="全部状态" style="width: 145px"><el-option label="待处理" value="OPEN" /><el-option label="已确认" value="ACKNOWLEDGED" /><el-option label="已解决" value="RESOLVED" /></el-select><el-select v-model="severityFilter" clearable placeholder="全部级别" style="width: 130px"><el-option label="严重" value="CRITICAL" /><el-option label="高" value="HIGH" /><el-option label="中" value="MEDIUM" /><el-option label="低" value="LOW" /></el-select><span class="toolbar-spacer"></span><span class="toolbar-meta">共 {{ filteredWarnings.length }} 条</span></div>
      <div v-if="errorMessage" class="inline-error"><WarningFilled />{{ errorMessage }}</div>
      <div v-if="loading && !warnings.length" class="table-loading"><div v-for="i in 4" :key="i" class="skeleton" style="height: 46px"></div></div>
      <div v-else-if="!filteredWarnings.length"><EmptyState title="没有匹配的预警" description="当前筛选范围内没有记录，或服务端还未返回预警数据。" action-text="清空筛选" @action="clearFilters" /></div>
      <div v-else class="table-wrap"><el-table :data="filteredWarnings" stripe><el-table-column label="级别" width="82"><template #default="scope"><StatusBadge :label="severityMeta(scope.row.severity).label" :tone="severityMeta(scope.row.severity).tone" /></template></el-table-column><el-table-column label="预警与对象" min-width="240"><template #default="scope"><strong>{{ scope.row.title }}</strong><div class="muted">{{ scope.row.subject }}</div></template></el-table-column><el-table-column prop="description" label="原因" min-width="280" show-overflow-tooltip /><el-table-column label="状态" width="100"><template #default="scope"><StatusBadge :label="statusMeta(scope.row.status).label" :tone="statusMeta(scope.row.status).tone" /></template></el-table-column><el-table-column prop="createdAt" label="创建时间" width="170" /><el-table-column label="操作" width="105" fixed="right"><template #default="scope"><el-button link type="primary" @click="openDetail(scope.row)">查看处置</el-button></template></el-table-column></el-table></div>
    </section>
    <el-drawer v-model="detailVisible" title="预警处置" size="475px"><template v-if="selected"><div class="warning-detail-head"><StatusBadge :label="severityMeta(selected.severity).label" :tone="severityMeta(selected.severity).tone" /><h2>{{ selected.title }}</h2><p>{{ selected.subject }}</p></div><dl class="drawer-list"><div><dt>原因</dt><dd>{{ selected.description }}</dd></div><div><dt>可能影响</dt><dd>{{ selected.impact }}</dd></div><div><dt>建议动作</dt><dd>{{ selected.suggestion }}</dd></div><div><dt>责任人</dt><dd>{{ selected.owner || '待分派' }}</dd></div><div><dt>创建时间</dt><dd>{{ selected.createdAt }}</dd></div></dl><div v-if="selected.status !== 'RESOLVED'" class="drawer-actions"><el-button :loading="updating" @click="updateStatus('ACKNOWLEDGED')">标记已确认</el-button><el-button type="primary" :loading="updating" @click="updateStatus('RESOLVED')">记录已解决</el-button></div><div v-else class="inline-note">该预警已解决。若业务事实发生变化，后端会根据新事件生成新的预警记录。</div></template></el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, Search, WarningFilled } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import EmptyState from '@/components/EmptyState.vue'
import { apiList, apiMutate, extractApiError } from '@/services/api'
import { demoWarnings } from '@/services/demo'
import { mapWarning } from '@/services/mappers'
import type { WarningRecord } from '@/types'

const warnings = ref<WarningRecord[]>([]); const keyword = ref(''); const statusFilter = ref(''); const severityFilter = ref(''); const loading = ref(false); const updating = ref(false); const errorMessage = ref(''); const selected = ref<WarningRecord>(); const detailVisible = ref(false)
const filteredWarnings = computed(() => warnings.value.filter((item) => (!statusFilter.value || item.status === statusFilter.value) && (!severityFilter.value || item.severity === severityFilter.value) && (!keyword.value || `${item.title}${item.subject}${item.description}`.toLowerCase().includes(keyword.value.toLowerCase()))))
const summaryItems = computed(() => [{ key: 'open', label: '待处理', count: warnings.value.filter((item) => item.status === 'OPEN').length, tone: 'danger' }, { key: 'high', label: '高风险', count: warnings.value.filter((item) => item.severity === 'HIGH' && item.status !== 'RESOLVED').length, tone: 'warning' }, { key: 'ack', label: '已确认', count: warnings.value.filter((item) => item.status === 'ACKNOWLEDGED').length, tone: 'blue' }, { key: 'resolved', label: '已解决', count: warnings.value.filter((item) => item.status === 'RESOLVED').length, tone: 'success' }])
async function load() { loading.value = true; errorMessage.value = ''; try { const result = await apiList<WarningRecord>({ method: 'GET', url: '/warnings' }, mapWarning, () => demoWarnings); warnings.value = result.records } catch (error) { errorMessage.value = extractApiError(error) } finally { loading.value = false } }
function openDetail(item: WarningRecord) { selected.value = item; detailVisible.value = true }
async function updateStatus(status: WarningRecord['status']) { if (!selected.value) return; updating.value = true; try { const raw = await apiMutate<unknown>({ method: 'POST', url: `/warnings/${selected.value.id}/handle`, data: { result: status === 'RESOLVED' ? '已完成处置' : '已确认并分派处理', close: status === 'RESOLVED' } }, () => ({ ...selected.value!, status })); const result = mapWarning(raw); warnings.value = warnings.value.map((item) => item.id === result.id ? result : item); selected.value = result; ElMessage.success(`预警已标记为${statusMeta(status).label}`) } catch (error) { ElMessage.error(extractApiError(error)) } finally { updating.value = false } }
function clearFilters() { keyword.value = ''; statusFilter.value = ''; severityFilter.value = '' }
function severityMeta(value: WarningRecord['severity']) { return ({ CRITICAL: { label: '严重', tone: 'danger' }, HIGH: { label: '高', tone: 'danger' }, MEDIUM: { label: '中', tone: 'warning' }, LOW: { label: '低', tone: 'blue' } } as const)[value] }
function statusMeta(value: WarningRecord['status']) { return ({ OPEN: { label: '待处理', tone: 'danger' }, ACKNOWLEDGED: { label: '已确认', tone: 'warning' }, RESOLVED: { label: '已解决', tone: 'success' } } as const)[value] }
onMounted(load)
</script>

<style scoped>
.warning-summary { display: flex; gap: 9px; margin-bottom: 14px; flex-wrap: wrap; }
.summary-chip { display: flex; align-items: center; gap: 8px; padding: 8px 11px; border: 1px solid var(--lm-border); background: #fff; color: var(--lm-muted); font-size: 11px; }
.summary-count { font-size: 17px; font-weight: 750; font-variant-numeric: tabular-nums; }
.summary-count.danger { color: var(--lm-danger); }.summary-count.warning { color: var(--lm-warning); }.summary-count.blue { color: var(--lm-blue); }.summary-count.success { color: var(--lm-success); }
.warning-table { overflow: hidden; }
.toolbar-meta { color: var(--lm-muted); font-size: 11px; }
.muted { margin-top: 4px; color: var(--lm-muted); font-size: 11px; }
.warning-detail-head { padding-bottom: 20px; border-bottom: 1px solid var(--lm-border); }
.warning-detail-head h2 { margin: 13px 0 5px; font-size: 20px; }
.warning-detail-head p { margin: 0; color: var(--lm-muted); font-size: 12px; }
.drawer-list { margin: 6px 0 0; }.drawer-list > div { display: grid; grid-template-columns: 62px 1fr; gap: 17px; padding: 14px 0; border-bottom: 1px solid var(--lm-border); }.drawer-list dt { color: var(--lm-muted); font-size: 12px; }.drawer-list dd { margin: 0; font-size: 12px; line-height: 1.6; }
.drawer-actions { display: flex; gap: 9px; margin-top: 22px; }
</style>
