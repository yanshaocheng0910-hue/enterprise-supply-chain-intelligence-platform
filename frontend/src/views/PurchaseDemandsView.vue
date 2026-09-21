<template>
  <div>
    <PageHeader title="采购需求" description="承接预测、库存缺口和人工输入，先形成需求草稿，再进入计划审批。">
      <template #actions><el-button v-if="auth.role === 'BUYER'" :icon="DataAnalysis" @click="router.push('/ai-parse')">AI 解析需求</el-button><el-button v-if="auth.role === 'BUYER'" type="primary" :icon="Plus" @click="openCreate">新建需求</el-button></template>
    </PageHeader>
    <section class="surface demand-table">
      <div class="toolbar"><el-input v-model="keyword" clearable placeholder="搜索需求编号、物料" :prefix-icon="Search" /><el-select v-model="statusFilter" clearable placeholder="全部状态" style="width: 145px"><el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" /></el-select><el-select v-model="sourceFilter" clearable placeholder="全部来源" style="width: 145px"><el-option label="预测建议" value="FORECAST" /><el-option label="情景推演" value="SCENARIO" /><el-option label="人工创建" value="MANUAL" /><el-option label="AI 解析" value="AI_PARSE" /></el-select><span class="toolbar-spacer"></span><span class="toolbar-meta">{{ filtered.length }} 条需求</span><el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button></div>
      <div v-if="errorMessage" class="inline-error"><WarningFilled />{{ errorMessage }}</div>
      <div v-if="loading && !demands.length" class="table-loading"><div v-for="i in 4" :key="i" class="skeleton" style="height: 44px"></div></div>
      <div v-else-if="!filtered.length"><EmptyState title="暂无采购需求" :description="emptyDescription" :action-text="emptyActionText" @action="handleEmptyAction" /></div>
      <div v-else class="table-wrap"><el-table :data="filtered" stripe><el-table-column prop="demandNo" label="需求编号" width="165" /><el-table-column label="来源" width="105"><template #default="scope"><StatusBadge :label="sourceMeta(scope.row.source).label" :tone="sourceMeta(scope.row.source).tone" /></template></el-table-column><el-table-column label="物料" min-width="190"><template #default="scope"><strong>{{ scope.row.materialName }}</strong><div class="muted">{{ scope.row.materialCode }}</div></template></el-table-column><el-table-column label="数量" width="110" align="right"><template #default="scope"><span class="number">{{ scope.row.quantity.toLocaleString() }}</span></template></el-table-column><el-table-column prop="dueDate" label="需求日期" width="120" /><el-table-column label="状态" width="115"><template #default="scope"><StatusBadge :label="statusMeta(scope.row.status).label" :tone="statusMeta(scope.row.status).tone" /></template></el-table-column><el-table-column prop="requester" label="申请人" width="100" /><el-table-column prop="createdAt" label="创建时间" width="170" /><el-table-column label="操作" width="90" fixed="right"><template #default="scope"><el-button link type="primary" @click="showDetail(scope.row)">查看</el-button></template></el-table-column></el-table></div>
    </section>
    <el-dialog v-model="dialogVisible" title="新建采购需求" width="520px" destroy-on-close><el-form ref="formRef" :model="form" :rules="rules" label-position="top"><div class="form-grid"><el-form-item label="物料编码" prop="materialCode"><el-input v-model="form.materialCode" placeholder="如 M-2048" /></el-form-item><el-form-item label="物料名称" prop="materialName"><el-input v-model="form.materialName" /></el-form-item><el-form-item label="需求数量" prop="quantity"><el-input-number v-model="form.quantity" :min="1" style="width:100%" /></el-form-item><el-form-item label="需求日期" prop="dueDate"><el-date-picker v-model="form.dueDate" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item></div><div class="inline-note">创建后默认为草稿，不会自动生成采购计划或订单。</div></el-form><template #footer><el-button @click="dialogVisible = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存草稿</el-button></template></el-dialog>
    <el-drawer v-model="detailVisible" title="采购需求详情" size="420px"><template v-if="selected"><div class="drawer-title"><div class="drawer-code">{{ selected.demandNo }}</div><h2>{{ selected.materialName }}</h2><StatusBadge :label="statusMeta(selected.status).label" :tone="statusMeta(selected.status).tone" /></div><dl class="drawer-list"><div><dt>物料编码</dt><dd>{{ selected.materialCode }}</dd></div><div><dt>数量</dt><dd>{{ selected.quantity.toLocaleString() }}</dd></div><div><dt>需求日期</dt><dd>{{ selected.dueDate }}</dd></div><div><dt>来源</dt><dd>{{ sourceMeta(selected.source).label }}</dd></div><div><dt>申请人</dt><dd>{{ selected.requester }}</dd></div></dl><div v-if="selected.status === 'DRAFT' && auth.role === 'BUYER'" class="inline-note">草稿需求会在“采购计划”中选择供应商后纳入计划并提交审批。</div></template></el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { DataAnalysis, Plus, Refresh, Search, WarningFilled } from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'
import PageHeader from '@/components/PageHeader.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import EmptyState from '@/components/EmptyState.vue'
import { apiList, apiMutate, extractApiError } from '@/services/api'
import { demoPurchaseDemands } from '@/services/demo'
import { mapDemand } from '@/services/mappers'
import { useAuthStore } from '@/stores/auth'
import type { PurchaseDemand } from '@/types'

