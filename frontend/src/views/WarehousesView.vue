<template>
  <div>
    <PageHeader title="仓库" description="维护收货与库存事实使用的仓库主档。采购可新增或启停，管理者只读查看。">
      <template #actions><el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button><el-button v-if="auth.role === 'BUYER'" type="primary" :icon="Plus" @click="openCreate">新增仓库</el-button></template>
    </PageHeader>
    <section class="surface warehouse-table">
      <div v-if="errorMessage" class="inline-error" role="alert"><WarningFilled /><span>{{ errorMessage }}</span><el-button link type="danger" @click="load">重试</el-button></div>
      <div v-else-if="loading && !warehouses.length" class="table-loading"><div v-for="i in 3" :key="i" class="skeleton" style="height: 45px"></div></div>
      <div v-else-if="!warehouses.length"><EmptyState title="暂无仓库主档" description="后端未返回仓库记录。采购角色可以新增仓库。" action-text="重新加载" @action="load" /></div>
      <div v-else class="table-wrap"><el-table :data="warehouses" stripe><el-table-column prop="warehouseCode" label="仓库编码" width="145" /><el-table-column prop="warehouseName" label="仓库名称" min-width="190" /><el-table-column prop="locationText" label="位置说明" min-width="220"><template #default="scope">{{ scope.row.locationText || '—' }}</template></el-table-column><el-table-column label="状态" width="100"><template #default="scope"><StatusBadge :label="scope.row.status === 'ACTIVE' ? '启用' : '停用'" :tone="scope.row.status === 'ACTIVE' ? 'success' : 'neutral'" /></template></el-table-column><el-table-column prop="version" label="版本" width="70" align="right" /><el-table-column prop="updatedAt" label="更新时间" width="170"><template #default="scope">{{ scope.row.updatedAt || scope.row.createdAt || '—' }}</template></el-table-column><el-table-column v-if="auth.role === 'BUYER'" label="操作" width="150" fixed="right"><template #default="scope"><el-button link type="primary" @click="openEdit(scope.row)">编辑</el-button><el-button link :type="scope.row.status === 'ACTIVE' ? 'danger' : 'primary'" @click="toggleStatus(scope.row)">{{ scope.row.status === 'ACTIVE' ? '停用' : '启用' }}</el-button></template></el-table-column></el-table></div>
    </section>
    <el-dialog v-model="dialogVisible" :title="editing ? '编辑仓库' : '新增仓库'" width="440px"><el-form ref="formRef" :model="form" :rules="rules" label-position="top"><el-form-item v-if="!editing" label="仓库编码" prop="warehouseCode"><el-input v-model="form.warehouseCode" placeholder="如 WH-EAST-01" /></el-form-item><el-form-item label="仓库名称" prop="warehouseName"><el-input v-model="form.warehouseName" placeholder="请输入仓库名称" /></el-form-item><el-form-item label="位置说明"><el-input v-model="form.locationText" placeholder="园区、楼栋或区域" /></el-form-item><el-form-item v-if="editing" label="状态"><el-select v-model="form.status" style="width:100%"><el-option label="启用" value="ACTIVE" /><el-option label="停用" value="INACTIVE" /></el-select></el-form-item><div v-if="editing" class="inline-note">保存时会携带当前版本号 {{ form.version }}，服务端会拒绝过期更新。</div></el-form><template #footer><el-button @click="dialogVisible = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template></el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { Plus, Refresh, WarningFilled } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import EmptyState from '@/components/EmptyState.vue'
import { apiList, apiMutate, extractApiError } from '@/services/api'
import { useAuthStore } from '@/stores/auth'

interface Warehouse { id: string; warehouseCode: string; warehouseName: string; locationText?: string; status: 'ACTIVE' | 'INACTIVE'; version: number; createdAt?: string; updatedAt?: string }
const auth = useAuthStore(); const warehouses = ref<Warehouse[]>([]); const loading = ref(false); const saving = ref(false); const errorMessage = ref(''); const dialogVisible = ref(false); const editing = ref(false); const formRef = ref<FormInstance>()
const form = reactive({ id: '', warehouseCode: '', warehouseName: '', locationText: '', status: 'ACTIVE' as Warehouse['status'], version: 0 }); const rules: FormRules = { warehouseCode: [{ required: true, message: '请输入仓库编码', trigger: 'blur' }], warehouseName: [{ required: true, message: '请输入仓库名称', trigger: 'blur' }] }
function mapWarehouse(raw: unknown): Warehouse { const r = raw && typeof raw === 'object' ? raw as Record<string, unknown> : {}; return { id: String(r.id ?? ''), warehouseCode: String(r.warehouseCode ?? ''), warehouseName: String(r.warehouseName ?? ''), locationText: r.locationText ? String(r.locationText) : '', status: String(r.status ?? 'ACTIVE') as Warehouse['status'], version: Number(r.version ?? 0), createdAt: r.createdAt ? String(r.createdAt) : '', updatedAt: r.updatedAt ? String(r.updatedAt) : '' } }
async function load() { loading.value = true; errorMessage.value = ''; try { const result = await apiList<Warehouse>({ method: 'GET', url: '/warehouses' }, mapWarehouse); warehouses.value = result.records } catch (error) { errorMessage.value = extractApiError(error) } finally { loading.value = false } }
function openCreate() { editing.value = false; Object.assign(form, { id: '', warehouseCode: '', warehouseName: '', locationText: '', status: 'ACTIVE', version: 0 }); dialogVisible.value = true }
function openEdit(item: Warehouse) { editing.value = true; Object.assign(form, item); dialogVisible.value = true }
async function save() { const valid = await formRef.value?.validate().catch(() => false); if (!valid) return; saving.value = true; try { const raw = await apiMutate<unknown>({ method: editing.value ? 'PATCH' : 'POST', url: editing.value ? `/warehouses/${form.id}` : '/warehouses', data: editing.value ? { warehouseName: form.warehouseName, locationText: form.locationText, status: form.status, expectedVersion: form.version } : { warehouseCode: form.warehouseCode, warehouseName: form.warehouseName, locationText: form.locationText } }); const result = mapWarehouse(raw); warehouses.value = editing.value ? warehouses.value.map((item) => item.id === result.id ? result : item) : [result, ...warehouses.value]; dialogVisible.value = false; ElMessage.success(editing.value ? '仓库主档已更新' : '仓库已创建') } catch (error) { ElMessage.error(extractApiError(error)) } finally { saving.value = false } }
async function toggleStatus(item: Warehouse) { saving.value = true; try { const raw = await apiMutate<unknown>({ method: 'PATCH', url: `/warehouses/${item.id}`, data: { status: item.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE', expectedVersion: item.version } }); const result = mapWarehouse(raw); warehouses.value = warehouses.value.map((entry) => entry.id === result.id ? result : entry); ElMessage.success(result.status === 'ACTIVE' ? '仓库已启用' : '仓库已停用') } catch (error) { ElMessage.error(extractApiError(error)) } finally { saving.value = false } }
onMounted(load)
</script>

<style scoped>
.warehouse-table { overflow: hidden; }
</style>
