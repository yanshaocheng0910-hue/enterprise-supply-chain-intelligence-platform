<template>
  <div>
    <PageHeader title="AI 语义解析" description="仅支持采购需求、计划变更、到货通知三类白名单意图。解析结果必须经过校验、影响预览和人工确认。">
      <template #actions><StatusBadge label="人在回路" tone="blue" /></template>
    </PageHeader>
    <div class="inline-note ai-governance"><Lock /><span><strong>受控范围：</strong>AI 只生成结构化草稿，不会直接创建订单、修改计划或完成收货。当前页面不展示未经校准的置信度，判断依据是字段证据、校验结果与影响预览。</span></div>
    <div class="ai-layout">
      <section class="surface surface-pad ai-editor">
        <div class="section-heading"><div><h2>输入协同文本</h2><p>支持自然语言或粘贴邮件/聊天片段</p></div></div>
        <el-form label-position="top" @submit.prevent="runParse"><el-form-item label="解析意图"><el-select v-model="intent" style="width:100%"><el-option v-for="item in intents" :key="item.value" :label="item.label" :value="item.value"><span class="intent-option"><strong>{{ item.label }}</strong><small>{{ item.description }}</small></span></el-option></el-select></el-form-item><el-form-item label="原文"><el-input v-model="originalText" type="textarea" :rows="9" maxlength="1200" show-word-limit :placeholder="inputPlaceholder" /></el-form-item><div class="editor-actions"><el-button native-type="button" @click="clear">清空</el-button><el-button native-type="submit" type="primary" :loading="parsing" :disabled="!originalText.trim()">开始解析</el-button></div></el-form>
        <div class="ai-scope"><strong>当前角色可用意图</strong><span v-for="item in intents" :key="item.value">{{ item.label }}</span></div>
      </section>

      <section class="surface surface-pad ai-preview">
        <div class="section-heading"><div><h2>结构化预览</h2><p>字段、证据与影响均待确认</p></div><StatusBadge v-if="preview" :label="providerBadge.label" :tone="providerBadge.tone" /></div>
        <EmptyState v-if="!preview && !parsing" title="等待解析结果" description="输入文本后开始解析。结果不会绕过确认直接写入业务数据。" />
        <div v-else-if="parsing" class="preview-loading"><div v-for="i in 5" :key="i" class="skeleton" :style="{ height: i === 1 ? '26px' : '43px' }"></div></div>
        <template v-else-if="preview">
          <div v-if="preview.fallback" class="fallback-note"><InfoFilled /> 当前后端不可用，展示的是明确标注的结构化回放；确认动作不会被当作真实业务写入。</div>
          <div v-else-if="preview.providerFallback" class="fallback-note"><InfoFilled /> 大模型调用未成功，本次已显式降级为可复现规则解析。结果仍需业务校验和人工确认，不计入大模型成功记录。</div>
          <div class="ai-split">
            <div><div class="subheading">原文证据</div><blockquote>{{ preview.originalText }}</blockquote><div class="subheading">解析字段</div><dl class="parse-fields"><div v-for="(value, key) in preview.normalized" :key="key"><dt>{{ fieldLabel(String(key)) }}</dt><dd><span>{{ value ?? '未提取' }}</span><small>{{ evidenceSource(String(key)) }}</small></dd></div></dl></div>
            <div><div class="subheading">Schema / 业务校验</div><div class="check-list"><div v-for="check in preview.checks" :key="check.label" class="check-row"><span :class="checkClass(check.status)">{{ check.status === 'PASS' ? '通过' : check.status === 'WARN' ? '需确认' : '失败' }}</span><div><strong>{{ check.label }}</strong><p>{{ check.detail }}</p></div></div></div><div class="subheading impact-heading">影响预览</div><div class="impact-preview"><div><span>意图</span><strong>{{ intentLabel(preview.intent) }}</strong></div><div><span>确认后动作</span><strong>{{ impactAction(preview.intent) }}</strong></div><div><span>执行角色</span><strong>{{ confirmationOwner }}</strong></div></div></div>
          </div>
          <div class="parse-meta">请求号 {{ preview.requestId }} · Provider {{ preview.provider }} · Model {{ preview.model }} · Prompt {{ preview.promptVersion }} · 原始结果已留痕</div>
          <div class="preview-actions"><el-button @click="clear">放弃结果</el-button><el-button type="primary" :loading="confirming" :disabled="preview.fallback || hasBlockingCheck" @click="confirm">{{ confirmActionLabel(preview.intent) }}</el-button></div>
          <div v-if="preview.fallback" class="confirm-disabled">演示回退结果不可直接写入后端。连接服务端后，确认按钮才会执行真实的校验与保存。</div>
          <div v-else-if="hasBlockingCheck" class="confirm-disabled">当前预览存在 FAIL / NEEDS_INPUT 校验结果，必须补充或修正后才能确认。</div>
        </template>
        <div v-if="errorMessage" class="inline-error"><WarningFilled />{{ errorMessage }}</div>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { InfoFilled, Lock, WarningFilled } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import EmptyState from '@/components/EmptyState.vue'
