import type {
  AuditRecord,
  AuthUser,
  DashboardSummary,
  DeliveryNotice,
  ForecastPoint,
  ImportBatch,
  InventoryRecord,
  ListResult,
  Material,
  ParsePreview,
  PurchaseDemand,
  PurchaseOrder,
  PurchasePlan,
  ReceiptRecord,
  ReconciliationRecord,
  Supplier,
  WarningRecord,
} from '@/types'

const users: { username: string; password: string; user: AuthUser }[] = [
  { username: 'admin', password: '123456', user: { id: 'u-admin', username: 'admin', displayName: '林岑', role: 'ADMIN', permissions: ['*'] } },
  { username: 'buyer', password: '123456', user: { id: 'u-buyer', username: 'buyer', displayName: '周宁', role: 'BUYER', permissions: ['purchase:*', 'inventory:read', 'ai:parse'] } },
  { username: 'supplier', password: '123456', user: { id: 'u-supplier', username: 'supplier', displayName: '徐海', role: 'SUPPLIER', supplierId: 'sup-001', permissions: ['order:self', 'delivery:self', 'reconciliation:self'] } },
  { username: 'manager', password: '123456', user: { id: 'u-manager', username: 'manager', displayName: '顾岚', role: 'MANAGER', permissions: ['dashboard:read', 'warning:manage', 'plan:approve'] } },
]

export const demoUsers = users

const now = '2026-08-24 14:20'

export const demoDashboard: DashboardSummary = {
  lastSyncedAt: now,
  kpis: [
    { key: 'openDemand', label: '待处理采购需求', value: 18, unit: '条', trend: 12, trendLabel: '较上周', tone: 'olive', description: '含 4 条今日到期需求' },
    { key: 'onTime', label: '准时交付率', value: '94.6', unit: '%', trend: 2.1, trendLabel: '较上月', tone: 'blue', description: '过去 30 天，已确认订单' },
    { key: 'shortage', label: '库存短缺物料', value: 7, unit: '种', trend: -3, trendLabel: '较昨日', tone: 'warning', description: '已扣除在途可用量' },
    { key: 'reconciliation', label: '待处理对账', value: 5, unit: '笔', trend: 8, trendLabel: '较上周', tone: 'danger', description: '其中 2 笔存在数量差异' },
  ],
  demandTrend: [
    { date: '08-24', forecast: 1240, actual: 1180 }, { date: '08-25', forecast: 1320, actual: 1280 }, { date: '08-26', forecast: 1390, actual: 1410 }, { date: '08-27', forecast: 1510, actual: 1460 }, { date: '08-28', forecast: 1460, actual: 1500 }, { date: '08-29', forecast: 1590 }, { date: '08-30', forecast: 1650 }, { date: '08-31', forecast: 1720 }, { date: '09-01', forecast: 1680 }, { date: '09-02', forecast: 1810 }, { date: '09-03', forecast: 1760 }, { date: '09-04', forecast: 1840 }, { date: '09-05', forecast: 1910 }, { date: '09-06', forecast: 1880 },
  ],
  fulfilment: [{ name: '已完成', value: 42 }, { name: '执行中', value: 38 }, { name: '待确认', value: 14 }, { name: '异常', value: 6 }],
  urgentTasks: [
    { id: 'task-1', title: '华东仓安全库存不足', description: 'M-2048 预计 3 天后触及下限，建议确认 2,400 个补货需求。', severity: 'critical', route: '/inventory', dueAt: '今天 16:00', owner: '周宁' },
    { id: 'task-2', title: '订单 PO-20260818-014 待供应商确认', description: '承诺交期为 08-28，已超过确认窗口 8 小时。', severity: 'high', route: '/orders', dueAt: '今天', owner: '徐海' },
    { id: 'task-3', title: '对账差异待核对', description: 'REC-202608-009 实收数量少于订单 120 件，请确认收货记录。', severity: 'medium', route: '/reconciliation', dueAt: '明天', owner: '周宁' },
  ],
  recentEvents: [
    { id: 'event-1', title: '需求预测已完成', description: '预测批次 FC-20260824-01 覆盖 68 种物料。', occurredAt: '14:16', actor: '系统', kind: 'forecast' },
    { id: 'event-2', title: '供应商提交到货通知', description: 'DN-20260824-003 已关联 PO-20260818-008。', occurredAt: '13:42', actor: '海岳电子', kind: 'delivery' },
    { id: 'event-3', title: '采购计划完成审批', description: '计划 PL-202608-W4 已由顾岚确认。', occurredAt: '11:08', actor: '顾岚', kind: 'approval' },
    { id: 'event-4', title: '导入批次完成校验', description: '库存快照 2026-08-24.csv 通过 99.2% 行。', occurredAt: '09:26', actor: '周宁', kind: 'import' },
  ],
}

