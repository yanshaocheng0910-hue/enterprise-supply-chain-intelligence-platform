import type {
  AuditRecord,
  AuthUser,
  DashboardSummary,
  DeliveryNotice,
  ForecastPoint,
  ImportBatch,
  InventoryRecord,
  Material,
  ParsePreview,
  PurchaseDemand,
  PurchaseOrder,
  PurchasePlan,
  ReceiptRecord,
  ReconciliationRecord,
  Supplier,
  UserRole,
  WarningRecord,
} from '@/types'

type Dict = Record<string, unknown>

export function camelizeKeys<T>(value: T): T {
  if (Array.isArray(value)) return value.map((item) => camelizeKeys(item)) as T
  if (!value || typeof value !== 'object' || value instanceof Date || value instanceof FormData) return value
  const output: Dict = {}
  for (const [key, nested] of Object.entries(value as Dict)) {
    output[key.replace(/_([a-z])/g, (_, letter: string) => letter.toUpperCase())] = camelizeKeys(nested)
  }
  return output as T
}

function dict(value: unknown): Dict { return value && typeof value === 'object' && !Array.isArray(value) ? value as Dict : {} }
function text(value: unknown, fallback = '') { return value === null || value === undefined ? fallback : String(value) }
function numeric(value: unknown, fallback = 0) { const result = Number(value); return Number.isFinite(result) ? result : fallback }
function dateText(value: unknown) { return text(value, '') }
function statusText(value: unknown, fallback = 'UNKNOWN') { return text(value, fallback).toUpperCase() }
function role(value: unknown): UserRole { const candidate = statusText(value, 'BUYER'); return ['ADMIN', 'BUYER', 'SUPPLIER', 'MANAGER'].includes(candidate) ? candidate as UserRole : 'BUYER' }

export function mapAuthUser(raw: unknown): AuthUser {
  const r = dict(camelizeKeys(raw))
  return {
    id: text(r.id),
    username: text(r.username),
    displayName: text(r.displayName || r.username, '用户'),
    role: role(r.role || r.roleCode),
    supplierId: r.supplierId === null || r.supplierId === undefined ? undefined : text(r.supplierId),
    permissions: Array.isArray(r.permissions) ? r.permissions.map(String) : [],
  }
}

export function mapSupplier(raw: unknown): Supplier {
  const r = dict(camelizeKeys(raw))
  const rate = numeric(r.onTimeRate)
  return {
    id: text(r.id), code: text(r.supplierCode || r.code), name: text(r.supplierName || r.name),
    category: text(r.category || r.levelCode, '未分类'), contactName: text(r.contactName, '未填写'), contactPhone: text(r.contactPhone, '未填写'),
    status: statusText(r.status, 'INACTIVE') as Supplier['status'], onTimeRate: rate <= 1 ? rate * 100 : rate,
    openOrders: numeric(r.openOrders), updatedAt: dateText(r.updatedAt || r.createdAt), version: numeric(r.version),
  }
}

export function mapMaterial(raw: unknown): Material {
  const r = dict(camelizeKeys(raw))
  return {
    id: text(r.id), code: text(r.materialCode || r.code), name: text(r.materialName || r.name), specification: text(r.specification || r.category, ''),
    unit: text(r.unit), category: text(r.category), safetyStock: numeric(r.safetyStock), leadTimeDays: numeric(r.leadTimeDays), status: statusText(r.status, 'INACTIVE') as Material['status'],
    updatedAt: dateText(r.updatedAt || r.createdAt), minOrderQty: numeric(r.minOrderQty), packSize: numeric(r.packSize), standardPrice: numeric(r.standardPrice), version: numeric(r.version),
  }
}

export function mapInventory(raw: unknown): InventoryRecord {
  const r = dict(camelizeKeys(raw))
  const available = numeric(r.availableQty ?? r.available)
  const inTransit = numeric(r.inTransitQty ?? r.inTransit)
  const safetyStock = numeric(r.safetyStock)
  const derivedShortage = Math.max(safetyStock - available - inTransit, 0)
  return {
    id: text(r.id), materialCode: text(r.materialCode), materialName: text(r.materialName), warehouse: text(r.warehouseName || r.warehouse || r.warehouseCode),
    available, inTransit, safetyStock, unit: text(r.unit), shortage: numeric(r.shortage, derivedShortage), updatedAt: dateText(r.updatedAt), version: numeric(r.version),
  }
}

