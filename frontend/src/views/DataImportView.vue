<template>
  <div>
    <PageHeader title="数据导入" description="以 CSV 作为外部数据入口，先校验、再导入；每个批次保留来源与错误明细。">
      <template #actions><el-button :icon="Download" @click="downloadTemplate">下载模板</el-button></template>
    </PageHeader>

    <div class="split-grid import-grid">
      <section class="surface surface-pad">
        <div class="section-heading"><div><h2>创建导入批次</h2><p>支持供应商、物料、库存、需求历史</p></div><StatusBadge label="CSV 适配器" tone="blue" /></div>
        <el-form label-position="top" :model="form" @submit.prevent="submitImport">
          <el-form-item label="数据类型" required><el-select v-model="form.dataType" placeholder="选择数据类型" style="width: 100%"><el-option v-for="item in dataTypes" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
          <el-form-item label="文件" required>
            <input ref="fileInput" class="visually-hidden" type="file" accept=".csv,text/csv" tabindex="-1" aria-hidden="true" @change="handleNativeFile" />
            <button class="file-picker" :class="{ 'is-dragging': dragActive }" type="button" @click="openFilePicker" @dragenter.prevent="dragActive = true" @dragover.prevent="dragActive = true" @dragleave.prevent="dragActive = false" @drop.prevent="handleFileDrop">
              <UploadFilled class="upload-icon" />
              <span class="file-picker__title">{{ file ? '更换 CSV 文件' : '选择 CSV 文件' }}</span>
              <span class="file-picker__hint">点击打开系统文件窗口，也可将 CSV 拖放到这里</span>
            </button>
            <div v-if="file" class="selected-file" aria-live="polite"><Document /><div><strong>{{ file.name }}</strong><span>{{ formatFileSize(file.size) }}</span></div><el-button link type="danger" @click="clearFile">移除</el-button></div>
            <div class="file-tip">UTF-8 编码，首行包含字段名；单文件不超过 20 MB。</div>
          </el-form-item>
          <div class="inline-note"><InfoFilled /> 导入只会写入经过结构、业务规则和唯一性校验的记录；失败行不会进入业务表。</div>
          <div v-if="errorMessage" class="inline-error" role="alert"><WarningFilled />{{ errorMessage }}</div>
          <div class="form-actions"><el-button @click="reset">清空</el-button><el-button type="primary" :loading="loading" :disabled="!file" @click="submitImport">上传并预览校验</el-button></div>
        </el-form>
      </section>

      <section class="surface surface-pad quality-panel">
        <div class="section-heading"><div><h2>导入前检查</h2><p>提交文件后显示字段映射和校验范围</p></div></div>
        <dl class="detail-grid"><div class="detail-item"><dt>当前批次</dt><dd>{{ file?.name || '未选择' }}</dd></div><div class="detail-item"><dt>数据类型</dt><dd>{{ dataTypeLabel }}</dd></div><div class="detail-item"><dt>处理策略</dt><dd>校验失败行隔离</dd></div></dl>
        <div class="quality-list"><div v-for="item in checks" :key="item.label" class="quality-row"><span class="quality-mark" :class="item.tone">{{ item.tone === 'pass' ? '✓' : '!' }}</span><div><strong>{{ item.label }}</strong><p>{{ item.description }}</p></div></div></div>
      </section>
    </div>

    <section class="surface import-history">
      <div class="toolbar"><div><strong>最近导入批次</strong><span class="toolbar-caption">记录来源、成功数与错误数</span></div><span class="toolbar-spacer"></span><el-button :icon="Refresh" :loading="loadingHistory" @click="loadHistory">刷新</el-button></div>
      <div v-if="loadingHistory" class="table-loading"><div v-for="i in 4" :key="i" class="skeleton" style="height: 42px"></div></div>
      <div v-else class="table-wrap"><el-table :data="batches" stripe empty-text="暂无导入批次"><el-table-column prop="fileName" label="文件" min-width="220" show-overflow-tooltip /><el-table-column label="数据类型" width="125"><template #default="scope">{{ dataTypeName(scope.row.dataType) }}</template></el-table-column><el-table-column prop="rowCount" label="总行数" width="95" align="right" /><el-table-column label="成功 / 错误" width="135" align="right"><template #default="scope"><span class="number">{{ scope.row.successCount }}</span> <span class="muted">/ {{ scope.row.errorCount }}</span></template></el-table-column><el-table-column label="状态" width="120"><template #default="scope"><StatusBadge :label="importStatus(scope.row.status).label" :tone="importStatus(scope.row.status).tone" /></template></el-table-column><el-table-column prop="createdAt" label="创建时间" width="170" /><el-table-column label="操作" width="100" fixed="right"><template #default="scope"><el-button link type="primary" @click="showBatch(scope.row)">查看</el-button></template></el-table-column></el-table></div>
    </section>

    <el-dialog v-model="detailVisible" title="导入批次详情" width="520px"><template v-if="selectedBatch"><dl class="detail-grid detail-grid--dialog"><div class="detail-item"><dt>文件</dt><dd>{{ selectedBatch.fileName }}</dd></div><div class="detail-item"><dt>批次状态</dt><dd>{{ importStatus(selectedBatch.status).label }}</dd></div><div class="detail-item"><dt>总行数</dt><dd>{{ selectedBatch.rowCount }}</dd></div><div class="detail-item"><dt>成功行</dt><dd>{{ selectedBatch.successCount }}</dd></div><div class="detail-item"><dt>错误行</dt><dd>{{ selectedBatch.errorCount }}</dd></div><div class="detail-item"><dt>批次时间</dt><dd>{{ selectedBatch.createdAt }}</dd></div></dl><div v-if="selectedBatch.errorCount" class="inline-error"><WarningFilled />存在错误行，请下载错误报告修复后重新导入。错误行不会写入业务表。</div><div v-if="errorRows.length" class="error-row-list"><div v-for="(row, index) in errorRows" :key="index">{{ errorRowText(row) }}</div></div></template><template #footer><el-button @click="detailVisible = false">关闭</el-button><el-button v-if="selectedBatch?.errorCount" type="primary" :disabled="!errorRows.length" @click="downloadBatchErrorReport">下载错误报告</el-button></template></el-dialog>
    <el-dialog v-model="previewVisible" title="导入预览与确认" width="560px"><template v-if="previewBatch"><dl class="detail-grid detail-grid--dialog"><div class="detail-item"><dt>批次</dt><dd>{{ previewBatch.batchNo || previewBatch.id }}</dd></div><div class="detail-item"><dt>状态</dt><dd>{{ importStatus(previewBatch.status).label }}</dd></div><div class="detail-item"><dt>总行数</dt><dd>{{ previewBatch.rowCount }}</dd></div><div class="detail-item"><dt>可提交行</dt><dd>{{ previewBatch.successCount }}</dd></div><div class="detail-item"><dt>错误行</dt><dd>{{ previewBatch.errorCount }}</dd></div></dl><div v-if="previewBatch.status === 'VALIDATED'" class="inline-note">服务端校验通过，确认提交后才会写入业务表。</div><div v-else class="inline-error"><WarningFilled />当前批次不是 VALIDATED，不能提交。请根据错误明细修复 CSV 后重新预览。</div><div v-if="previewErrors.length" class="error-row-list"><div v-for="(row, index) in previewErrors" :key="index">{{ errorRowText(row) }}</div></div></template><template #footer><el-button @click="previewVisible = false">关闭</el-button><el-button v-if="previewErrors.length" @click="downloadPreviewErrorReport">下载错误报告</el-button><el-button v-if="previewBatch?.status === 'VALIDATED'" type="primary" :loading="committing" @click="commitImport">确认提交</el-button></template></el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Document, Download, InfoFilled, Refresh, UploadFilled, WarningFilled } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { apiList, apiMutate, extractApiError } from '@/services/api'
