<template>
  <div>
    <PageHeader title="用户与角色" description="仅系统管理员可见。用户启停、角色与供应商绑定由服务端版本校验，并记录审计。">
      <template #actions><el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button><el-button type="primary" :icon="Plus" @click="openCreate">新建用户</el-button></template>
    </PageHeader>

    <div class="inline-note admin-note">供应商角色必须绑定供应商；其他角色不能绑定供应商。密码只在创建时提交，页面不保存或回显密码。</div>
    <section class="surface user-table">
      <div v-if="errorMessage" class="inline-error"><WarningFilled />{{ errorMessage }}</div>
      <div v-if="loading && !users.length" class="table-loading"><div v-for="i in 4" :key="i" class="skeleton" style="height: 45px"></div></div>
      <div v-else-if="!users.length"><EmptyState title="暂无用户" description="服务端没有返回用户记录。" action-text="重新加载" @action="load" /></div>
      <div v-else class="table-wrap"><el-table :data="users" stripe><el-table-column prop="username" label="账号" width="130" /><el-table-column prop="displayName" label="显示名称" min-width="170" /><el-table-column label="角色" width="145"><template #default="scope"><StatusBadge :label="roleLabel(scope.row.roleCode)" :tone="scope.row.roleCode === 'ADMIN' ? 'blue' : scope.row.roleCode === 'SUPPLIER' ? 'olive' : 'neutral'" /></template></el-table-column><el-table-column prop="supplierName" label="绑定供应商" min-width="180"><template #default="scope">{{ scope.row.supplierName || '—' }}</template></el-table-column><el-table-column label="状态" width="95"><template #default="scope"><StatusBadge :label="scope.row.enabled ? '启用' : '停用'" :tone="scope.row.enabled ? 'success' : 'danger'" /></template></el-table-column><el-table-column prop="lastLoginAt" label="最近登录" width="170"><template #default="scope">{{ scope.row.lastLoginAt || '尚未登录' }}</template></el-table-column><el-table-column prop="version" label="版本" width="70" align="right" /><el-table-column label="操作" width="170" fixed="right"><template #default="scope"><el-button link type="primary" @click="openEdit(scope.row)">编辑</el-button><el-button link :type="scope.row.enabled ? 'danger' : 'primary'" @click="toggleUser(scope.row)">{{ scope.row.enabled ? '停用' : '启用' }}</el-button></template></el-table-column></el-table></div>
    </section>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑用户' : '新建用户'" width="470px"><el-form ref="formRef" :model="form" :rules="rules" label-position="top"><div class="form-grid"><el-form-item label="账号" prop="username"><el-input v-model="form.username" :disabled="editing" placeholder="登录账号" /></el-form-item><el-form-item label="显示名称" prop="displayName"><el-input v-model="form.displayName" placeholder="工作台展示名称" /></el-form-item><el-form-item v-if="!editing" label="初始密码" prop="password"><el-input v-model="form.password" type="password" show-password autocomplete="new-password" placeholder="8–72 位" /></el-form-item><el-form-item label="角色" prop="roleCode"><el-select v-model="form.roleCode" style="width:100%"><el-option v-for="item in roleOptions" :key="item.roleCode" :label="`${item.roleCode} · ${item.roleName}`" :value="item.roleCode" /></el-select></el-form-item><el-form-item v-if="form.roleCode === 'SUPPLIER'" label="供应商 ID" prop="supplierId"><el-input-number v-model="form.supplierId" :min="1" :precision="0" controls-position="right" style="width:100%" placeholder="绑定 supplier.id" /></el-form-item><el-form-item v-if="editing" label="账号状态"><el-switch v-model="form.enabled" active-text="启用" inactive-text="停用" /></el-form-item></div><div v-if="editing" class="inline-note">保存时会携带当前版本号 {{ form.version }}；版本冲突时需刷新后重试。</div></el-form><template #footer><el-button @click="dialogVisible = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template></el-dialog>
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
import type { UserRole } from '@/types'

interface AdminUser { id: string; username: string; displayName: string; roleCode: UserRole; supplierId?: number; supplierName?: string; enabled: boolean; lastLoginAt?: string; version: number; createdAt?: string }
interface AdminRole { id: string; roleCode: UserRole; roleName: string; createdAt?: string }

const auth = useAuthStore()
const users = ref<AdminUser[]>([]); const roleOptions = ref<AdminRole[]>([]); const loading = ref(false); const saving = ref(false); const errorMessage = ref(''); const dialogVisible = ref(false); const editing = ref(false); const formRef = ref<FormInstance>()
const form = reactive({ id: '', username: '', displayName: '', password: '', roleCode: 'BUYER' as UserRole, supplierId: undefined as number | undefined, enabled: true, version: 0 })
const rules: FormRules = { username: [{ required: true, message: '请输入账号', trigger: 'blur' }], displayName: [{ required: true, message: '请输入显示名称', trigger: 'blur' }], password: [{ min: 8, max: 72, message: '密码长度为 8–72 位', trigger: 'blur' }], roleCode: [{ required: true, message: '请选择角色', trigger: 'change' }], supplierId: [{ required: true, message: '供应商角色必须绑定供应商 ID', trigger: 'change' }] }
const fallbackRoles: AdminRole[] = [{ id: '1', roleCode: 'ADMIN', roleName: '系统管理员' }, { id: '2', roleCode: 'BUYER', roleName: '采购协同人员' }, { id: '3', roleCode: 'SUPPLIER', roleName: '供应商' }, { id: '4', roleCode: 'MANAGER', roleName: '企业管理人员' }]

