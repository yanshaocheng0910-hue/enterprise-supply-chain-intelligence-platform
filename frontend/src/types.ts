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
  source: 'FORECAST' | 'MANUAL' | 'AI_PARSE'
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
  status: 'PENDING_CONFIRM' | 'PENDING_CONFIRMATION' | 'CONFIRMED' | 'PENDING_SHIPMENT' | 'SHIPPED' | 'ARRIVED' | 'PARTIAL' | 'DELIVERED' | 'RECEIVED' | 'RECONCILING' | 'COMPLETED' | 'REJECTED' | 'CLOSED' | 'CANCELLED'
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
  shippedAt: string
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
  checks: { label: string; status: 'PASS' | 'WARN' | 'FAIL'; detail: string }[]
  provider: string
  model: string
  promptVersion: string
  fallback: boolean
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
