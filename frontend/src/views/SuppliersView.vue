<template>
  <div>
    <PageHeader title="供应商" description="维护合作方主档与履约信号；供应商账号只能访问绑定 supplier_id 的业务数据.">
      <template #actions><el-button v-if="auth.role === 'BUYER'" type="primary" :icon="Plus" @click="openCreate">新增供应商</el-button></template>
    </PageHeader>
    <section class="surface supplier-table">
      <div class="toolbar"><el-input v-model="keyword" clearable placeholder="搜索名称、编码或联系人" :prefix-icon="Search" /><el-select v-model="statusFilter" clearable placeholder="全部状态" style="width: 135px"><el-option label="启用" value="ACTIVE" /><el-option label="待审核" value="PENDING" /><el-option label="停用" value="INACTIVE" /></el-select><span class="toolbar-spacer"></span><span class="toolbar-meta">共 {{ filteredSuppliers.length }} 家</span><el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button></div>
      <div v-if="errorMessage" class="inline-error"><WarningFilled />{{ errorMessage }}</div>
      <div v-if="loading && !suppliers.length" class="table-loading"><div v-for="i in 4" :key="i" class="skeleton" style="height: 42px"></div></div>
      <div v-else-if="!filteredSuppliers.length"><EmptyState title="没有匹配的供应商" :description="emptyDescription" :action-text="emptyActionText" @action="handleEmptyAction" /></div>
      <div v-else class="table-wrap"><el-table :data="filteredSuppliers" stripe><el-table-column prop="code" label="编码" width="110" /><el-table-column prop="name" label="供应商" min-width="190" show-overflow-tooltip /><el-table-column prop="category" label="类别" width="120" /><el-table-column label="联系人" width="160"><template #default="scope"><div>{{ scope.row.contactName }}</div><small class="muted">{{ scope.row.contactPhone }}</small></template></el-table-column><el-table-column label="准时交付率" width="125" align="right"><template #default="scope"><span class="number">{{ scope.row.onTimeRate.toFixed(1) }}%</span></template></el-table-column><el-table-column label="未结订单" width="100" align="right"><template #default="scope"><span class="number">{{ scope.row.openOrders }}</span></template></el-table-column><el-table-column label="状态" width="110"><template #default="scope"><StatusBadge :label="statusMeta(scope.row.status).label" :tone="statusMeta(scope.row.status).tone" /></template></el-table-column><el-table-column prop="updatedAt" label="更新时间" width="170" /><el-table-column label="操作" width="95" fixed="right"><template #default="scope"><el-button link type="primary" @click="showDetail(scope.row)">详情</el-button></template></el-table-column></el-table></div>
    </section>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑供应商' : '新增供应商'" width="520px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top"><div class="form-grid"><el-form-item label="供应商编码" prop="code"><el-input v-model="form.code" placeholder="如 SUP-005" /></el-form-item><el-form-item label="名称" prop="name"><el-input v-model="form.name" placeholder="请输入企业全称" /></el-form-item><el-form-item label="类别" prop="category"><el-input v-model="form.category" placeholder="如 电子元件" /></el-form-item><el-form-item label="联系人" prop="contactName"><el-input v-model="form.contactName" /></el-form-item><el-form-item label="联系电话" prop="contactPhone"><el-input v-model="form.contactPhone" /></el-form-item><el-form-item label="状态" prop="status"><el-select v-model="form.status" style="width:100%"><el-option label="启用" value="ACTIVE" /><el-option label="待审核" value="PENDING" /><el-option label="停用" value="INACTIVE" /></el-select></el-form-item></div></el-form>
      <template #footer><el-button @click="dialogVisible = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存供应商</el-button></template>
    </el-dialog>
    <el-drawer v-model="detailVisible" title="供应商详情" size="420px"><template v-if="selected"><div class="drawer-title"><div class="drawer-code">{{ selected.code }}</div><h2>{{ selected.name }}</h2><StatusBadge :label="statusMeta(selected.status).label" :tone="statusMeta(selected.status).tone" /></div><dl class="drawer-list"><div><dt>类别</dt><dd>{{ selected.category }}</dd></div><div><dt>联系人</dt><dd>{{ selected.contactName }} · {{ selected.contactPhone }}</dd></div><div><dt>准时交付率</dt><dd>{{ selected.onTimeRate.toFixed(1) }}%</dd></div><div><dt>未结订单</dt><dd>{{ selected.openOrders }} 笔</dd></div><div><dt>更新时间</dt><dd>{{ selected.updatedAt }}</dd></div></dl><div class="inline-note">准时交付率口径：过去 30 天已完成或部分完成订单，按承诺交期比较到货时间。</div></template></el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { Plus, Refresh, Search, WarningFilled } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import EmptyState from '@/components/EmptyState.vue'