import { demoImports } from '@/services/demo'
import { mapImport } from '@/services/mappers'
import type { ImportBatch } from '@/types'

const dataTypes = [{ value: 'SUPPLIER', label: '供应商' }, { value: 'MATERIAL', label: '物料' }, { value: 'INVENTORY', label: '库存快照' }, { value: 'DEMAND_HISTORY', label: '需求历史' }]
const form = reactive({ dataType: 'INVENTORY' })
const file = ref<File>()
const fileInput = ref<HTMLInputElement>()
const dragActive = ref(false)
const batches = ref<ImportBatch[]>([])
const loading = ref(false)
const loadingHistory = ref(false)
const errorMessage = ref('')
const detailVisible = ref(false)
const selectedBatch = ref<ImportBatch>()
const previewVisible = ref(false)
const previewBatch = ref<ImportBatch>()
const previewErrors = ref<unknown[]>([])
const errorRows = ref<unknown[]>([])
const committing = ref(false)
const dataTypeLabel = computed(() => dataTypes.find((item) => item.value === form.dataType)?.label || '未选择')
function dataTypeName(value: string) { return dataTypes.find((item) => item.value === value)?.label || value }
const checks = [
  { label: '字段映射', description: '根据数据类型匹配模板字段，并拒绝未知必填字段。', tone: 'pass' },
  { label: '格式与范围', description: '校验日期、数量、编码唯一性与业务范围。', tone: 'pass' },
  { label: '批次留痕', description: '保存来源文件名、操作者、校验结果和服务端追踪号。', tone: 'pass' },
]

