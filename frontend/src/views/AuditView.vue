<template>
  <div>
    <PageHeader title="审计记录" description="记录登录、导入、AI 解析、状态变更与权限拒绝，便于按对象和追踪号回溯。">
      <template #actions><el-button :icon="Refresh" :loading="loading" @click="load">刷新记录</el-button></template>
    </PageHeader>
    <section class="surface audit-table"><div class="toolbar"><el-input v-model="keyword" clearable placeholder="搜索动作、对象或追踪号" :prefix-icon="Search" /><el-select v-model="resultFilter" clearable placeholder="全部结果" style="width: 130px"><el-option label="成功" value="SUCCESS" /><el-option label="失败" value="FAILED" /><el-option label="已拦截" value="BLOCKED" /></el-select><el-select v-model="roleFilter" clearable placeholder="全部角色" style="width: 145px"><el-option label="系统管理员" value="ADMIN" /><el-option label="采购协同人员" value="BUYER" /><el-option label="供应商" value="SUPPLIER" /><el-option label="管理人员" value="MANAGER" /></el-select><span class="toolbar-spacer"></span><span class="toolbar-meta">{{ filtered.length }} 条记录</span></div><div v-if="errorMessage" class="inline-error"><WarningFilled />{{ errorMessage }}</div><div v-if="loading && !records.length" class="table-loading"><div v-for="i in 5" :key="i" class="skeleton" style="height: 43px"></div></div><div v-else-if="!filtered.length"><EmptyState title="暂无审计记录" description="操作发生后，服务端会返回对应的审计记录与追踪号。" action-text="重新加载" @action="load" /></div><div v-else class="table-wrap"><el-table :data="filtered" stripe><el-table-column prop="occurredAt" label="发生时间" width="170" /><el-table-column label="动作" min-width="185"><template #default="scope"><strong>{{ scope.row.action }}</strong><div class="muted">{{ scope.row.module }}</div></template></el-table-column><el-table-column label="操作者" width="125"><template #default="scope"><div>{{ scope.row.actor }}</div><small class="muted">{{ roleMeta(scope.row.role) }}</small></template></el-table-column><el-table-column prop="target" label="对象" min-width="190" show-overflow-tooltip /><el-table-column label="结果" width="105"><template #default="scope"><StatusBadge :label="resultMeta(scope.row.result).label" :tone="resultMeta(scope.row.result).tone" /></template></el-table-column><el-table-column prop="traceId" label="追踪号" width="120" /><el-table-column label="详情" min-width="250" show-overflow-tooltip><template #default="scope">{{ scope.row.detail }}</template></el-table-column></el-table></div></section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Refresh, Search, WarningFilled } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import EmptyState from '@/components/EmptyState.vue'
import { apiList, extractApiError } from '@/services/api'
import { demoAudit } from '@/services/demo'
import { mapAudit } from '@/services/mappers'
import type { AuditRecord } from '@/types'

const records = ref<AuditRecord[]>([]); const keyword = ref(''); const resultFilter = ref(''); const roleFilter = ref(''); const loading = ref(false); const errorMessage = ref('')
const filtered = computed(() => records.value.filter((item) => (!resultFilter.value || item.result === resultFilter.value) && (!roleFilter.value || item.role === roleFilter.value) && (!keyword.value || `${item.action}${item.module}${item.target}${item.traceId}${item.detail}`.toLowerCase().includes(keyword.value.toLowerCase()))))
async function load() { loading.value = true; errorMessage.value = ''; try { const result = await apiList<AuditRecord>({ method: 'GET', url: '/audit', params: { page: 0, size: 30 } }, mapAudit, () => demoAudit); records.value = result.records } catch (error) { errorMessage.value = extractApiError(error) } finally { loading.value = false } }
function roleMeta(value: AuditRecord['role']) { return ({ ADMIN: '系统管理员', BUYER: '采购协同人员', SUPPLIER: '供应商', MANAGER: '企业管理人员' } as const)[value] }
function resultMeta(value: AuditRecord['result']) { return ({ SUCCESS: { label: '成功', tone: 'success' }, FAILED: { label: '失败', tone: 'danger' }, BLOCKED: { label: '已拦截', tone: 'warning' }, RECORDED: { label: '已记录', tone: 'neutral' } } as const)[value] }
onMounted(load)
</script>

<style scoped>
.audit-table { overflow: hidden; }.toolbar-meta { color: var(--lm-muted); font-size: 11px; }.muted { margin-top: 3px; color: var(--lm-muted); font-size: 11px; }
</style>
