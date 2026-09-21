export type UserRole = 'ADMIN' | 'BUYER' | 'SUPPLIER' | 'MANAGER'

export interface AuthUser {
  id: string
  username: string
  displayName: string
  role: UserRole
  supplierId?: string
  permissions: string[]
}

export interface AuthSession {
  token: string
  user: AuthUser
  tokenType?: string
  expiresIn?: number
}

export interface ApiEnvelope<T> {
  data: T
  message?: string
  code?: string
  traceId?: string
}

export interface KpiMetric {
  key: string
  label: string
  value: string | number
  unit?: string
  trend?: number
  trendLabel?: string
  tone?: 'olive' | 'blue' | 'warning' | 'danger' | 'neutral'
  description?: string
}

export interface DashboardSummary {
  lastSyncedAt: string
  dataNotice?: string
  kpis: KpiMetric[]
  demandTrend: { date: string; forecast: number; actual?: number }[]
  fulfilment: { name: string; value: number }[]
  urgentTasks: TaskItem[]
  recentEvents: EventItem[]
}

export interface TaskItem {
  id: string
  title: string
  description: string
  severity: 'critical' | 'high' | 'medium' | 'low'
  route?: string
  dueAt?: string
  owner?: string
}

export interface EventItem {
  id: string
  title: string
  description: string
  occurredAt: string
  actor: string
  kind: string
}

export interface Supplier {
  id: string
  code: string
  name: string
  category: string
  contactName: string
  contactPhone: string
  status: 'ACTIVE' | 'INACTIVE' | 'PENDING'
  onTimeRate: number
  openOrders: number
  updatedAt: string
  version?: number
}

export interface Material {
  id: string
  code: string
  name: string
  specification: string
  unit: string
  category: string
  safetyStock: number
  leadTimeDays: number
  status: 'ACTIVE' | 'INACTIVE'
  updatedAt: string
  minOrderQty?: number
  packSize?: number
  standardPrice?: number
  version?: number
}

export interface InventoryRecord {
  id: string
  materialCode: string
  materialName: string
  warehouse: string
  available: number
  inTransit: number
  safetyStock: number
  unit: string
  shortage: number
  updatedAt: string
  version?: number
}

export interface ForecastPoint {
  id?: string
  date: string
  materialCode: string
  materialName: string
  unit?: string
  baseline?: number
  forecast: number
  lower?: number
  upper?: number
  actual?: number
  confidence?: number
  runNo?: string
  modelName?: string
  status?: string
  fallbackReason?: string
  dataLabel?: string
  horizonDays?: number
  mae?: number
  rmse?: number
  mape?: number
  featureVersion?: string
  randomSeed?: number
  suggestedOrderQty?: number
  suggestionStatus?: string
  suggestionDecisionNote?: string
  suggestionDecidedAt?: string
  optimizationNote?: string
  warningCode?: string
  postprocessNote?: string
  selectionNote?: string
  version?: number
  adoptedDemand?: {
    id?: string
    demandNo?: string
    quantity?: number
    expectedDate?: string
    priority?: string
    status?: string
    notes?: string
  }
}

export interface ScenarioProjectionPoint {
  date: string
  demandQty: number
  arrivalQty: number
  projectedAvailableQty: number
  safetyStock: number
  riskQty: number
}

export interface ScenarioProjection {
  totalDemand: number
  safetyStock: number
  availableNow: number
  effectiveInTransit: number
  inTransitArrivalDay: number
  inTransitWithinWindow: boolean
  effectiveSupply: number
  recommendedOrderQty: number
  estimatedAmount: number
  riskLevel: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL'
  riskDate?: string
  stockoutDate?: string
  endAvailableQty: number
  maxRiskQty: number
  points: ScenarioProjectionPoint[]
}