import { apiList, apiMutate, extractApiError } from '@/services/api'
import { demoSuppliers } from '@/services/demo'
import { mapSupplier } from '@/services/mappers'
import { useAuthStore } from '@/stores/auth'
import type { Supplier } from '@/types'

const auth = useAuthStore(); const suppliers = ref<Supplier[]>([]); const keyword = ref(''); const statusFilter = ref(''); const loading = ref(false); const errorMessage = ref('')
const dialogVisible = ref(false); const detailVisible = ref(false); const saving = ref(false); const editing = ref(false); const selected = ref<Supplier>(); const formRef = ref<FormInstance>()
const form = reactive({ id: '', code: '', name: '', category: '', contactName: '', contactPhone: '', status: 'PENDING' as Supplier['status'] })
const rules: FormRules = { code: [{ required: true, message: '请输入供应商编码', trigger: 'blur' }], name: [{ required: true, message: '请输入供应商名称', trigger: 'blur' }], category: [{ required: true, message: '请输入供应商类别', trigger: 'blur' }], contactName: [{ required: true, message: '请输入联系人', trigger: 'blur' }] }
const filteredSuppliers = computed(() => suppliers.value.filter((item) => (!statusFilter.value || item.status === statusFilter.value) && (!keyword.value || `${item.code}${item.name}${item.contactName}`.toLowerCase().includes(keyword.value.toLowerCase()))))
const hasFilters = computed(() => Boolean(keyword.value || statusFilter.value))
const emptyDescription = computed(() => hasFilters.value ? '当前筛选条件下没有匹配记录。' : auth.role === 'BUYER' ? '可以新增第一条供应商主档。' : '当前可见范围内还没有供应商主档；管理角色仅可查看。')
const emptyActionText = computed(() => hasFilters.value ? '清空筛选' : auth.role === 'BUYER' ? '新增供应商' : '重新加载')

async function load() { loading.value = true; errorMessage.value = ''; try { const result = await apiList<Supplier>({ method: 'GET', url: '/suppliers' }, mapSupplier, () => demoSuppliers); suppliers.value = result.records } catch (error) { errorMessage.value = extractApiError(error) } finally { loading.value = false } }
function openCreate() { if (auth.role !== 'BUYER') return; editing.value = false; Object.assign(form, { id: '', code: '', name: '', category: '', contactName: '', contactPhone: '', status: 'PENDING' }); dialogVisible.value = true }
function handleEmptyAction() { if (hasFilters.value) { keyword.value = ''; statusFilter.value = '' } else if (auth.role === 'BUYER') openCreate(); else load() }
function showDetail(item: Supplier) { selected.value = item; detailVisible.value = true }
async function save() { const valid = await formRef.value?.validate().catch(() => false); if (!valid) return; saving.value = true; try { const payload = { supplierCode: form.code, supplierName: form.name, contactName: form.contactName, contactPhone: form.contactPhone, levelCode: form.category }; const raw = await apiMutate<unknown>({ method: 'POST', url: '/suppliers', data: payload }, () => ({ ...form, id: form.id || `sup-demo-${Date.now()}`, code: form.code, name: form.name, onTimeRate: 0, openOrders: 0, updatedAt: new Date().toLocaleString('zh-CN', { hour12: false }) })); const result = mapSupplier(raw); if (editing.value) suppliers.value = suppliers.value.map((item) => item.id === result.id ? result : item); else suppliers.value = [result, ...suppliers.value]; dialogVisible.value = false; ElMessage.success('供应商主档已保存') } catch (error) { errorMessage.value = extractApiError(error) } finally { saving.value = false } }
function statusMeta(status: Supplier['status']) { return ({ ACTIVE: { label: '启用', tone: 'success' }, PENDING: { label: '待审核', tone: 'warning' }, INACTIVE: { label: '停用', tone: 'neutral' } } as const)[status] }
onMounted(load)
</script>

<style scoped>
.supplier-table { overflow: hidden; }
.toolbar-meta { color: var(--lm-muted); font-size: 11px; }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 14px; }
.drawer-title { padding-bottom: 20px; border-bottom: 1px solid var(--lm-border); }
.drawer-code { color: var(--lm-olive); font-size: 11px; font-weight: 750; letter-spacing: 0.05em; }
.drawer-title h2 { margin: 8px 0 11px; font-size: 21px; }
.drawer-list { margin: 0; }
.drawer-list > div { display: flex; justify-content: space-between; gap: 20px; padding: 14px 0; border-bottom: 1px solid var(--lm-border); }
.drawer-list dt { color: var(--lm-muted); font-size: 12px; }
.drawer-list dd { margin: 0; font-size: 12px; font-weight: 650; text-align: right; }
.drawer-title + .drawer-list { margin-top: 5px; }
.inline-note { margin-top: 20px; }
@media (max-width: 600px) { .form-grid { grid-template-columns: 1fr; } }
</style>