export const demoSuppliers: Supplier[] = [
  { id: 'sup-001', code: 'SUP-001', name: '海岳电子（苏州）', category: '电子元件', contactName: '徐海', contactPhone: '0512 6802 1188', status: 'ACTIVE', onTimeRate: 96.8, openOrders: 4, updatedAt: '2026-08-24 13:42' },
  { id: 'sup-002', code: 'SUP-002', name: '启辰精密制造', category: '结构件', contactName: '姚琴', contactPhone: '021 5841 7620', status: 'ACTIVE', onTimeRate: 92.4, openOrders: 7, updatedAt: '2026-08-23 16:20' },
  { id: 'sup-003', code: 'SUP-003', name: '博源包装材料', category: '包材', contactName: '赵卓', contactPhone: '0571 8890 3401', status: 'PENDING', onTimeRate: 89.1, openOrders: 2, updatedAt: '2026-08-22 10:08' },
  { id: 'sup-004', code: 'SUP-004', name: '德科工业耗材', category: '工业耗材', contactName: '韩松', contactPhone: '0769 2230 5198', status: 'INACTIVE', onTimeRate: 87.5, openOrders: 0, updatedAt: '2026-08-19 15:30' },
]

export const demoMaterials: Material[] = [
  { id: 'mat-001', code: 'M-2048', name: '连接器外壳', specification: 'PA66 黑色 24P', unit: '个', category: '电子元件', safetyStock: 3200, leadTimeDays: 7, status: 'ACTIVE', updatedAt: '2026-08-24 09:10' },
  { id: 'mat-002', code: 'M-2049', name: '镀锡铜端子', specification: '0.8mm 预镀锡', unit: '个', category: '电子元件', safetyStock: 5600, leadTimeDays: 5, status: 'ACTIVE', updatedAt: '2026-08-24 09:10' },
  { id: 'mat-003', code: 'M-3150', name: '铝合金支架', specification: '6061-T6 阳极氧化', unit: '件', category: '结构件', safetyStock: 900, leadTimeDays: 12, status: 'ACTIVE', updatedAt: '2026-08-23 17:30' },
  { id: 'mat-004', code: 'M-4020', name: '防静电周转箱', specification: '600×400×280mm', unit: '个', category: '包材', safetyStock: 180, leadTimeDays: 10, status: 'ACTIVE', updatedAt: '2026-08-21 11:24' },
  { id: 'mat-005', code: 'M-5108', name: '无铅锡膏', specification: 'SAC305 500g', unit: '罐', category: '工业耗材', safetyStock: 42, leadTimeDays: 4, status: 'ACTIVE', updatedAt: '2026-08-20 08:40' },
]

export const demoInventory: InventoryRecord[] = [
  { id: 'inv-001', materialCode: 'M-2048', materialName: '连接器外壳', warehouse: '华东成品仓', available: 1680, inTransit: 800, safetyStock: 3200, unit: '个', shortage: 720, updatedAt: now },
  { id: 'inv-002', materialCode: 'M-2049', materialName: '镀锡铜端子', warehouse: '华东成品仓', available: 7420, inTransit: 1200, safetyStock: 5600, unit: '个', shortage: 0, updatedAt: now },
  { id: 'inv-003', materialCode: 'M-3150', materialName: '铝合金支架', warehouse: '华南原料仓', available: 420, inTransit: 0, safetyStock: 900, unit: '件', shortage: 480, updatedAt: now },
  { id: 'inv-004', materialCode: 'M-4020', materialName: '防静电周转箱', warehouse: '华东成品仓', available: 124, inTransit: 160, safetyStock: 180, unit: '个', shortage: 0, updatedAt: now },
  { id: 'inv-005', materialCode: 'M-5108', materialName: '无铅锡膏', warehouse: '华南原料仓', available: 28, inTransit: 0, safetyStock: 42, unit: '罐', shortage: 14, updatedAt: now },
]