export interface ProcurementScenario {
  id: string | number
  scenarioNo: string
  scenarioName: string
  status: 'PREVIEW' | 'ADOPTED' | 'EXPIRED'
  version: number
  dataFingerprint: string
  expiresAt: string
  createdAt: string
  adoptedAt?: string
  material: {
    id: string | number
    code: string
    name: string
    unit: string
    leadTimeDays: number
    safetyStock: number
    minOrderQty: number
    packSize: number
    standardPrice: number
  }
  forecast: {
    runId: string | number
    runNo: string
    asOfDate: string
    horizonDays: number
    modelName: string
    modelVersion?: string
    dataLabel: string
  }
  parameters: {
    demandChangePercent: number
    supplierDelayDays: number
    safetyStockChangePercent: number
    qualificationRatePercent: number
    priceChangePercent: number
  }
  sourceSnapshot: {
    onHandQty: number
    reservedQty: number
    inTransitQty: number
    availableNowQty: number
    inventoryUpdatedAt?: string
    affectedOrderCount: number
    forecastRunNo: string
    forecastDataHash: string
  }
  baseline: ScenarioProjection
  simulated: ScenarioProjection
  deltas: {
    demandQty: number
    recommendedOrderQty: number
    estimatedAmount: number
    riskDateShiftDays?: number
  }
  affectedOrders: Array<{
    id: string | number
    orderNo: string
    status: string
    supplierName: string
    remainingQty: number
    expectedArrivalDate?: string
    simulatedArrivalDate?: string
  }>
  assumptions: string[]
  adoptedDemand?: {
    id: string | number
    demandNo: string
    quantity: number
    expectedDate: string
    priority: string
    status: string
    sourceType: string
    sourceRef: string
  }
}

export interface ProcurementScenarioSummary {
  id: string | number
  scenarioNo: string
  scenarioName: string
  materialCode: string
  materialName: string
  runNo: string
  baselineOrderQty: number
  simulatedOrderQty: number
  simulatedAmount: number
  riskLevel: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL'
  status: 'PREVIEW' | 'ADOPTED' | 'EXPIRED'
  version: number
  expiresAt: string
  adoptedAt?: string
  adoptedDemandId?: string | number
  createdAt: string
  createdByName: string
}

export interface WarningRecord {
  id: string
  type: 'SHORTAGE' | 'DELIVERY_DELAY' | 'RECONCILIATION' | 'DEMAND_SPIKE'
  title: string
  subject: string
  description: string
  impact: string
  suggestion: string
  severity: 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW'
  status: 'OPEN' | 'ACKNOWLEDGED' | 'RESOLVED'
  createdAt: string
  owner?: string
  version?: number
  warningNo?: string
}

export interface PurchaseDemand {
  id: string
  demandNo: string
  source: 'FORECAST' | 'MANUAL' | 'AI_PARSE' | 'SCENARIO'
  materialName: string
  materialCode: string
  quantity: number
  dueDate: string
  status: 'DRAFT' | 'SUBMITTED' | 'PLANNED' | 'APPROVED' | 'REJECTED' | 'CLOSED'
  requester: string
  createdAt: string
  priority?: string
  notes?: string
  unit?: string
  sourceRef?: string
  version?: number
}

export interface PurchasePlan {
  id: string
  planNo: string
  period: string
  supplierName?: string
  lineCount: number
  totalQuantity: number
  estimatedAmount: number
  status: 'DRAFT' | 'PENDING_APPROVAL' | 'APPROVED' | 'REJECTED' | 'CANCELLED' | 'CLOSED' | 'ORDER_CREATED'
  owner: string
  updatedAt: string
  version?: number
  planName?: string
  rejectionReason?: string
}

export interface PurchaseOrder {
  id: string
  orderNo: string
  supplierName: string
  itemCount: number
  totalAmount: number
  promisedDate: string
  status: 'PENDING_CONFIRM' | 'PENDING_CONFIRMATION' | 'CONFIRMED' | 'PENDING_SHIPMENT' | 'SHIPPED' | 'ARRIVED' | 'PARTIAL' | 'PARTIALLY_RECEIVED' | 'DELIVERED' | 'RECEIVED' | 'RECONCILING' | 'COMPLETED' | 'REJECTED' | 'CLOSED' | 'CANCELLED'
  sourcePlanNo: string
  updatedAt: string
  version?: number
  planId?: string
  supplierCode?: string
}

