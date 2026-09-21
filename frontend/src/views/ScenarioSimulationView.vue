<template>
  <div>
    <PageHeader title="采购情景推演" description="基于已保存的预测、库存与在途事实，对需求、交期、合格率和价格变化进行只读模拟；确认前不修改业务数据。">
      <template #actions>
        <el-button @click="router.push('/forecast')">查看预测批次</el-button>
        <el-button :icon="Refresh" :loading="loadingHistory" @click="loadHistory">刷新记录</el-button>
      </template>
    </PageHeader>

    <section class="surface scenario-builder">
      <div class="builder-heading">
        <div>
          <h2>设置模拟条件</h2>
          <p>系统自动选择该物料最新成功的14日预测批次，并冻结本次输入、库存快照与计算结果。</p>
        </div>
        <div class="preset-actions" aria-label="情景预设">
          <span>快速预设</span>
          <el-button size="small" @click="applyPreset('demand')">需求上升</el-button>
          <el-button size="small" @click="applyPreset('delay')">供应延迟</el-button>
          <el-button size="small" @click="applyPreset('stress')">复合压力</el-button>
        </div>
      </div>
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent>
        <div class="scenario-form-grid">
          <el-form-item class="span-two" label="情景名称" prop="scenarioName">
            <el-input v-model="form.scenarioName" maxlength="128" show-word-limit placeholder="例如：核心供应商延迟5天且需求上升30%" />
          </el-form-item>
          <el-form-item class="span-two" label="推演物料" prop="materialCode">
            <el-select v-model="form.materialCode" filterable style="width:100%" placeholder="选择已有预测批次的物料">
              <el-option v-for="item in materials" :key="item.code" :label="`${item.code} · ${item.name}`" :value="item.code" />
            </el-select>
          </el-form-item>
          <el-form-item label="需求变化" prop="demandChangePercent">
            <el-input-number v-model="form.demandChangePercent" :min="-80" :max="300" :step="5" controls-position="right" style="width:100%" />
            <div class="field-hint">相对原预测序列，范围 -80% 至 +300%</div>
          </el-form-item>
          <el-form-item label="供应延迟" prop="supplierDelayDays">
            <el-input-number v-model="form.supplierDelayDays" :min="0" :max="60" :step="1" controls-position="right" style="width:100%" />
            <div class="field-hint">在途到达时间整体后移，最多60天</div>
          </el-form-item>
          <el-form-item label="安全库存变化" prop="safetyStockChangePercent">
            <el-input-number v-model="form.safetyStockChangePercent" :min="-100" :max="300" :step="5" controls-position="right" style="width:100%" />
            <div class="field-hint">用于评估服务水平变化对补货量的影响</div>
          </el-form-item>
          <el-form-item label="到货合格率" prop="qualificationRatePercent">
            <el-input-number v-model="form.qualificationRatePercent" :min="50" :max="100" :step="1" controls-position="right" style="width:100%" />
            <div class="field-hint">仅折算在途有效数量，不修改历史收货</div>
          </el-form-item>
          <el-form-item label="采购价格变化" prop="priceChangePercent">
            <el-input-number v-model="form.priceChangePercent" :min="-50" :max="300" :step="5" controls-position="right" style="width:100%" />
            <div class="field-hint">基于物料标准价计算金额影响</div>
          </el-form-item>
        </div>
      </el-form>
      <div v-if="errorMessage" class="inline-error"><WarningFilled />{{ errorMessage }}</div>
      <div class="builder-footer">
        <div class="simulation-boundary"><Lock /><span><strong>只读推演</strong> 本步骤只保存两小时有效的计算快照，不生成需求、不改库存、不修改订单。</span></div>
        <el-button type="primary" :loading="simulating" :disabled="!materials.length" @click="simulate">运行情景推演</el-button>
      </div>
    </section>

    <template v-if="scenario">
      <section class="surface scenario-result">
        <div class="result-heading">
          <div>
            <div class="result-code">{{ scenario.scenarioNo }} · {{ scenario.forecast.runNo }}</div>
            <h2>{{ scenario.scenarioName }}</h2>
            <p>{{ scenario.material.name }}（{{ scenario.material.code }}）· {{ scenario.forecast.modelName }} · {{ scenario.forecast.dataLabel }}</p>
          </div>
          <div class="result-statuses">
            <StatusBadge :label="scenarioStatus.label" :tone="scenarioStatus.tone" />
            <StatusBadge :label="riskStatus.label" :tone="riskStatus.tone" />
          </div>
        </div>

        <div class="decision-summary">
          <div class="decision-primary">
            <span>模拟方案建议采购</span>
            <strong>{{ formatQty(scenario.simulated.recommendedOrderQty) }} {{ scenario.material.unit }}</strong>
            <p>较基准方案 {{ signedQty(scenario.deltas.recommendedOrderQty) }} {{ scenario.material.unit }}，预计采购金额 {{ currency(scenario.simulated.estimatedAmount) }}。</p>
            <div class="decision-actions">
              <el-button v-if="canAdopt" type="primary" @click="openAdopt">确认生成采购需求</el-button>
              <el-button v-if="scenario.adoptedDemand" @click="router.push('/purchase-demands')">查看 {{ scenario.adoptedDemand.demandNo }}</el-button>
              <span v-else-if="auth.role === 'MANAGER'">管理角色可评估和复核，但不能生成采购需求。</span>
              <span v-else-if="scenario.status === 'EXPIRED'">结果已过有效期，请按当前事实重新推演。</span>
              <span v-else-if="scenario.simulated.recommendedOrderQty <= 0">模拟结果未产生新增采购需求。</span>
            </div>
          </div>
          <dl class="source-facts">
            <div><dt>预测基准日</dt><dd>{{ scenario.forecast.asOfDate }}</dd></div>
            <div><dt>现有可用量</dt><dd>{{ formatQty(scenario.sourceSnapshot.availableNowQty) }} {{ scenario.material.unit }}</dd></div>
            <div><dt>在途数量</dt><dd>{{ formatQty(scenario.sourceSnapshot.inTransitQty) }} {{ scenario.material.unit }}</dd></div>
            <div><dt>在途模拟到达</dt><dd>窗口第 {{ scenario.simulated.inTransitArrivalDay }} 天</dd></div>
            <div><dt>结果有效期</dt><dd>{{ dateTime(scenario.expiresAt) }}</dd></div>
            <div><dt>数据指纹</dt><dd class="fingerprint">{{ scenario.dataFingerprint.slice(0, 16) }}…</dd></div>
          </dl>
        </div>

        <div class="comparison-wrap">
          <table class="comparison-table">
            <thead><tr><th>计算口径</th><th>基准方案</th><th>模拟方案</th><th>变化</th></tr></thead>
            <tbody>
              <tr v-for="row in comparisonRows" :key="row.label">
                <th>{{ row.label }}<small>{{ row.note }}</small></th>
                <td>{{ row.baseline }}</td><td :class="row.tone">{{ row.simulated }}</td><td :class="row.deltaTone">{{ row.delta }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <div class="split-grid scenario-details">
        <section class="surface surface-pad">
          <div class="section-heading"><div><h2>逐日库存投影</h2><p>模拟方案按日扣减需求，并在预计到达日计入有效在途。</p></div></div>
          <div class="table-wrap"><el-table :data="projectionRows" size="small" max-height="440"><el-table-column prop="date" label="日期" width="108" /><el-table-column label="基准可用" align="right" min-width="105"><template #default="scope">{{ formatQty(scope.row.baselineAvailable) }}</template></el-table-column><el-table-column label="模拟可用" align="right" min-width="105"><template #default="scope"><span :class="{ danger: scope.row.simulatedAvailable < 0, warning: scope.row.simulatedRisk > 0 && scope.row.simulatedAvailable >= 0 }">{{ formatQty(scope.row.simulatedAvailable) }}</span></template></el-table-column><el-table-column label="模拟需求" align="right" min-width="100"><template #default="scope">{{ formatQty(scope.row.simulatedDemand) }}</template></el-table-column><el-table-column label="到货计入" align="right" min-width="100"><template #default="scope">{{ formatQty(scope.row.simulatedArrival) }}</template></el-table-column><el-table-column label="安全缺口" align="right" min-width="100"><template #default="scope"><span :class="{ danger: scope.row.simulatedRisk > 0 }">{{ formatQty(scope.row.simulatedRisk) }}</span></template></el-table-column></el-table></div>
        </section>
        <section class="surface surface-pad">
          <div class="section-heading"><div><h2>受影响订单</h2><p>展示该物料仍在执行的订单及延迟后的预计日期。</p></div><StatusBadge :label="`${scenario.affectedOrders.length} 笔`" tone="blue" /></div>
          <div v-if="scenario.affectedOrders.length" class="table-wrap"><el-table :data="scenario.affectedOrders" size="small" max-height="300"><el-table-column prop="orderNo" label="订单号" min-width="155" /><el-table-column prop="supplierName" label="供应商" min-width="145" /><el-table-column label="剩余量" width="90" align="right"><template #default="scope">{{ formatQty(scope.row.remainingQty) }}</template></el-table-column><el-table-column prop="expectedArrivalDate" label="原到货日" width="105" /><el-table-column prop="simulatedArrivalDate" label="模拟到货日" width="105" /></el-table></div>
          <div v-else class="compact-empty">当前没有该物料的未完成订单；推演仍基于库存和预测序列完成。</div>
          <div class="assumptions"><h3>计算边界</h3><ul><li v-for="item in scenario.assumptions" :key="item">{{ item }}</li></ul></div>
        </section>
      </div>
    </template>

    <section class="surface scenario-history">
      <div class="section-heading history-heading"><div><h2>推演记录</h2><p>BUYER只查看自己的记录；MANAGER可查看全部记录。点击一行读取冻结结果。</p></div><span>{{ history.length }} 条</span></div>
      <div v-if="loadingHistory && !history.length" class="history-loading"><div v-for="item in 3" :key="item" class="skeleton"></div></div>
      <div v-else-if="!history.length" class="compact-empty">还没有情景推演记录。运行上方表单后，结果会在这里保留。</div>
      <div v-else class="table-wrap"><el-table :data="history" highlight-current-row @row-click="openHistory"><el-table-column prop="scenarioNo" label="场景编号" min-width="185" /><el-table-column prop="scenarioName" label="情景" min-width="200" /><el-table-column prop="materialCode" label="物料" width="125" /><el-table-column label="建议量变化" width="130" align="right"><template #default="scope">{{ signedQty(scope.row.simulatedOrderQty - scope.row.baselineOrderQty) }}</template></el-table-column><el-table-column label="风险" width="95"><template #default="scope"><StatusBadge :label="riskMeta(scope.row.riskLevel).label" :tone="riskMeta(scope.row.riskLevel).tone" /></template></el-table-column><el-table-column label="状态" width="105"><template #default="scope"><StatusBadge :label="statusMeta(scope.row.status).label" :tone="statusMeta(scope.row.status).tone" /></template></el-table-column><el-table-column prop="createdByName" label="创建人" width="120" /><el-table-column label="创建时间" width="170"><template #default="scope">{{ dateTime(scope.row.createdAt) }}</template></el-table-column></el-table></div>
    </section>

    <el-dialog v-model="adoptVisible" title="确认生成采购需求" width="510px" destroy-on-close>
      <div v-if="scenario" class="adopt-context"><span>{{ scenario.scenarioNo }}</span><strong>{{ scenario.material.name }} · {{ formatQty(scenario.simulated.recommendedOrderQty) }} {{ scenario.material.unit }}</strong><small>确认后生成来源为“情景推演”的采购需求草稿；库存和订单仍不会在本步骤改变。</small></div>
      <el-form ref="adoptFormRef" :model="adoptForm" :rules="adoptRules" label-position="top"><el-form-item label="期望到货日期" prop="expectedDate"><el-date-picker v-model="adoptForm.expectedDate" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item><el-form-item label="优先级" prop="priority"><el-select v-model="adoptForm.priority" style="width:100%"><el-option label="普通" value="NORMAL" /><el-option label="高" value="HIGH" /><el-option label="紧急" value="URGENT" /></el-select></el-form-item><el-form-item label="确认说明"><el-input v-model="adoptForm.note" type="textarea" :rows="3" maxlength="300" show-word-limit placeholder="说明采用该情景的业务原因" /></el-form-item></el-form>
      <template #footer><el-button @click="adoptVisible = false">取消</el-button><el-button type="primary" :loading="adopting" @click="adopt">确认并生成草稿</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { Lock, Refresh, WarningFilled } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { apiList, apiMutate, apiRequest, extractApiError } from '@/services/api'
import { mapMaterial } from '@/services/mappers'
import { useAuthStore } from '@/stores/auth'
import type { Material, ProcurementScenario, ProcurementScenarioSummary } from '@/types'

const router = useRouter()
const auth = useAuthStore()
const formRef = ref<FormInstance>()
const adoptFormRef = ref<FormInstance>()
const materials = ref<Material[]>([])
const history = ref<ProcurementScenarioSummary[]>([])
const scenario = ref<ProcurementScenario>()
const simulating = ref(false)
const loadingHistory = ref(false)
const adopting = ref(false)
const adoptVisible = ref(false)
const errorMessage = ref('')
const form = reactive({ scenarioName: '需求与供应变化模拟', materialCode: '', demandChangePercent: 20, supplierDelayDays: 3, safetyStockChangePercent: 0, qualificationRatePercent: 95, priceChangePercent: 0 })
const adoptForm = reactive({ expectedDate: '', priority: 'HIGH', note: '' })
const rules: FormRules = { scenarioName: [{ required: true, message: '请输入情景名称', trigger: 'blur' }], materialCode: [{ required: true, message: '请选择推演物料', trigger: 'change' }] }
const adoptRules: FormRules = { expectedDate: [{ required: true, message: '请选择期望到货日期', trigger: 'change' }], priority: [{ required: true, message: '请选择优先级', trigger: 'change' }] }
const scenarioStatus = computed(() => statusMeta(scenario.value?.status || 'PREVIEW'))
const riskStatus = computed(() => riskMeta(scenario.value?.simulated.riskLevel || 'LOW'))
const canAdopt = computed(() => auth.role === 'BUYER' && scenario.value?.status === 'PREVIEW' && scenario.value.simulated.recommendedOrderQty > 0)
const comparisonRows = computed(() => scenario.value ? [
  { label: '14日需求', note: '预测序列汇总', baseline: qtyWithUnit(scenario.value.baseline.totalDemand), simulated: qtyWithUnit(scenario.value.simulated.totalDemand), delta: signedQty(scenario.value.deltas.demandQty), tone: '', deltaTone: scenario.value.deltas.demandQty > 0 ? 'warning' : '' },
  { label: '窗口有效供给', note: '现有可用量 + 窗口内有效在途', baseline: qtyWithUnit(scenario.value.baseline.effectiveSupply), simulated: qtyWithUnit(scenario.value.simulated.effectiveSupply), delta: signedQty(scenario.value.simulated.effectiveSupply - scenario.value.baseline.effectiveSupply), tone: '', deltaTone: scenario.value.simulated.effectiveSupply < scenario.value.baseline.effectiveSupply ? 'danger' : '' },
  { label: '安全库存', note: '按情景比例调整', baseline: qtyWithUnit(scenario.value.baseline.safetyStock), simulated: qtyWithUnit(scenario.value.simulated.safetyStock), delta: signedQty(scenario.value.simulated.safetyStock - scenario.value.baseline.safetyStock), tone: '', deltaTone: '' },
  { label: '建议采购量', note: '按最小起订量与包装量取整', baseline: qtyWithUnit(scenario.value.baseline.recommendedOrderQty), simulated: qtyWithUnit(scenario.value.simulated.recommendedOrderQty), delta: signedQty(scenario.value.deltas.recommendedOrderQty), tone: 'emphasis', deltaTone: scenario.value.deltas.recommendedOrderQty > 0 ? 'danger' : 'success' },
  { label: '预计采购金额', note: '标准价 × 情景价格变化', baseline: currency(scenario.value.baseline.estimatedAmount), simulated: currency(scenario.value.simulated.estimatedAmount), delta: signedCurrency(scenario.value.deltas.estimatedAmount), tone: 'emphasis', deltaTone: scenario.value.deltas.estimatedAmount > 0 ? 'danger' : 'success' },
  { label: '窗口末可用量', note: '不含尚未到达的在途', baseline: qtyWithUnit(scenario.value.baseline.endAvailableQty), simulated: qtyWithUnit(scenario.value.simulated.endAvailableQty), delta: signedQty(scenario.value.simulated.endAvailableQty - scenario.value.baseline.endAvailableQty), tone: scenario.value.simulated.endAvailableQty < 0 ? 'danger' : '', deltaTone: scenario.value.simulated.endAvailableQty < scenario.value.baseline.endAvailableQty ? 'danger' : '' },
] : [])
const projectionRows = computed(() => scenario.value?.simulated.points.map((point, index) => ({ date: point.date, simulatedDemand: point.demandQty, simulatedArrival: point.arrivalQty, simulatedAvailable: point.projectedAvailableQty, simulatedRisk: point.riskQty, baselineAvailable: scenario.value?.baseline.points[index]?.projectedAvailableQty ?? 0 })) || [])

function applyPreset(kind: 'demand' | 'delay' | 'stress') {
  if (kind === 'demand') Object.assign(form, { scenarioName: '需求上升20%模拟', demandChangePercent: 20, supplierDelayDays: 0, safetyStockChangePercent: 0, qualificationRatePercent: 100, priceChangePercent: 0 })
  if (kind === 'delay') Object.assign(form, { scenarioName: '供应延迟5天模拟', demandChangePercent: 0, supplierDelayDays: 5, safetyStockChangePercent: 0, qualificationRatePercent: 100, priceChangePercent: 0 })
  if (kind === 'stress') Object.assign(form, { scenarioName: '需求与供应复合压力模拟', demandChangePercent: 30, supplierDelayDays: 5, safetyStockChangePercent: 10, qualificationRatePercent: 90, priceChangePercent: 10 })
}
async function initialize() {
  errorMessage.value = ''
  try {
    const result = await apiList<Material>({ method: 'GET', url: '/master-data/materials' }, mapMaterial)
    materials.value = result.records.filter((item) => item.status === 'ACTIVE')
  } catch (error) { errorMessage.value = extractApiError(error) }
  await loadHistory()
  const latestScenarioMaterial = history.value.find((item) => materials.value.some((material) => material.code === item.materialCode))?.materialCode
  form.materialCode = latestScenarioMaterial || materials.value[0]?.code || ''
}
async function loadHistory() {
  loadingHistory.value = true
  try { history.value = (await apiList<ProcurementScenarioSummary>({ method: 'GET', url: '/intelligence/scenarios' })).records }
  catch (error) { errorMessage.value = extractApiError(error) }
  finally { loadingHistory.value = false }
}
async function simulate() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  simulating.value = true
  errorMessage.value = ''
  try {
    scenario.value = await apiMutate<ProcurementScenario>({ method: 'POST', url: '/intelligence/scenarios', data: { ...form } })
    ElMessage.success('情景推演已完成，结果已冻结两小时')
    await loadHistory()
    requestAnimationFrame(() => document.querySelector('.scenario-result')?.scrollIntoView({ behavior: 'smooth', block: 'start' }))
  } catch (error) { errorMessage.value = extractApiError(error) }
  finally { simulating.value = false }
}
async function openHistory(row: ProcurementScenarioSummary) {
  errorMessage.value = ''
  try { scenario.value = await apiRequest<ProcurementScenario>({ method: 'GET', url: `/intelligence/scenarios/${row.id}` }); requestAnimationFrame(() => document.querySelector('.scenario-result')?.scrollIntoView({ behavior: 'smooth', block: 'start' })) }
  catch (error) { errorMessage.value = extractApiError(error) }
}
function openAdopt() {
  if (!scenario.value || !canAdopt.value) return
  const date = new Date(); date.setDate(date.getDate() + Math.max(1, scenario.value.material.leadTimeDays + scenario.value.parameters.supplierDelayDays))
  Object.assign(adoptForm, { expectedDate: localDate(date), priority: scenario.value.simulated.riskLevel === 'CRITICAL' ? 'URGENT' : 'HIGH', note: '' })
  adoptVisible.value = true
}
async function adopt() {
  const valid = await adoptFormRef.value?.validate().catch(() => false)
  if (!valid || !scenario.value) return
  adopting.value = true
  try {
    scenario.value = await apiMutate<ProcurementScenario>({ method: 'POST', url: `/intelligence/scenarios/${scenario.value.id}/adopt`, data: { expectedVersion: scenario.value.version, ...adoptForm } })
    adoptVisible.value = false
    ElMessage.success('已生成情景来源的采购需求草稿')
    await loadHistory()
  } catch (error) {
    ElMessage.error(extractApiError(error))
    if (scenario.value) {
      try { scenario.value = await apiRequest<ProcurementScenario>({ method: 'GET', url: `/intelligence/scenarios/${scenario.value.id}` }); await loadHistory() }
      catch { /* 保留原错误提示，刷新失败时不覆盖上下文 */ }
    }
  }
  finally { adopting.value = false }
}
function statusMeta(status: string): { label: string; tone: 'neutral' | 'warning' | 'success' | 'blue' | 'danger' | 'olive' } { return ({ PREVIEW: { label: '待确认', tone: 'warning' }, ADOPTED: { label: '已生成需求', tone: 'success' }, EXPIRED: { label: '已过期', tone: 'neutral' } } as const)[status] || { label: status, tone: 'neutral' } }
function riskMeta(risk: string): { label: string; tone: 'neutral' | 'warning' | 'success' | 'blue' | 'danger' | 'olive' } { return ({ LOW: { label: '低风险', tone: 'success' }, MEDIUM: { label: '中风险', tone: 'blue' }, HIGH: { label: '高风险', tone: 'warning' }, CRITICAL: { label: '严重风险', tone: 'danger' } } as const)[risk] || { label: risk, tone: 'neutral' } }
function formatQty(value: number) { return Number(value || 0).toLocaleString('zh-CN', { maximumFractionDigits: 4 }) }
function qtyWithUnit(value: number) { return `${formatQty(value)} ${scenario.value?.material.unit || ''}` }
function signedQty(value: number) { const number = Number(value || 0); return `${number > 0 ? '+' : ''}${formatQty(number)}` }
function currency(value: number) { return `¥${Number(value || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}` }
function signedCurrency(value: number) { const number = Number(value || 0); return `${number > 0 ? '+' : number < 0 ? '-' : ''}¥${Math.abs(number).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}` }
function dateTime(value?: string) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—' }
function localDate(value: Date) { const year = value.getFullYear(); const month = String(value.getMonth() + 1).padStart(2, '0'); const day = String(value.getDate()).padStart(2, '0'); return `${year}-${month}-${day}` }
onMounted(initialize)
</script>