export const demoForecast: ForecastPoint[] = demoDashboard.demandTrend.flatMap((point, index) => [
  { date: `2026-${point.date.replace('-', '-')}`, materialCode: 'M-2048', materialName: '连接器外壳', baseline: Math.round(point.forecast * 0.92), forecast: point.forecast, lower: Math.round(point.forecast * 0.84), upper: Math.round(point.forecast * 1.12), actual: point.actual, confidence: Math.max(72, 94 - index) },
])

export const demoWarnings: WarningRecord[] = [
  { id: 'warn-001', type: 'SHORTAGE', title: '安全库存缺口', subject: 'M-2048 连接器外壳', description: '可用量与在途量合计低于未来 7 天需求，预计 3 天后断供。', impact: '可能影响华东装配线 2 个工单', suggestion: '确认补货 2,400 个，优先选择海岳电子。', severity: 'CRITICAL', status: 'OPEN', createdAt: '2026-08-24 09:12', owner: '周宁' },
  { id: 'warn-002', type: 'DELIVERY_DELAY', title: '供应商确认超时', subject: 'PO-20260818-014 · 启辰精密制造', description: '订单已发送 32 小时，尚未收到确认或交期反馈。', impact: '承诺交期风险上升，需重新评估排产', suggestion: '联系供应商并记录确认结果。', severity: 'HIGH', status: 'ACKNOWLEDGED', createdAt: '2026-08-24 08:05', owner: '周宁' },
  { id: 'warn-003', type: 'RECONCILIATION', title: '实收数量差异', subject: 'REC-202608-009 · 海岳电子', description: '订单 2,000 个，实际收货 1,880 个，差异 120 个。', impact: '对账金额将减少 ¥1,560.00', suggestion: '核对装箱单与仓库收货记录后确认差异。', severity: 'MEDIUM', status: 'OPEN', createdAt: '2026-08-23 17:21', owner: '周宁' },
  { id: 'warn-004', type: 'DEMAND_SPIKE', title: '需求短期上升', subject: 'M-3150 铝合金支架', description: '未来 3 日预测较 MA7 基线高 28%，已超过规则阈值。', impact: '现有库存覆盖不足 5 天', suggestion: '确认是否存在临时项目需求，再决定是否加急采购。', severity: 'LOW', status: 'RESOLVED', createdAt: '2026-08-22 14:10', owner: '顾岚' },
]

export const demoPurchaseDemands: PurchaseDemand[] = [
  { id: 'req-001', demandNo: 'PR-20260824-001', source: 'FORECAST', materialName: '连接器外壳', materialCode: 'M-2048', quantity: 2400, dueDate: '2026-08-31', status: 'SUBMITTED', requester: '周宁', createdAt: '2026-08-24 09:42' },
  { id: 'req-002', demandNo: 'PR-20260823-014', source: 'MANUAL', materialName: '铝合金支架', materialCode: 'M-3150', quantity: 1200, dueDate: '2026-09-05', status: 'APPROVED', requester: '周宁', createdAt: '2026-08-23 16:18' },
  { id: 'req-003', demandNo: 'PR-20260823-009', source: 'AI_PARSE', materialName: '无铅锡膏', materialCode: 'M-5108', quantity: 80, dueDate: '2026-08-29', status: 'DRAFT', requester: '周宁', createdAt: '2026-08-23 11:03' },
  { id: 'req-004', demandNo: 'PR-20260821-006', source: 'FORECAST', materialName: '防静电周转箱', materialCode: 'M-4020', quantity: 300, dueDate: '2026-09-08', status: 'CLOSED', requester: '周宁', createdAt: '2026-08-21 10:44' },
]