async function loadHistory() {
  loadingHistory.value = true
  try {
    const result = await apiList<ImportBatch>({ method: 'GET', url: '/imports' }, mapImport, () => demoImports)
    batches.value = result.records
  } catch (error) { errorMessage.value = extractApiError(error) } finally { loadingHistory.value = false }
}
function openFilePicker() { fileInput.value?.click() }
function handleNativeFile(event: Event) {
  const input = event.target as HTMLInputElement
  chooseFile(input.files?.[0])
  input.value = ''
}
function handleFileDrop(event: DragEvent) {
  dragActive.value = false
  chooseFile(event.dataTransfer?.files?.[0])
}
function chooseFile(candidate?: File) {
  if (!candidate) return
  if (!candidate.name.toLowerCase().endsWith('.csv')) { errorMessage.value = '请选择 .csv 格式的文件。'; return }
  if (candidate.size > 20 * 1024 * 1024) { errorMessage.value = '文件超过 20 MB，请拆分后重新选择。'; return }
  file.value = candidate
  errorMessage.value = ''
}
function clearFile() { file.value = undefined; if (fileInput.value) fileInput.value.value = '' }
function formatFileSize(bytes: number) { return bytes < 1024 ? `${bytes} B` : bytes < 1024 * 1024 ? `${(bytes / 1024).toFixed(1)} KB` : `${(bytes / 1024 / 1024).toFixed(1)} MB` }
async function submitImport() {
  if (!file.value) return
  loading.value = true; errorMessage.value = ''
  try {
    const payload = new FormData(); payload.append('file', file.value); payload.append('type', form.dataType); payload.append('sourceSystem', 'CSV_ADAPTER')
    const raw = await apiMutate<unknown>({ method: 'POST', url: '/imports/preview', data: payload }, () => ({ id: `imp-demo-${Date.now()}`, fileName: file.value?.name || 'preview.csv', dataType: dataTypeLabel.value, rowCount: 0, successCount: 0, errorCount: 0, status: 'VALIDATING', createdAt: new Date().toLocaleString('zh-CN', { hour12: false }) }))
    const result = mapImport(raw)
    batches.value = [result, ...batches.value]
    previewBatch.value = result
    const rawPayload = raw && typeof raw === 'object' ? raw as Record<string, unknown> : {}
    previewErrors.value = Array.isArray(rawPayload.errors) ? rawPayload.errors : []
    if (result.errorCount && !previewErrors.value.length) {
      try { previewErrors.value = (await apiList<unknown>({ method: 'GET', url: `/imports/${result.id}/errors` })).records } catch (error) { errorMessage.value = extractApiError(error) }
    }
    previewVisible.value = true
    ElMessage[result.status === 'VALIDATED' ? 'success' : 'warning'](result.status === 'VALIDATED' ? '导入预览已通过校验，请确认后提交。' : '导入预览发现错误，请修正 CSV 后重新上传。')
  } catch (error) { errorMessage.value = extractApiError(error) } finally { loading.value = false }
}
async function commitImport() { if (!previewBatch.value || previewBatch.value.status !== 'VALIDATED') return; committing.value = true; try { const raw = await apiMutate<unknown>({ method: 'POST', url: `/imports/${previewBatch.value.id}/commit`, data: {} }); const result = mapImport(raw); batches.value = batches.value.map((item) => item.id === result.id ? result : item); previewBatch.value = result; previewVisible.value = false; reset(); ElMessage.success('导入批次已提交，业务数据写入完成。') } catch (error) { errorMessage.value = extractApiError(error) } finally { committing.value = false } }
function reset() { form.dataType = 'INVENTORY'; clearFile(); errorMessage.value = '' }
async function showBatch(batch: ImportBatch) { selectedBatch.value = batch; try { errorRows.value = batch.errorCount ? (await apiList<unknown>({ method: 'GET', url: `/imports/${batch.id}/errors` })).records : [] } catch (error) { errorMessage.value = extractApiError(error); errorRows.value = [] } detailVisible.value = true }
function downloadTemplate() {
  const names: Record<string, string> = { SUPPLIER: 'suppliers.csv', MATERIAL: 'materials.csv', INVENTORY: 'inventory.csv', DEMAND_HISTORY: 'demand_history.csv' }
  const name = names[form.dataType]
  const base = import.meta.env.BASE_URL.endsWith('/') ? import.meta.env.BASE_URL : `${import.meta.env.BASE_URL}/`
  const link = document.createElement('a')
  link.href = `${base}templates/${name}`
  link.download = name
  document.body.appendChild(link)
  link.click()
  link.remove()
  ElMessage.success(`${dataTypeLabel.value}模板已开始下载`)
}
function downloadBatchErrorReport() { downloadErrorReport(errorRows.value, selectedBatch.value?.batchNo || selectedBatch.value?.id || 'batch') }
function downloadPreviewErrorReport() { downloadErrorReport(previewErrors.value, previewBatch.value?.batchNo || previewBatch.value?.id || 'preview') }
function downloadErrorReport(rows: unknown[], batchName: string) {
  if (!rows.length) { ElMessage.warning('当前没有可下载的错误明细。'); return }
  const headers = ['row', 'field', 'code', 'message', 'raw']
  const lines = rows.map((item, index) => {
    const row = item && typeof item === 'object' ? item as Record<string, unknown> : { raw: item }
    return [row.row ?? row.rowNumber ?? index + 1, row.field ?? row.fieldName ?? '', row.code ?? row.errorCode ?? '', row.message ?? row.errorMessage ?? '', row.raw ?? row.rawData ?? JSON.stringify(item)].map(csvCell).join(',')
  })
  downloadTextFile(`\uFEFF${[headers.join(','), ...lines].join('\r\n')}`, `import-errors-${batchName}.csv`)
  ElMessage.success('错误报告已下载')
}
function csvCell(value: unknown) { return `"${String(value ?? '').replaceAll('"', '""')}"` }
function downloadTextFile(content: string, name: string) {
  const url = URL.createObjectURL(new Blob([content], { type: 'text/csv;charset=utf-8' }))
  const link = document.createElement('a')
  link.href = url; link.download = name; document.body.appendChild(link); link.click(); link.remove()
  URL.revokeObjectURL(url)
}
function errorRowText(item: unknown) {
  if (!item || typeof item !== 'object') return String(item)
  const row = item as Record<string, unknown>
  const rowNumber = row.row ?? row.rowNumber ?? '—'
  const field = row.field ?? row.fieldName ?? '未知字段'
  const message = row.message ?? row.errorMessage ?? '校验失败'
  const code = row.code ?? row.errorCode
  return `第 ${rowNumber} 行 · ${field}：${message}${code ? `（${code}）` : ''}`
}
function importStatus(status: ImportBatch['status']) {
  const labels: Record<string, { label: string; tone: 'neutral' | 'warning' | 'success' | 'blue' | 'danger' }> = {
    VALIDATING: { label: '校验中', tone: 'blue' }, VALIDATED: { label: '校验通过，待提交', tone: 'success' }, COMPLETED: { label: '已提交', tone: 'success' }, FAILED_VALIDATION: { label: '校验失败', tone: 'danger' }, READY: { label: '待导入', tone: 'warning' }, IMPORTED: { label: '已导入', tone: 'success' }, FAILED: { label: '失败', tone: 'danger' },
  }
  return labels[status] || { label: status, tone: 'neutral' }
}
onMounted(loadHistory)
</script>