export function mapDemand(raw: unknown): PurchaseDemand {
  const r = dict(camelizeKeys(raw))
  const source = statusText(r.sourceType || r.source, 'MANUAL')
  return {
    id: text(r.id), demandNo: text(r.demandNo), source: (['FORECAST', 'MANUAL', 'AI_PARSE'].includes(source) ? source : 'MANUAL') as PurchaseDemand['source'],
    materialName: text(r.materialName), materialCode: text(r.materialCode), quantity: numeric(r.quantity), dueDate: text(r.expectedDate || r.dueDate),
    status: statusText(r.status, 'DRAFT') as PurchaseDemand['status'], requester: text(r.requester || r.createdByName, '服务端记录'), createdAt: dateText(r.createdAt),
    priority: text(r.priority), notes: text(r.notes), unit: text(r.unit), sourceRef: text(r.sourceRef), version: numeric(r.version),
  }
}

export function mapPlan(raw: unknown): PurchasePlan {
  const r = dict(camelizeKeys(raw))
  const items = Array.isArray(r.items) ? r.items.map((item) => dict(item)) : []
  const supplier = items.find((item) => item.supplierName)?.supplierName
  return {
    id: text(r.id), planNo: text(r.planNo), period: text(r.period || r.planName), supplierName: text(r.supplierName || supplier), lineCount: numeric(r.itemCount || r.lineCount || items.length),
    totalQuantity: numeric(r.totalQuantity), estimatedAmount: numeric(r.totalAmount ?? r.estimatedAmount), status: statusText(r.status, 'DRAFT') as PurchasePlan['status'], owner: text(r.owner || r.createdByName, '服务端记录'), updatedAt: dateText(r.updatedAt || r.createdAt), version: numeric(r.version), planName: text(r.planName), rejectionReason: text(r.rejectionReason),
  }
}

export function mapOrder(raw: unknown): PurchaseOrder {
  const r = dict(camelizeKeys(raw))
  return {
    id: text(r.id), orderNo: text(r.orderNo), supplierName: text(r.supplierName), itemCount: numeric(r.itemCount), totalAmount: numeric(r.orderAmount ?? r.totalAmount), promisedDate: text(r.expectedArrivalDate || r.promisedDate),
    status: statusText(r.status, 'PENDING_CONFIRMATION') as PurchaseOrder['status'], sourcePlanNo: text(r.planNo || r.sourcePlanNo), updatedAt: dateText(r.updatedAt || r.createdAt), version: numeric(r.version), planId: text(r.planId), supplierCode: text(r.supplierCode),
  }
}

export function mapDelivery(raw: unknown): DeliveryNotice {
  const r = dict(camelizeKeys(raw))
  const items = Array.isArray(r.items) ? r.items : []
  return {
    id: text(r.id), noticeNo: text(r.noticeNo), orderNo: text(r.orderNo), supplierName: text(r.supplierName), shippedAt: text(r.shippedAt || r.createdAt), eta: text(r.expectedArrivalAt || r.eta), quantity: numeric(r.quantity || (items.length ? items.reduce((sum, item) => sum + numeric(dict(item).quantity), 0) : 0)),
    status: statusText(r.status, 'DRAFT') as DeliveryNotice['status'], note: text(r.exceptionNote || r.note), version: numeric(r.version), orderId: text(r.orderId), items,
  }
}

export function mapReceipt(raw: unknown): ReceiptRecord {
  const r = dict(camelizeKeys(raw))
  const items = Array.isArray(r.items) ? r.items : []
  const expected = numeric(r.expectedQuantity || (items.length ? items.reduce((sum, item) => sum + numeric(dict(item).orderedQty), 0) : 0))
  const received = numeric(r.receivedQuantity || (items.length ? items.reduce((sum, item) => sum + numeric(dict(item).receivedQty), 0) : 0))
  return {
    id: text(r.id), receiptNo: text(r.receiptNo), orderNo: text(r.orderNo), supplierName: text(r.supplierName), receivedAt: dateText(r.receivedAt || r.createdAt),
    expectedQuantity: expected, receivedQuantity: received, difference: numeric(r.difference, received - expected), status: statusText(r.status, 'PENDING') as ReceiptRecord['status'], operator: text(r.operator || r.receivedBy, '服务端记录'), version: numeric(r.version), orderId: text(r.orderId), orderVersion: numeric(r.orderVersion || r.version), deliveryNoticeId: r.noticeId || r.deliveryNoticeId, items,
  }
}

export function mapReconciliation(raw: unknown): ReconciliationRecord {
  const r = dict(camelizeKeys(raw))
  return {
    id: text(r.id), reconciliationNo: text(r.reconciliationNo), orderNo: text(r.orderNo), supplierName: text(r.supplierName), orderAmount: numeric(r.orderAmount), receivedAmount: numeric(r.receivedAmount), differenceAmount: numeric(r.differenceAmount),
    status: statusText(r.status, 'PENDING') as ReconciliationRecord['status'], updatedAt: dateText(r.updatedAt || r.createdAt), version: numeric(r.version), orderId: text(r.orderId), orderVersion: numeric(r.orderVersion || r.version),
  }
}

