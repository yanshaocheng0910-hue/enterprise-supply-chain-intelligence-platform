<template>
  <div>
    <PageHeader title="物料" description="统一物料编码、规格、单位、安全库存与采购提前期，为预测和建议提供同一口径。">
      <template #actions><el-button v-if="auth.role === 'BUYER'" type="primary" :icon="Plus" @click="openCreate">新增物料</el-button></template>
    </PageHeader>
    <section class="surface material-table">
      <div class="toolbar"><el-input v-model="keyword" clearable placeholder="搜索物料编码、名称或规格" :prefix-icon="Search" /><el-select v-model="statusFilter" clearable placeholder="全部状态" style="width: 135px"><el-option label="启用" value="ACTIVE" /><el-option label="停用" value="INACTIVE" /></el-select><span class="toolbar-spacer"></span><span class="toolbar-meta">共 {{ filteredMaterials.length }} 种</span><el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button></div>
      <div v-if="errorMessage" class="inline-error"><WarningFilled />{{ errorMessage }}</div>
      <div v-if="loading && !materials.length" class="table-loading"><div v-for="i in 4" :key="i" class="skeleton" style="height: 42px"></div></div>
      <div v-else-if="!filteredMaterials.length"><EmptyState title="没有匹配的物料" :description="emptyDescription" :action-text="emptyActionText" @action="handleEmptyAction" /></div>
      <div v-else class="table-wrap"><el-table :data="filteredMaterials" stripe><el-table-column prop="code" label="物料编码" width="120" /><el-table-column prop="name" label="物料名称" min-width="175" /><el-table-column prop="specification" label="规格" min-width="190" show-overflow-tooltip /><el-table-column prop="category" label="类别" width="110" /><el-table-column prop="unit" label="单位" width="75" align="center" /><el-table-column label="安全库存" width="110" align="right"><template #default="scope"><span class="number">{{ scope.row.safetyStock.toLocaleString() }}</span></template></el-table-column><el-table-column label="提前期" width="95" align="right"><template #default="scope"><span class="number">{{ scope.row.leadTimeDays }} 天</span></template></el-table-column><el-table-column label="状态" width="100"><template #default="scope"><StatusBadge :label="scope.row.status === 'ACTIVE' ? '启用' : '停用'" :tone="scope.row.status === 'ACTIVE' ? 'success' : 'neutral'" /></template></el-table-column><el-table-column prop="updatedAt" label="更新时间" width="170" /></el-table></div>
    </section>
    <el-dialog v-model="dialogVisible" :title="editing ? '编辑物料' : '新增物料'" width="520px" destroy-on-close><el-form ref="formRef" :model="form" :rules="rules" label-position="top"><div class="form-grid"><el-form-item label="物料编码" prop="code"><el-input v-model="form.code" placeholder="如 M-6001" /></el-form-item><el-form-item label="物料名称" prop="name"><el-input v-model="form.name" /></el-form-item><el-form-item label="规格" prop="specification"><el-input v-model="form.specification" /></el-form-item><el-form-item label="类别" prop="category"><el-input v-model="form.category" /></el-form-item><el-form-item label="单位" prop="unit"><el-input v-model="form.unit" placeholder="个 / 件 / 罐" /></el-form-item><el-form-item label="安全库存" prop="safetyStock"><el-input-number v-model="form.safetyStock" :min="0" style="width:100%" /></el-form-item><el-form-item label="最小采购量" prop="minOrderQty"><el-input-number v-model="form.minOrderQty" :min="0.0001" :precision="2" style="width:100%" /></el-form-item><el-form-item label="包装量" prop="packSize"><el-input-number v-model="form.packSize" :min="0.0001" :precision="2" style="width:100%" /></el-form-item><el-form-item label="标准价" prop="standardPrice"><el-input-number v-model="form.standardPrice" :min="0" :precision="2" style="width:100%" /></el-form-item><el-form-item label="采购提前期（天）" prop="leadTimeDays"><el-input-number v-model="form.leadTimeDays" :min="0" style="width:100%" /></el-form-item><el-form-item label="状态" prop="status"><el-select v-model="form.status" style="width:100%"><el-option label="启用" value="ACTIVE" /><el-option label="停用" value="INACTIVE" /></el-select></el-form-item></div></el-form><template #footer><el-button @click="dialogVisible = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存物料</el-button></template></el-dialog>
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
import { demoMaterials } from '@/services/demo'
import { mapMaterial } from '@/services/mappers'
import { useAuthStore } from '@/stores/auth'
import type { Material } from '@/types'