import { apiMutate, extractApiError } from '@/services/api'
import { demoParsePreview } from '@/services/demo'
import { mapParsePreview } from '@/services/mappers'
import { useAuthStore } from '@/stores/auth'
import type { ParseIntent, ParsePreview } from '@/types'

const auth = useAuthStore()
const intentCatalog = [{ value: 'PURCHASE_DEMAND' as ParseIntent, label: '采购需求', description: '从文本提取物料、数量、日期与仓库' }, { value: 'PLAN_CHANGE' as ParseIntent, label: '计划变更', description: '提取已存在计划的调整意图与范围' }, { value: 'DELIVERY_NOTICE' as ParseIntent, label: '到货通知', description: '提取订单、发货、数量与预计到达' }]
const intents = computed(() => auth.role === 'SUPPLIER' ? intentCatalog.filter((item) => item.value === 'DELIVERY_NOTICE') : intentCatalog.filter((item) => item.value !== 'DELIVERY_NOTICE'))
const intent = ref<ParseIntent>(auth.role === 'SUPPLIER' ? 'DELIVERY_NOTICE' : 'PURCHASE_DEMAND'); const originalText = ref(''); const preview = ref<ParsePreview>(); const parsing = ref(false); const confirming = ref(false); const errorMessage = ref('')
const confirmationOwner = computed(() => auth.role === 'SUPPLIER' ? '当前供应商用户' : '当前采购用户')
const providerBadge = computed(() => {
  if (!preview.value) return { label: '', tone: 'blue' as const }
  if (preview.value.fallback) return { label: '演示回退', tone: 'warning' as const }
  if (preview.value.providerFallback) return { label: '规则降级', tone: 'warning' as const }
  if (preview.value.provider === 'openai-compatible') return { label: '本地大模型', tone: 'success' as const }
  return { label: '规则解析', tone: 'blue' as const }
})
const inputPlaceholder = computed(() => auth.role === 'SUPPLIER' ? '例如：订单 PO-20260820-008 已发货，预计 8 月 31 日到达……' : '例如：请补充连接器外壳 2400 个，希望 8 月 31 日前到货……')
const hasBlockingCheck = computed(() => {
  const status = String(preview.value?.status || '').toUpperCase()
  return Boolean(preview.value && (['FAIL', 'FAILED', 'INVALID', 'NEEDS_INPUT'].includes(status) || preview.value.checks.some((check) => check.status === 'FAIL')))
})
async function runParse() {
  if (!intents.value.some((item) => item.value === intent.value)) return
  parsing.value = true
  errorMessage.value = ''
  preview.value = undefined
  const requestedIntent = intent.value
  try {
    const raw = await apiMutate<unknown>({ method: 'POST', url: '/ai-parse/preview', timeout: 90000, data: { taskType: requestedIntent, text: originalText.value, context: {} } }, () => ({ ...demoParsePreview, intent: requestedIntent, originalText: originalText.value }))
    const result = raw && typeof raw === 'object' && !Array.isArray(raw) ? raw as Record<string, unknown> : {}
    preview.value = mapParsePreview({ ...result, taskType: result.taskType ?? requestedIntent })
  } catch (error) {
    errorMessage.value = extractApiError(error)
  } finally {
    parsing.value = false
  }
}
async function confirm() { if (!preview.value || preview.value.fallback || hasBlockingCheck.value) return; confirming.value = true; try { const completedIntent = preview.value.intent; await apiMutate({ method: 'POST', url: `/ai-parse/${preview.value.requestId}/confirm`, data: { expectedVersion: preview.value.expectedVersion ?? 0 } }); ElMessage.success(`已确认，服务端已${impactAction(completedIntent)}`); clear() } catch (error) { ElMessage.error(extractApiError(error)) } finally { confirming.value = false } }
function clear() { preview.value = undefined; originalText.value = ''; errorMessage.value = '' }
function checkClass(status: string) { return status === 'PASS' ? 'check-pass' : status === 'WARN' ? 'check-warn' : 'check-fail' }
function intentLabel(value: ParseIntent) { return intentCatalog.find((item) => item.value === value)?.label || value }
function impactAction(value: ParseIntent) { return value === 'PLAN_CHANGE' ? '更新 DRAFT 计划数量并留痕' : value === 'DELIVERY_NOTICE' ? '创建到货通知草稿' : '创建采购需求草稿' }
function confirmActionLabel(value: ParseIntent) { return value === 'PLAN_CHANGE' ? '确认并更新计划' : value === 'DELIVERY_NOTICE' ? '确认并创建通知草稿' : '确认并创建需求草稿' }
function evidenceSource(key: string) { return preview.value?.evidence.find((item) => item.field === key)?.source || '服务端校验' }
function fieldLabel(key: string) { return ({ materialCode: '物料编码', materialName: '物料名称', supplierCode: '供应商编码', supplierName: '供应商名称', quantity: '数量', unit: '单位', requiredDate: '期望日期', warehouseCode: '仓库编码', planNo: '计划编号', changeType: '变更类型', newQuantity: '调整后数量', quantityDelta: '数量变化', orderNo: '关联订单', shipDate: '发货日期', eta: '预计到达', trackingNo: '运单号', deliveryStatus: '交付状态', reason: '业务原因' } as Record<string, string>)[key] || key }
</script>