export const demoPurchasePlans: PurchasePlan[] = [
  { id: 'plan-001', planNo: 'PL-202608-W4', period: '2026-W35', supplierName: '海岳电子（苏州）', lineCount: 8, totalQuantity: 8940, estimatedAmount: 286400, status: 'PENDING_APPROVAL', owner: '周宁', updatedAt: '2026-08-24 10:26' },
  { id: 'plan-002', planNo: 'PL-202608-W3', period: '2026-W34', supplierName: '启辰精密制造', lineCount: 12, totalQuantity: 12760, estimatedAmount: 412800, status: 'APPROVED', owner: '周宁', updatedAt: '2026-08-22 16:04' },
  { id: 'plan-003', planNo: 'PL-202608-W2', period: '2026-W33', supplierName: '博源包装材料', lineCount: 7, totalQuantity: 5360, estimatedAmount: 173900, status: 'CLOSED', owner: '周宁', updatedAt: '2026-08-18 13:12' },
]

export const demoOrders: PurchaseOrder[] = [
  { id: 'order-001', orderNo: 'PO-20260818-014', supplierName: '启辰精密制造', itemCount: 3, totalAmount: 128600, promisedDate: '2026-08-28', status: 'PENDING_CONFIRM', sourcePlanNo: 'PL-202608-W3', updatedAt: '2026-08-24 08:05' },
  { id: 'order-002', orderNo: 'PO-20260820-008', supplierName: '海岳电子（苏州）', itemCount: 2, totalAmount: 98400, promisedDate: '2026-08-26', status: 'CONFIRMED', sourcePlanNo: 'PL-202608-W3', updatedAt: '2026-08-23 13:18' },
  { id: 'order-003', orderNo: 'PO-20260815-021', supplierName: '博源包装材料', itemCount: 4, totalAmount: 56200, promisedDate: '2026-08-25', status: 'PARTIAL', sourcePlanNo: 'PL-202608-W2', updatedAt: '2026-08-24 12:44' },
  { id: 'order-004', orderNo: 'PO-20260810-006', supplierName: '海岳电子（苏州）', itemCount: 2, totalAmount: 68400, promisedDate: '2026-08-19', status: 'CLOSED', sourcePlanNo: 'PL-202608-W2', updatedAt: '2026-08-21 09:32' },
]

export const demoDeliveries: DeliveryNotice[] = [
  { id: 'delivery-001', noticeNo: 'DN-20260824-003', orderNo: 'PO-20260820-008', supplierName: '海岳电子（苏州）', createdAt: '2026-08-24', eta: '2026-08-26', quantity: 2400, status: 'IN_TRANSIT', note: '顺丰陆运，运单 SF12480022' },
  { id: 'delivery-002', noticeNo: 'DN-20260823-006', orderNo: 'PO-20260815-021', supplierName: '博源包装材料', createdAt: '2026-08-23', eta: '2026-08-25', quantity: 180, status: 'ARRIVED', note: '已到华东仓待收货' },
  { id: 'delivery-003', noticeNo: 'DN-20260822-011', orderNo: 'PO-20260818-014', supplierName: '启辰精密制造', createdAt: '2026-08-22', eta: '2026-08-28', quantity: 1200, status: 'SUBMITTED' },
]

export const demoReceipts: ReceiptRecord[] = [
  { id: 'receipt-001', receiptNo: 'GR-20260824-002', orderNo: 'PO-20260815-021', supplierName: '博源包装材料', receivedAt: '2026-08-24 11:26', expectedQuantity: 300, receivedQuantity: 180, difference: -120, status: 'PARTIAL', operator: '周宁' },
  { id: 'receipt-002', receiptNo: 'GR-20260823-009', orderNo: 'PO-20260820-008', supplierName: '海岳电子（苏州）', receivedAt: '2026-08-23 15:40', expectedQuantity: 1200, receivedQuantity: 1200, difference: 0, status: 'COMPLETED', operator: '周宁' },
]