export interface DeliveryNotice {
  id: string
  noticeNo: string
  orderNo: string
  supplierName: string
  createdAt: string
  eta: string
  quantity: number
  status: 'DRAFT' | 'SUBMITTED' | 'IN_TRANSIT' | 'ARRIVED' | 'EXCEPTION' | 'RECEIVED' | 'CANCELLED'
  note?: string
  version?: number
  orderId?: string
  items?: unknown[]
}

export interface ReceiptRecord {
  id: string
  receiptNo: string
  orderNo: string
  supplierName: string
  receivedAt: string
  expectedQuantity: number
  receivedQuantity: number
  difference: number
  status: 'PENDING' | 'PARTIAL' | 'COMPLETED' | 'DISPUTED'
  operator: string
  version?: number
  orderId?: string
  orderVersion?: number
  deliveryNoticeId?: unknown
  items?: unknown[]
  quantityLoaded?: boolean
}

export interface ReconciliationRecord {
  id: string
  reconciliationNo: string
  orderNo: string
  supplierName: string
  orderAmount: number
  receivedAmount: number
  differenceAmount: number
  status: 'PENDING' | 'IN_REVIEW' | 'CONFIRMED' | 'DISPUTED' | 'RESOLVED' | 'COMPLETED' | 'CLOSED'
  updatedAt: string
  version?: number
  orderId?: string
  orderVersion?: number
}

export interface AuditRecord {
  id: string
  action: string
  module: string
  actor: string
  role: UserRole
  target: string
  result: 'SUCCESS' | 'FAILED' | 'BLOCKED' | 'RECORDED'
  occurredAt: string
  traceId: string
  detail: string
  beforeState?: string
  afterState?: string
}

export interface ImportBatch {
  id: string
  fileName: string
  dataType: string
  rowCount: number
  successCount: number
  errorCount: number
  status: 'VALIDATING' | 'VALIDATED' | 'COMPLETED' | 'FAILED_VALIDATION' | 'READY' | 'IMPORTED' | 'FAILED'
  createdAt: string
  batchNo?: string
  finishedAt?: string
}

export type ParseIntent = 'PURCHASE_DEMAND' | 'PLAN_CHANGE' | 'DELIVERY_NOTICE'

export interface ParsePreview {
  requestId: string
  intent: ParseIntent
  originalText: string
  normalized: Record<string, string | number | null>
  evidence: { field: string; value: string | number | null; source: string }[]
  checks: { label: string; status: 'PASS' | 'WARN' | 'FAIL'; detail: string }[]
  provider: string
  model: string
  promptVersion: string
  fallback: boolean
  providerFallback: boolean
  fallbackReason?: string
  expectedVersion?: number
  status?: string
  previewNo?: string
  requiresConfirmation?: boolean
  expiresAt?: string
}

export interface ListQuery {
  page?: number
  pageSize?: number
  keyword?: string
  status?: string
}

export interface ListResult<T> {
  records: T[]
  total: number
}

export interface AnalysisReportSummary {
  id: string | number
  reportNo: string
  asOfTime: string
  provider: 'rule' | 'openai-compatible'
  modelName: string
  promptVersion: string
  fallbackReason?: string
  dataFingerprint: string
  createdAt: string
  createdByName: string
}

export interface AnalysisReport extends AnalysisReportSummary {
  warnings: string[]
  sections: Array<{
    key: 'executive_summary' | 'demand_inventory' | 'supplier_fulfillment' | 'reconciliation_finance' | 'warning_risk' | 'recommendations' | 'boundary'
    title: string
    content: string
  }>
  priorityActions: Array<{
    code: string
    level: 'HIGH' | 'MEDIUM' | 'LOW'
    title: string
    rationale: string
    route: string
  }>
  context: {
    asOfTime: string
    timezone: string
    scope: string
    metrics: {
      activeOrders: number
      overdueOrders: number
      inventoryShortages: number
      openWarnings: number
      highWarnings: number
      pendingPlans: number
      pendingReconciliations: number
      reconciliationDifferenceAmount: number
      rejectedQuantity: number
      activeSuppliers: number
      averageOnTimeRate: number
    }
    topRisks: Array<{ kind: string; label: string; severity: string; createdAt: string }>
  }
}