<style scoped>
.import-grid { align-items: stretch; margin-bottom: 14px; }
.upload-icon { width: 31px; height: 31px; color: var(--lm-olive); }
.visually-hidden { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; border: 0; }
.file-picker { display: grid; justify-items: center; gap: 7px; width: 100%; min-height: 164px; padding: 28px 20px; border: 1px dashed var(--lm-olive); background: var(--lm-surface); color: var(--lm-text); font: inherit; cursor: pointer; transition: background-color 160ms ease, border-color 160ms ease, box-shadow 160ms ease; }
.file-picker:hover, .file-picker:focus-visible, .file-picker.is-dragging { border-color: var(--lm-blue); background: var(--lm-blue-soft); box-shadow: 0 0 0 3px color-mix(in srgb, var(--lm-blue) 14%, transparent); outline: none; }
.file-picker__title { color: var(--lm-blue); font-size: 14px; font-weight: 700; }
.file-picker__hint, .file-tip { color: var(--lm-muted); font-size: 11px; }
.file-tip { margin-top: 8px; line-height: 1.5; }
.selected-file { display: grid; grid-template-columns: 20px minmax(0, 1fr) auto; gap: 10px; align-items: center; width: 100%; margin-top: 10px; padding: 10px 12px; border: 1px solid var(--lm-border); background: var(--lm-surface-muted); }
.selected-file > svg { width: 18px; color: var(--lm-olive); }
.selected-file div { display: grid; min-width: 0; }
.selected-file strong { overflow: hidden; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.selected-file span { color: var(--lm-muted); font-size: 11px; }
.inline-note { display: flex; align-items: flex-start; gap: 8px; }
.inline-note svg { width: 15px; flex: 0 0 auto; margin-top: 1px; }
.form-actions { display: flex; justify-content: flex-end; gap: 9px; margin-top: 24px; }
.quality-panel { background: var(--lm-surface); }
.quality-list { display: grid; gap: 0; margin-top: 22px; }
.quality-row { display: grid; grid-template-columns: 23px 1fr; gap: 9px; align-items: start; padding: 12px 0; border-top: 1px solid var(--lm-border); }
.quality-mark { display: grid; place-items: center; width: 19px; height: 19px; border: 1px solid currentColor; font-size: 11px; font-weight: 750; }
.quality-mark.pass { color: var(--lm-success); }
.quality-mark.warn { color: var(--lm-warning); }
.quality-row strong { font-size: 12px; }
.quality-row p { margin: 4px 0 0; color: var(--lm-muted); font-size: 11px; line-height: 1.5; }
.import-history { overflow: hidden; }
.toolbar strong { font-size: 13px; }
.toolbar-caption { margin-left: 10px; color: var(--lm-muted); font-size: 11px; }
.table-loading { display: grid; gap: 8px; padding: 16px; }
.number { font-variant-numeric: tabular-nums; }
.muted { color: var(--lm-muted); }
.detail-grid--dialog { grid-template-columns: repeat(2, minmax(0, 1fr)); }
@media (max-width: 640px) { .file-picker { min-height: 138px; padding: 22px 14px; } .detail-grid--dialog { grid-template-columns: 1fr; } }
</style>