const router = useRouter(); const auth = useAuthStore(); const demands = ref<PurchaseDemand[]>([]); const keyword = ref(''); const statusFilter = ref(''); const sourceFilter = ref(''); const loading = ref(false); const saving = ref(false); const errorMessage = ref(''); const dialogVisible = ref(false); const detailVisible = ref(false); const selected = ref<PurchaseDemand>(); const formRef = ref<FormInstance>()
const form = reactive({ materialCode: '', materialName: '', quantity: 1, dueDate: '' }); const rules: FormRules = { materialCode: [{ required: true, message: '请输入物料编码', trigger: 'blur' }], materialName: [{ required: true, message: '请输入物料名称', trigger: 'blur' }], quantity: [{ required: true, message: '请输入数量', trigger: 'change' }], dueDate: [{ required: true, message: '请选择需求日期', trigger: 'change' }] }
const statusOptions = [{ value: 'DRAFT', label: '草稿' }, { value: 'SUBMITTED', label: '待审批' }, { value: 'PLANNED', label: '已纳入计划' }, { value: 'APPROVED', label: '已批准' }, { value: 'REJECTED', label: '已驳回' }, { value: 'CLOSED', label: '已关闭' }]
const filtered = computed(() => demands.value.filter((item) => (!statusFilter.value || item.status === statusFilter.value) && (!sourceFilter.value || item.source === sourceFilter.value) && (!keyword.value || `${item.demandNo}${item.materialCode}${item.materialName}`.toLowerCase().includes(keyword.value.toLowerCase()))))
const hasFilters = computed(() => Boolean(keyword.value || statusFilter.value || sourceFilter.value))
const emptyDescription = computed(() => hasFilters.value ? '当前筛选条件下没有匹配记录。' : auth.role === 'BUYER' ? '可以从库存短缺、预测建议或人工输入创建第一条需求。' : '当前可见范围内还没有采购需求；管理角色仅可查看。')
const emptyActionText = computed(() => hasFilters.value ? '清空筛选' : auth.role === 'BUYER' ? '新建需求' : '重新加载')
async function load() { loading.value = true; errorMessage.value = ''; try { const result = await apiList<PurchaseDemand>({ method: 'GET', url: '/purchase-demands' }, mapDemand, () => demoPurchaseDemands); demands.value = result.records } catch (error) { errorMessage.value = extractApiError(error) } finally { loading.value = false } }
function openCreate() { if (auth.role !== 'BUYER') return; Object.assign(form, { materialCode: '', materialName: '', quantity: 1, dueDate: '' }); dialogVisible.value = true }
function handleEmptyAction() { if (hasFilters.value) { keyword.value = ''; statusFilter.value = ''; sourceFilter.value = '' } else if (auth.role === 'BUYER') openCreate(); else load() }
function showDetail(item: PurchaseDemand) { selected.value = item; detailVisible.value = true }
async function save() { const valid = await formRef.value?.validate().catch(() => false); if (!valid) return; saving.value = true; try { const raw = await apiMutate<unknown>({ method: 'POST', url: '/purchase-demands', data: { materialCode: form.materialCode, quantity: form.quantity, expectedDate: form.dueDate, priority: 'NORMAL', notes: '前端手工创建' } }, () => ({ id: `req-demo-${Date.now()}`, demandNo: `PR-DEMO-${Date.now().toString().slice(-6)}`, source: 'MANUAL', ...form, status: 'DRAFT', requester: '当前用户', createdAt: new Date().toLocaleString('zh-CN', { hour12: false }) })); const result = mapDemand(raw); demands.value = [result, ...demands.value]; dialogVisible.value = false; ElMessage.success('采购需求草稿已保存') } catch (error) { errorMessage.value = extractApiError(error) } finally { saving.value = false } }
function sourceMeta(value: PurchaseDemand['source']) { return ({ FORECAST: { label: '预测建议', tone: 'olive' }, SCENARIO: { label: '情景推演', tone: 'warning' }, MANUAL: { label: '人工创建', tone: 'neutral' }, AI_PARSE: { label: 'AI 解析', tone: 'blue' } } as const)[value] }
function statusMeta(value: PurchaseDemand['status']) { return ({ DRAFT: { label: '草稿', tone: 'neutral' }, SUBMITTED: { label: '待审批', tone: 'warning' }, PLANNED: { label: '已纳入计划', tone: 'blue' }, APPROVED: { label: '已批准', tone: 'success' }, REJECTED: { label: '已驳回', tone: 'danger' }, CLOSED: { label: '已关闭', tone: 'blue' } } as const)[value] }
onMounted(load)
</script>

<style scoped>
.demand-table { overflow: hidden; }.toolbar-meta { color: var(--lm-muted); font-size: 11px; }.muted { margin-top: 3px; color: var(--lm-muted); font-size: 11px; }.number { font-variant-numeric: tabular-nums; }.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 14px; }.drawer-title { padding-bottom: 18px; border-bottom: 1px solid var(--lm-border); }.drawer-code { color: var(--lm-olive); font-size: 11px; font-weight: 750; }.drawer-title h2 { margin: 8px 0 11px; font-size: 20px; }.drawer-list { margin: 5px 0 0; }.drawer-list > div { display: flex; justify-content: space-between; gap: 20px; padding: 14px 0; border-bottom: 1px solid var(--lm-border); }.drawer-list dt { color: var(--lm-muted); font-size: 12px; }.drawer-list dd { margin: 0; font-size: 12px; font-weight: 650; text-align: right; }.drawer-actions { margin-top: 21px; }.inline-note { margin-top: 3px; }
@media (max-width: 600px) { .form-grid { grid-template-columns: 1fr; } }
</style>