<style scoped>
.ai-governance { display: flex; align-items: flex-start; gap: 8px; margin-bottom: 14px; }.ai-governance svg { width: 15px; flex: 0 0 auto; margin-top: 1px; }.ai-editor, .ai-preview { min-height: 580px; }.intent-option { display: grid; gap: 3px; }.intent-option strong { font-size: 12px; }.intent-option small { color: var(--lm-muted); font-size: 10px; }.editor-actions, .preview-actions { display: flex; justify-content: flex-end; gap: 9px; margin-top: 18px; }.ai-scope { display: flex; align-items: center; gap: 7px; flex-wrap: wrap; margin-top: 25px; padding-top: 16px; border-top: 1px solid var(--lm-border); color: var(--lm-muted); font-size: 11px; }.ai-scope strong { color: var(--lm-ink-soft); margin-right: 4px; }.ai-scope span { padding: 5px 7px; border: 1px solid var(--lm-border); background: var(--lm-surface); }.preview-loading { display: grid; gap: 10px; }.fallback-note { display: flex; align-items: flex-start; gap: 7px; margin-bottom: 17px; padding: 10px 12px; border: 1px solid oklch(79% 0.08 70); background: var(--lm-warning-soft); color: oklch(37% 0.08 70); font-size: 11px; line-height: 1.5; }.fallback-note svg { flex: 0 0 auto; width: 15px; }.subheading { margin-bottom: 9px; color: var(--lm-muted); font-size: 11px; font-weight: 750; letter-spacing: 0.04em; }.subheading:not(:first-child) { margin-top: 22px; }blockquote { margin: 0; padding: 12px 13px; border-left: 3px solid var(--lm-olive); background: var(--lm-surface); color: var(--lm-ink-soft); font-size: 12px; line-height: 1.6; }.parse-fields { margin: 0; border: 1px solid var(--lm-border); }.parse-fields > div { display: grid; grid-template-columns: 90px 1fr; gap: 8px; padding: 8px 10px; border-top: 1px solid var(--lm-border); }.parse-fields > div:first-child { border-top: 0; }.parse-fields dt { color: var(--lm-muted); font-size: 11px; }.parse-fields dd { margin: 0; font-size: 12px; font-weight: 650; word-break: break-word; }.check-row p { margin: 3px 0 0; color: var(--lm-muted); font-size: 11px; line-height: 1.45; }.impact-heading { margin-top: 22px !important; }.impact-preview { display: grid; gap: 0; border: 1px solid var(--lm-border); }.impact-preview > div { display: flex; justify-content: space-between; gap: 10px; padding: 9px 10px; border-top: 1px solid var(--lm-border); font-size: 11px; }.impact-preview > div:first-child { border-top: 0; }.impact-preview span { color: var(--lm-muted); }.impact-preview strong { font-size: 11px; text-align: right; }.parse-meta { margin-top: 20px; color: var(--lm-faint); font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 10px; }.confirm-disabled { margin-top: 10px; color: var(--lm-warning); font-size: 11px; text-align: right; }
.parse-fields dd { display: grid; gap: 2px; }
.parse-fields dd small { color: var(--lm-faint); font-size: 9px; font-weight: 500; }
</style>