export const demoReconciliations: ReconciliationRecord[] = [
  { id: 'rec-001', reconciliationNo: 'REC-202608-009', orderNo: 'PO-20260815-021', supplierName: '海岳电子（苏州）', orderAmount: 98400, receivedAmount: 96840, differenceAmount: -1560, status: 'IN_REVIEW', updatedAt: '2026-08-24 11:26' },
  { id: 'rec-002', reconciliationNo: 'REC-202608-007', orderNo: 'PO-20260820-008', supplierName: '海岳电子（苏州）', orderAmount: 68400, receivedAmount: 68400, differenceAmount: 0, status: 'CONFIRMED', updatedAt: '2026-08-23 15:40' },
]

export const demoAudit: AuditRecord[] = [
  { id: 'audit-001', action: '确认到货通知', module: '交付通知', actor: '徐海', role: 'SUPPLIER', target: 'DN-20260824-003', result: 'SUCCESS', occurredAt: '2026-08-24 13:42', traceId: 'tr_89d3f2', detail: '关联 PO-20260820-008，申报数量 2,400 个' },
  { id: 'audit-002', action: '提交采购计划审批', module: '采购计划', actor: '周宁', role: 'BUYER', target: 'PL-202608-W4', result: 'SUCCESS', occurredAt: '2026-08-24 10:26', traceId: 'tr_23ac11', detail: '8 个物料行，总预计金额 ¥286,400.00' },
  { id: 'audit-003', action: '尝试访问他人订单', module: '采购订单', actor: '徐海', role: 'SUPPLIER', target: 'PO-20260818-014', result: 'BLOCKED', occurredAt: '2026-08-24 09:55', traceId: 'tr_1f2b80', detail: '后端数据范围校验拒绝 supplier_id 不匹配的查询' },
  { id: 'audit-004', action: '导入库存快照', module: '数据导入', actor: '周宁', role: 'BUYER', target: 'inventory_20260824.csv', result: 'SUCCESS', occurredAt: '2026-08-24 09:26', traceId: 'tr_d8a11c', detail: '共 1,248 行，成功 1,238 行，错误 10 行' },
  { id: 'audit-005', action: 'AI 解析预览', module: 'AI 语义解析', actor: '周宁', role: 'BUYER', target: 'parse_20260823_1103', result: 'SUCCESS', occurredAt: '2026-08-23 11:03', traceId: 'tr_a093fe', detail: '意图 PURCHASE_DEMAND，结构校验通过，等待确认' },
]

export const demoImports: ImportBatch[] = [
  { id: 'imp-001', fileName: 'inventory_20260824.csv', dataType: '库存快照', rowCount: 1248, successCount: 1238, errorCount: 10, status: 'IMPORTED', createdAt: '2026-08-24 09:26' },
  { id: 'imp-002', fileName: 'demand_history_202608.csv', dataType: '需求历史', rowCount: 8640, successCount: 8640, errorCount: 0, status: 'IMPORTED', createdAt: '2026-08-23 17:12' },
  { id: 'imp-003', fileName: 'supplier_master_202608.csv', dataType: '供应商', rowCount: 36, successCount: 35, errorCount: 1, status: 'READY', createdAt: '2026-08-22 14:06' },
]

export const demoParsePreview: ParsePreview = {
  requestId: 'parse-demo-20260824',
  intent: 'PURCHASE_DEMAND',
  originalText: '请为华东仓补充连接器外壳 2400 个，希望 8 月 31 日前到货，优先联系海岳电子。',
  normalized: { materialCode: 'M-2048', materialName: '连接器外壳', quantity: 2400, unit: '个', dueDate: '2026-08-31', warehouse: '华东成品仓', supplierHint: 'SUP-001' },
  evidence: [],
  checks: [
    { label: '白名单意图', status: 'PASS', detail: '识别为采购需求，允许进入预览流程' },
    { label: '结构化字段', status: 'PASS', detail: '物料、数量、单位、日期均已提取' },
    { label: '业务事实校验', status: 'WARN', detail: 'M-2048 当前可用量低于安全库存，建议保留预警' },
    { label: '执行权限', status: 'PASS', detail: '当前采购角色可以创建草稿，不会自动提交订单' },
  ],
  provider: 'demo-fallback', model: 'structured-replay', promptVersion: 'v0.8-whitelist-1', fallback: true, providerFallback: false,
}

export function listResult<T>(records: T[]): ListResult<T> {
  return { records, total: records.length }
}