<style scoped>
.scenario-builder { padding: 20px; }
.builder-heading, .result-heading, .builder-footer, .decision-actions { display: flex; align-items: flex-start; justify-content: space-between; gap: 18px; }
.builder-heading { padding-bottom: 16px; border-bottom: 1px solid var(--lm-border); }
.builder-heading h2, .result-heading h2 { margin: 0; font-size: 16px; }
.builder-heading p, .result-heading p { max-width: 72ch; margin: 5px 0 0; color: var(--lm-muted); font-size: 12px; line-height: 1.55; }
.preset-actions { display: flex; align-items: center; justify-content: flex-end; gap: 7px; flex-wrap: wrap; }
.preset-actions > span { margin-right: 3px; color: var(--lm-muted); font-size: 11px; }
.scenario-form-grid { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 0 14px; padding-top: 17px; }
.scenario-form-grid .span-two { grid-column: span 2; }
.field-hint { margin-top: 6px; color: var(--lm-muted); font-size: 10px; line-height: 1.45; }
.builder-footer { align-items: center; padding-top: 14px; border-top: 1px solid var(--lm-border); }
.simulation-boundary { display: flex; align-items: flex-start; gap: 8px; max-width: 76ch; color: var(--lm-muted); font-size: 11px; line-height: 1.5; }
.simulation-boundary svg { flex: 0 0 auto; width: 14px; margin-top: 1px; color: var(--lm-blue); }
.simulation-boundary strong { color: var(--lm-ink-soft); }
.scenario-result, .scenario-history { margin-top: 14px; overflow: hidden; }
.result-heading { align-items: center; padding: 19px 20px 16px; border-bottom: 1px solid var(--lm-border); }
.result-code { margin-bottom: 5px; color: var(--lm-blue); font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 10px; }
.result-statuses { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.decision-summary { display: grid; grid-template-columns: minmax(0, 1.25fr) minmax(360px, 0.75fr); border-bottom: 1px solid var(--lm-border); }
.decision-primary { padding: 21px 20px; }
.decision-primary > span { color: var(--lm-muted); font-size: 11px; }
.decision-primary > strong { display: block; margin-top: 7px; font-size: 24px; letter-spacing: -0.02em; font-variant-numeric: tabular-nums; }
.decision-primary > p { margin: 7px 0 16px; color: var(--lm-muted); font-size: 12px; line-height: 1.55; }
.decision-actions { align-items: center; justify-content: flex-start; }
.decision-actions > span { color: var(--lm-muted); font-size: 11px; }
.source-facts { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); margin: 0; border-left: 1px solid var(--lm-border); background: var(--lm-surface); }
.source-facts > div { padding: 12px 14px; border-bottom: 1px solid var(--lm-border); }
.source-facts > div:nth-child(odd) { border-right: 1px solid var(--lm-border); }
.source-facts dt { color: var(--lm-muted); font-size: 10px; }
.source-facts dd { margin: 5px 0 0; font-size: 12px; font-weight: 650; font-variant-numeric: tabular-nums; }
.source-facts .fingerprint { font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 10px; }
.comparison-wrap { overflow-x: auto; padding: 0 20px 20px; }
.comparison-table { width: 100%; min-width: 720px; border-collapse: collapse; }
.comparison-table th, .comparison-table td { padding: 12px 13px; border-bottom: 1px solid var(--lm-border); text-align: right; font-size: 12px; font-variant-numeric: tabular-nums; }
.comparison-table thead th { color: var(--lm-muted); background: var(--lm-surface); font-size: 11px; font-weight: 650; }
.comparison-table th:first-child { text-align: left; }
.comparison-table tbody th { color: var(--lm-ink-soft); font-weight: 650; }
.comparison-table tbody th small { display: block; margin-top: 3px; color: var(--lm-muted); font-size: 10px; font-weight: 400; }
.emphasis { color: var(--lm-ink); font-weight: 750; }
.danger { color: var(--lm-danger); font-weight: 700; }
.warning { color: var(--lm-warning); font-weight: 700; }
.success { color: var(--lm-success); font-weight: 700; }
.scenario-details { margin-top: 14px; align-items: start; }
.scenario-details > .surface { min-width: 0; overflow: hidden; }
.scenario-details .table-wrap { max-width: 100%; }
.compact-empty { padding: 24px 16px; color: var(--lm-muted); background: var(--lm-surface); font-size: 12px; line-height: 1.6; text-align: center; }
.assumptions { margin-top: 18px; padding-top: 15px; border-top: 1px solid var(--lm-border); }
.assumptions h3 { margin: 0; font-size: 12px; }
.assumptions ul { margin: 9px 0 0; padding-left: 18px; color: var(--lm-muted); font-size: 11px; line-height: 1.65; }
.history-heading { margin: 0; padding: 18px 20px 14px; border-bottom: 1px solid var(--lm-border); }
.history-heading > span { color: var(--lm-muted); font-size: 11px; }
.history-loading { display: grid; gap: 8px; padding: 16px; }
.history-loading .skeleton { height: 40px; }
.adopt-context { display: grid; gap: 5px; margin-bottom: 18px; padding: 12px 13px; border: 1px solid var(--lm-border); background: var(--lm-surface); }
.adopt-context span { color: var(--lm-blue); font-size: 10px; font-weight: 700; }
.adopt-context strong { font-size: 13px; }
.adopt-context small { color: var(--lm-muted); font-size: 11px; line-height: 1.5; }
@media (max-width: 1180px) { .scenario-form-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); }.decision-summary { grid-template-columns: 1fr; }.source-facts { border-top: 1px solid var(--lm-border); border-left: 0; } }
@media (max-width: 760px) { .builder-heading, .result-heading, .builder-footer { flex-direction: column; align-items: stretch; }.preset-actions { justify-content: flex-start; }.scenario-form-grid { grid-template-columns: 1fr; }.scenario-form-grid .span-two { grid-column: auto; }.source-facts { grid-template-columns: 1fr; }.source-facts > div:nth-child(odd) { border-right: 0; }.decision-primary > strong { font-size: 20px; }.comparison-wrap { padding-inline: 14px; } }
@media (prefers-reduced-motion: reduce) { * { scroll-behavior: auto !important; } }
</style>
