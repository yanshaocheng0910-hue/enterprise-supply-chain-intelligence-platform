INSERT INTO sys_role(role_code, role_name) VALUES
('ADMIN', '系统管理员'),
('BUYER', '采购协同人员'),
('SUPPLIER', '供应商'),
('MANAGER', '企业管理人员');

INSERT INTO supplier(supplier_code, supplier_name, contact_name, contact_phone, level_code, on_time_rate) VALUES
('SUP-001', '华东精密电子有限公司', '陈维', '13800001001', 'A', 0.9620),
('SUP-002', '新港工业材料有限公司', '林青', '13800001002', 'B', 0.8840),
('SUP-003', '启明包装科技有限公司', '周禾', '13800001003', 'A', 0.9310);

INSERT INTO sys_user(username, display_name, password_hash, role_code, supplier_id) VALUES
('admin', '系统管理员', '{noop}123456', 'ADMIN', NULL),
('buyer', '采购专员·严绍诚', '{noop}123456', 'BUYER', NULL),
('supplier', '供应商·陈维', '{noop}123456', 'SUPPLIER', 1),
('manager', '供应链经理·李文生', '{noop}123456', 'MANAGER', NULL);

INSERT INTO material(material_code, material_name, category, unit, safety_stock, min_order_qty, pack_size, lead_time_days, standard_price) VALUES
('MAT-CPU-01', '工业控制芯片 A7', '电子元件', '片', 420, 200, 50, 12, 86.50),
('MAT-SEN-02', '温度传感器 T20', '电子元件', '只', 300, 100, 20, 7, 42.80),
('MAT-ALU-03', '6061 铝合金板', '结构材料', '千克', 900, 500, 100, 18, 18.60),
('MAT-CAB-04', '耐弯折屏蔽线束', '线缆组件', '套', 180, 80, 10, 9, 63.20),
('MAT-BOX-05', '防潮运输包装箱', '包装材料', '个', 260, 100, 25, 5, 12.50);

INSERT INTO warehouse(warehouse_code, warehouse_name, location_text) VALUES
('WH-001', '宁波中心仓', '浙江省宁波市'),
('WH-002', '上海备件仓', '上海市浦东新区');

INSERT INTO inventory(warehouse_id, material_id, on_hand_qty, reserved_qty, in_transit_qty) VALUES
(1, 1, 510, 160, 200),
(1, 2, 880, 210, 0),
(1, 3, 720, 400, 500),
(1, 4, 230, 90, 80),
(1, 5, 1400, 300, 0),
(2, 1, 180, 30, 0),
(2, 2, 220, 40, 100);

INSERT INTO purchase_demand(demand_no, material_id, quantity, expected_date, priority, source_type, status, notes, created_by) VALUES
('REQ-202608-001', 1, 600, '2026-09-05', 'HIGH', 'FORECAST', 'DRAFT', '14天库存覆盖不足，演示数据', 2),
('REQ-202608-002', 4, 260, '2026-09-03', 'NORMAL', 'MANUAL', 'PLANNED', '线束补充需求，演示数据', 2);

INSERT INTO purchase_plan(plan_no, plan_name, status, total_amount, created_by, approved_by, approved_at) VALUES
('PLAN-202608-001', '九月第一批电子元件采购计划', 'APPROVED', 51900.00, 2, 4, CURRENT_TIMESTAMP),
('PLAN-202608-002', '结构材料补货计划', 'PENDING_APPROVAL', 9300.00, 2, NULL, NULL);

INSERT INTO purchase_plan_item(plan_id, demand_id, material_id, supplier_id, quantity, unit_price, expected_date) VALUES
(1, 1, 1, 1, 600, 86.50, '2026-09-05'),
(2, NULL, 3, 2, 500, 18.60, '2026-09-12');

INSERT INTO purchase_order(order_no, plan_id, supplier_id, status, order_amount, expected_arrival_date, idempotency_key, created_by) VALUES
('PO-202608-001', 1, 1, 'PENDING_CONFIRMATION', 51900.00, '2026-09-05', 'seed-po-001', 2);

INSERT INTO purchase_order_item(order_id, material_id, quantity, unit_price, amount) VALUES
(1, 1, 600, 86.50, 51900.00);

INSERT INTO warning_record(warning_no, warning_type, severity, target_type, target_id, title, reason_text, suggestion_text, status) VALUES
('WARN-202608-001', 'STOCK_SHORTAGE', 'HIGH', 'MATERIAL', 1, '工业控制芯片预计出现库存缺口', '可用库存350片，安全库存420片，且未来14日需求持续', '复核预测并优先处理采购需求REQ-202608-001', 'OPEN'),
('WARN-202608-002', 'LEAD_TIME_HORIZON', 'MEDIUM', 'MATERIAL', 3, '铝合金板提前期超过预测窗口', '采购提前期18天，当前预测窗口固定14天', '结合已知计划事件并人工延长采购判断周期', 'OPEN'),
('WARN-202608-003', 'SUPPLIER_DELIVERY', 'LOW', 'SUPPLIER', 2, '供应商准时交付率低于目标', '近期开票口径下准时交付率为88.4%', '与供应商确认下批订单交期并记录改进措施', 'ACKNOWLEDGED');