function mapUser(raw: unknown): AdminUser { const r = raw && typeof raw === 'object' ? raw as Record<string, unknown> : {}; return { id: String(r.id ?? ''), username: String(r.username ?? ''), displayName: String(r.displayName ?? ''), roleCode: String(r.roleCode ?? 'BUYER') as UserRole, supplierId: r.supplierId === null || r.supplierId === undefined ? undefined : Number(r.supplierId), supplierName: r.supplierName ? String(r.supplierName) : undefined, enabled: Boolean(r.enabled), lastLoginAt: r.lastLoginAt ? String(r.lastLoginAt) : '', version: Number(r.version ?? 0), createdAt: r.createdAt ? String(r.createdAt) : '' } }
function mapRole(raw: unknown): AdminRole { const r = raw && typeof raw === 'object' ? raw as Record<string, unknown> : {}; return { id: String(r.id ?? ''), roleCode: String(r.roleCode ?? 'BUYER') as UserRole, roleName: String(r.roleName ?? r.roleCode ?? ''), createdAt: r.createdAt ? String(r.createdAt) : '' } }
function roleLabel(value: UserRole) { return roleOptions.value.find((item) => item.roleCode === value)?.roleName || ({ ADMIN: '系统管理员', BUYER: '采购协同人员', SUPPLIER: '供应商', MANAGER: '企业管理人员' } as Record<UserRole, string>)[value] || value }

async function load() { loading.value = true; errorMessage.value = ''; try { const [userResult, roleResult] = await Promise.all([apiList<AdminUser>({ method: 'GET', url: '/admin/users' }, mapUser), apiList<AdminRole>({ method: 'GET', url: '/admin/roles' }, mapRole, () => fallbackRoles)]); users.value = userResult.records; roleOptions.value = roleResult.records.length ? roleResult.records : fallbackRoles } catch (error) { errorMessage.value = extractApiError(error) } finally { loading.value = false } }
function openCreate() { editing.value = false; Object.assign(form, { id: '', username: '', displayName: '', password: '', roleCode: 'BUYER', supplierId: undefined, enabled: true, version: 0 }); dialogVisible.value = true }
function openEdit(item: AdminUser) { editing.value = true; Object.assign(form, { id: item.id, username: item.username, displayName: item.displayName, password: '', roleCode: item.roleCode, supplierId: item.supplierId, enabled: item.enabled, version: item.version }); dialogVisible.value = true }
async function save() { const valid = await formRef.value?.validate().catch(() => false); if (!valid) return; if (!editing.value && !form.password) { ElMessage.error('请输入初始密码'); return } if (form.roleCode === 'SUPPLIER' && !form.supplierId) { ElMessage.error('供应商角色必须绑定供应商 ID'); return } saving.value = true; try { const payload = editing.value ? { displayName: form.displayName, roleCode: form.roleCode, supplierId: form.roleCode === 'SUPPLIER' ? form.supplierId : null, enabled: form.enabled, expectedVersion: form.version } : { username: form.username, displayName: form.displayName, password: form.password, roleCode: form.roleCode, supplierId: form.roleCode === 'SUPPLIER' ? form.supplierId : null }; const raw = await apiMutate<unknown>({ method: editing.value ? 'PATCH' : 'POST', url: editing.value ? `/admin/users/${form.id}` : '/admin/users', data: payload }); const result = mapUser(raw); users.value = editing.value ? users.value.map((item) => item.id === result.id ? result : item) : [result, ...users.value]; dialogVisible.value = false; ElMessage.success(editing.value ? '用户信息已更新' : '用户已创建') } catch (error) { ElMessage.error(extractApiError(error)) } finally { saving.value = false } }
async function toggleUser(item: AdminUser) { saving.value = true; try { const raw = await apiMutate<unknown>({ method: 'PATCH', url: `/admin/users/${item.id}`, data: { displayName: item.displayName, roleCode: item.roleCode, supplierId: item.roleCode === 'SUPPLIER' ? item.supplierId : null, enabled: !item.enabled, expectedVersion: item.version } }); const result = mapUser(raw); users.value = users.value.map((entry) => entry.id === result.id ? result : entry); ElMessage.success(result.enabled ? '账号已启用' : '账号已停用') } catch (error) { ElMessage.error(extractApiError(error)) } finally { saving.value = false } }
onMounted(() => { if (auth.role === 'ADMIN') load() })
</script>

<style scoped>
.user-table { overflow: hidden; }
.admin-note { margin-bottom: 14px; }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 14px; }
@media (max-width: 600px) { .form-grid { grid-template-columns: 1fr; } }
</style>