const auth = useAuthStore(); const materials = ref<Material[]>([]); const keyword = ref(''); const statusFilter = ref(''); const loading = ref(false); const saving = ref(false); const errorMessage = ref(''); const dialogVisible = ref(false); const editing = ref(false); const formRef = ref<FormInstance>()
const form = reactive({ id: '', code: '', name: '', specification: '', unit: '', category: '', safetyStock: 0, minOrderQty: 1, packSize: 1, standardPrice: 0, leadTimeDays: 1, status: 'ACTIVE' as Material['status'] })
const rules: FormRules = { code: [{ required: true, message: '请输入物料编码', trigger: 'blur' }], name: [{ required: true, message: '请输入物料名称', trigger: 'blur' }], category: [{ required: true, message: '请输入类别', trigger: 'blur' }], unit: [{ required: true, message: '请输入单位', trigger: 'blur' }], safetyStock: [{ required: true, message: '请输入安全库存', trigger: 'change' }], minOrderQty: [{ required: true, message: '请输入最小采购量', trigger: 'change' }], packSize: [{ required: true, message: '请输入包装量', trigger: 'change' }], leadTimeDays: [{ required: true, message: '请输入提前期', trigger: 'change' }], standardPrice: [{ required: true, message: '请输入标准价', trigger: 'change' }] }
const filteredMaterials = computed(() => materials.value.filter((item) => (!statusFilter.value || item.status === statusFilter.value) && (!keyword.value || `${item.code}${item.name}${item.specification}`.toLowerCase().includes(keyword.value.toLowerCase()))))
const hasFilters = computed(() => Boolean(keyword.value || statusFilter.value))
const emptyDescription = computed(() => hasFilters.value ? '当前筛选条件下没有匹配记录。' : auth.role === 'BUYER' ? '可以新增第一条物料主档。' : '当前可见范围内还没有物料主档；管理角色仅可查看。')
const emptyActionText = computed(() => hasFilters.value ? '清空筛选' : auth.role === 'BUYER' ? '新增物料' : '重新加载')
async function load() { loading.value = true; errorMessage.value = ''; try { const result = await apiList<Material>({ method: 'GET', url: '/materials' }, mapMaterial, () => demoMaterials); materials.value = result.records } catch (error) { errorMessage.value = extractApiError(error) } finally { loading.value = false } }
function openCreate() { if (auth.role !== 'BUYER') return; editing.value = false; Object.assign(form, { id: '', code: '', name: '', specification: '', unit: '', category: '', safetyStock: 0, minOrderQty: 1, packSize: 1, standardPrice: 0, leadTimeDays: 1, status: 'ACTIVE' }); dialogVisible.value = true }
function handleEmptyAction() { if (hasFilters.value) { keyword.value = ''; statusFilter.value = '' } else if (auth.role === 'BUYER') openCreate(); else load() }
async function save() { const valid = await formRef.value?.validate().catch(() => false); if (!valid) return; saving.value = true; try { const payload = { materialCode: form.code, materialName: form.name, category: form.category, unit: form.unit, safetyStock: form.safetyStock, minOrderQty: form.minOrderQty, packSize: form.packSize, leadTimeDays: form.leadTimeDays, standardPrice: form.standardPrice }; const raw = await apiMutate<unknown>({ method: 'POST', url: '/materials', data: payload }, () => ({ ...form, id: form.id || `mat-demo-${Date.now()}`, code: form.code, name: form.name, updatedAt: new Date().toLocaleString('zh-CN', { hour12: false }) })); const result = mapMaterial(raw); if (editing.value) materials.value = materials.value.map((item) => item.id === result.id ? result : item); else materials.value = [result, ...materials.value]; dialogVisible.value = false; ElMessage.success('物料主档已保存') } catch (error) { errorMessage.value = extractApiError(error) } finally { saving.value = false } }
onMounted(load)
</script>

<style scoped>
.material-table { overflow: hidden; }
.toolbar-meta { color: var(--lm-muted); font-size: 11px; }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 14px; }
@media (max-width: 600px) { .form-grid { grid-template-columns: 1fr; } }
</style>