export function mapWarning(raw: unknown): WarningRecord {
  const r = dict(camelizeKeys(raw))
  const status = statusText(r.status, 'OPEN')
  return {
    id: text(r.id), type: statusText(r.warningType || r.type, 'SHORTAGE') as WarningRecord['type'], title: text(r.title), subject: text(r.subject || r.warningNo || r.targetType), description: text(r.reasonText || r.description), impact: text(r.impact, '服务端未提供影响说明'), suggestion: text(r.suggestionText || r.suggestion), severity: statusText(r.severity, 'LOW') as WarningRecord['severity'], status: status === 'CLOSED' ? 'RESOLVED' : status as WarningRecord['status'], createdAt: dateText(r.createdAt), owner: text(r.owner), version: numeric(r.version), warningNo: text(r.warningNo),
  }
}

export function mapAudit(raw: unknown): AuditRecord {
  const r = dict(camelizeKeys(raw))
  const after = statusText(r.afterState)
  return { id: text(r.id), action: text(r.actionCode || r.action), module: text(r.targetType || r.module), actor: text(r.operatorName || r.actor), role: role(r.roleCode || r.role), target: `${text(r.targetType)}${r.targetId === undefined ? '' : ` #${text(r.targetId)}`}`, result: after === 'BLOCKED' ? 'BLOCKED' : 'RECORDED', occurredAt: dateText(r.createdAt || r.occurredAt), traceId: text(r.requestId || r.traceId), detail: text(r.detailText || r.detail), }
}

export function mapImport(raw: unknown): ImportBatch {
  const r = dict(camelizeKeys(raw))
  return { id: text(r.id), fileName: text(r.originalFilename || r.fileName), dataType: text(r.importType || r.dataType), rowCount: numeric(r.totalRows || r.rowCount), successCount: numeric(r.successRows || r.successCount), errorCount: numeric(r.failedRows || r.errorCount), status: statusText(r.status, 'VALIDATING') as ImportBatch['status'], createdAt: dateText(r.createdAt), batchNo: text(r.batchNo), finishedAt: dateText(r.finishedAt), }
}

export function mapForecast(raw: unknown): ForecastPoint {
  const r = dict(camelizeKeys(raw))
  const baseline = r.baseline ?? r.ma7
  const forecast = r.forecast ?? r.predictedQty
  const lower = r.lower ?? r.lowerBound
  const upper = r.upper ?? r.upperBound
  return { id: text(r.id), date: text(r.date ?? r.forecastDate ?? r.asOfDate), materialCode: text(r.materialCode), materialName: text(r.materialName), baseline: baseline === undefined ? undefined : numeric(baseline), forecast: forecast === undefined ? 0 : numeric(forecast), lower: lower === undefined ? undefined : numeric(lower), upper: upper === undefined ? undefined : numeric(upper), actual: r.actual === undefined ? undefined : numeric(r.actual), confidence: undefined, runNo: text(r.runNo), modelName: text(r.modelName), status: text(r.status), fallbackReason: text(r.fallbackReason), dataLabel: text(r.dataLabel), horizonDays: numeric(r.horizonDays), mae: r.mae === undefined ? undefined : numeric(r.mae), rmse: r.rmse === undefined ? undefined : numeric(r.rmse), mape: r.mape === undefined ? undefined : numeric(r.mape), featureVersion: text(r.featureVersion), randomSeed: r.randomSeed === undefined ? undefined : numeric(r.randomSeed), }
}

export function mapForecastDetail(raw: unknown): { metadata: ForecastPoint; points: ForecastPoint[] } {
  const r = dict(camelizeKeys(raw))
  const metadata = mapForecast(r)
  const sequence = Array.isArray(r.results) ? r.results : Array.isArray(r.sequence) ? r.sequence : []
  const points = sequence.map((item) => {
    const row = dict(item)
    return mapForecast({
      ...r,
      ...row,
      date: row.forecastDate ?? row.date,
      forecast: row.predictedQty ?? row.forecast,
      lower: row.lowerBound ?? row.lower,
      upper: row.upperBound ?? row.upper,
      baseline: row.baseline ?? row.ma7,
    })
  })
  return { metadata, points }
}

export function mapDashboard(raw: unknown): DashboardSummary {
  const r = dict(camelizeKeys(raw)); const metrics = dict(r.metrics); const inventoryRisk = Array.isArray(r.inventoryRisk) ? r.inventoryRisk.map(dict) : []; const recentOrders = Array.isArray(r.recentOrders) ? r.recentOrders.map(dict) : []; const orderStatus = Array.isArray(r.orderStatus) ? r.orderStatus.map(dict) : []
  const kpis = [
    { key: 'openWarnings', label: '开放预警', value: numeric(metrics.openWarnings), unit: '条', tone: 'danger' as const, description: 'OPEN / ACKNOWLEDGED' },
    { key: 'highWarnings', label: '高风险预警', value: numeric(metrics.highWarnings), unit: '条', tone: 'warning' as const, description: '当前数据库快照' },
    { key: 'activeOrders', label: '执行中订单', value: numeric(metrics.activeOrders), unit: '笔', tone: 'blue' as const, description: '未完成或未取消' },
    { key: 'orderAmount', label: '订单金额', value: `¥${numeric(metrics.orderAmount).toLocaleString('zh-CN', { minimumFractionDigits: 2 })}`, tone: 'olive' as const, description: '当前可见订单合计' },
  ]
  return {
    lastSyncedAt: dateText(r.asOf), dataNotice: text(r.dataNotice), kpis,
    demandTrend: [], fulfilment: orderStatus.map((item) => ({ name: text(item.status), value: numeric(item.count) })),
    urgentTasks: inventoryRisk.map((item, index) => ({ id: `inventory-${index}`, title: `${text(item.materialName)}库存风险`, description: `可用供给 ${numeric(item.availableSupply)}，安全库存 ${numeric(item.safetyStock)}。`, severity: numeric(item.availableSupply) < numeric(item.safetyStock) ? 'critical' : 'medium', route: '/inventory', dueAt: '按当前快照处理', owner: '采购协同' })),
    recentEvents: recentOrders.map((item) => ({ id: text(item.id), title: `订单 ${text(item.orderNo)}`, description: `${text(item.supplierName)} · ${text(item.status)}`, occurredAt: dateText(item.updatedAt), actor: '服务端记录', kind: 'order' })),
  }
}

export function mapParsePreview(raw: unknown): ParsePreview {
  const r = dict(camelizeKeys(raw)); const validation = dict(r.validation); const schemaChecks = dict(r.schemaChecks)
  const businessChecksRaw = Array.isArray(r.businessChecks) ? r.businessChecks : Array.isArray(validation.businessChecks) ? validation.businessChecks : []
  const businessChecks = businessChecksRaw.map(dict)
  const missingRaw = Array.isArray(r.missingFields) ? r.missingFields : Array.isArray(validation.missingFields) ? validation.missingFields : []
  const missing = missingRaw.map((item) => typeof item === 'object' ? text(dict(item).field || dict(item).message, JSON.stringify(item)) : String(item))
  const warningsRaw = Array.isArray(r.warnings) ? r.warnings : Array.isArray(validation.warnings) ? validation.warnings : []
  const warnings = warningsRaw.map((item) => typeof item === 'object' ? text(dict(item).message || dict(item).detail, JSON.stringify(item)) : String(item))
  const schemaValid = r.schemaValid === undefined ? schemaChecks.valid !== false && !missing.length : r.schemaValid !== false
  const businessCheckFailed = businessChecks.some((item) => ['FAIL', 'FAILED', 'NEEDS_INPUT', 'INVALID'].includes(statusText(item.status)))
  const businessValid = r.businessValid === undefined ? !businessCheckFailed : r.businessValid !== false
  const checks: ParsePreview['checks'] = [
    { label: 'Schema 校验', status: schemaValid ? 'PASS' : 'FAIL', detail: schemaValid ? '固定 Schema 校验通过' : '结构化字段不符合固定 Schema' },
    { label: '业务校验', status: businessValid ? 'PASS' : 'FAIL', detail: businessChecks.map((item) => `${text(item.status)}: ${text(item.message || item.detail)}`).join('；') || '服务端业务规则校验失败' },
    { label: '缺失字段', status: missing.length ? 'FAIL' : 'PASS', detail: missing.length ? `待补充：${missing.join('、')}` : '必填字段已提取' },
    { label: '服务说明', status: warnings.length ? 'WARN' : 'PASS', detail: warnings.join('；') || '无额外服务说明' },
  ]
  return { requestId: text(r.previewId || r.requestId), intent: statusText(r.taskType || r.intent, 'PURCHASE_DEMAND') as ParsePreview['intent'], originalText: text(r.sourceText || r.originalText), normalized: dict(camelizeKeys(r.normalizedFields || r.normalized)) as ParsePreview['normalized'], checks, provider: text(r.provider, '服务端'), model: text(r.modelName, '服务端配置'), promptVersion: text(r.schemaVersion, '1.0'), fallback: Boolean(r.fallback), expectedVersion: numeric(r.previewVersion || r.version), status: text(r.status), previewNo: text(r.previewNo), requiresConfirmation: Boolean(r.requiresConfirmation ?? true), expiresAt: dateText(r.expiresAt), }
}
