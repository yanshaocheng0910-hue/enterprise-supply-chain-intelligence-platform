# 数据库字典与导入字段说明（工作版）

文档编号：SCIC-DATA-001  
基线日期：2026-08-24  
当前状态：**UAT 候选，非最终 V1.0**

数据库结构以 `backend/src/main/resources/db/migration/V1__core_schema.sql` 为准，种子数据来自 V2 迁移。开发默认使用 H2 文件 `D:\论文\.data\h2\scic`，Compose/生产候选使用 MySQL 8。FastAPI 不连接这些数据库；它只计算请求中的数据。学校 Word/Excel 数据字典模板本轮未填写，本文件只是项目内工作字典和证据入口。

## 1. 统一约定

- 所有 `id` 为自增 BIGINT；业务编号（如 `supplier_code`、`order_no`、`batch_no`）另有唯一约束。
- 数量使用 `DECIMAL(18,4)`，金额使用 `DECIMAL(18,2)`；日期为 DATE，时间为 TIMESTAMP。
- `status`、`role_code`、`source_type` 等为代码值，后端状态服务负责合法迁移。
- `version` 用于乐观并发控制；`idempotency_key` 用于提交/收货等幂等操作。
- `data_label` 区分 `BUSINESS`、`DEMO_SYNTHETIC` 等来源；演示数据不能在论文中描述为真实企业生产数据。
- AI 解析原文、模型响应和规范化结果由 Spring 业务层保存到 `ai_parse_record`，FastAPI 本身不写库。

## 2. 表目录

### 2.1 组织、主数据和库存

| 表 | 关键字段 | 关系/用途 |
|---|---|---|
| `sys_role` | `id`、`role_code`、`role_name`、`created_at` | 角色字典；`role_code` 唯一 |
| `supplier` | `supplier_code`、`supplier_name`、联系人、`level_code`、`status`、`on_time_rate`、`version` | 供应商主数据；被用户、计划项、订单、到货引用 |
| `sys_user` | `username`、`display_name`、`password_hash`、`role_code`、`supplier_id`、`enabled`、`version` | 账号与角色；供应商账号绑定 `supplier_id` |
| `material` | `material_code`、`material_name`、`category`、`unit`、`safety_stock`、`min_order_qty`、`pack_size`、`lead_time_days`、`standard_price`、`status`、`version` | 物料主数据；预测和业务单据引用 |
| `warehouse` | `warehouse_code`、`warehouse_name`、`location_text`、`status` | 仓库主数据 |
| `inventory` | `warehouse_id`、`material_id`、`on_hand_qty`、`reserved_qty`、`in_transit_qty`、`version` | `(warehouse_id, material_id)` 唯一；当前库存 |
| `inventory_transaction` | `transaction_no`、仓库/物料、`transaction_type`、`quantity`、`before_qty`、`after_qty`、`source_type/source_id`、`operator_id` | 收货等动作产生的库存流水 |

### 2.2 数据接入与需求

| 表 | 关键字段 | 关系/用途 |
|---|---|---|
| `data_import_batch` | `batch_no`、`import_type`、`source_system`、`original_filename`、`file_hash`、`template_version`、`idempotency_key`、状态和行数 | CSV 预览/提交批次；类型/文件哈希和幂等键约束重复提交 |
| `data_import_error` | `batch_id`、`row_number`、`field_name`、`error_code`、`error_message`、`raw_data` | 行级校验错误 |
| `demand_history` | `material_id`、`demand_date`、`quantity`、`source_system`、`import_batch_id`、`data_label` | 预测历史；`(material_id,demand_date,source_system)` 唯一 |
| `demand_event` | `material_id`、`event_date`、`event_type`、`quantity_delta`、`description`、`source_system` | 已知计划事件/需求变化说明 |

### 2.3 采购、协同和收货

| 表 | 关键字段 | 关系/用途 |
|---|---|---|
| `purchase_demand` | `demand_no`、`material_id`、`quantity`、`expected_date`、`priority`、`source_type/source_ref`、`forecast_run_id`、`status`、`created_by`、`version` | 采购需求草稿和来源追踪；预测采纳需求以唯一 `forecast_run_id` 关联原预测批次 |
| `purchase_plan` | `plan_no`、`plan_name`、`status`、`total_amount`、审批人/时间、`version` | 采购计划主表 |
| `purchase_plan_item` | `plan_id`、`demand_id`、`material_id`、`supplier_id`、`quantity`、`unit_price`、`expected_date` | 计划明细 |
| `purchase_order` | `order_no`、`plan_id`（唯一）、`supplier_id`、`status`、`order_amount`、`expected_arrival_date`、`idempotency_key`、`version` | 已审批计划生成的订单；数据库约束保证一个计划至多生成一个订单 |
| `purchase_order_item` | `order_id`、`material_id`、`quantity`、`received_qty`、`unit_price`、`amount` | 订单明细和累计合格实收；拒收数量不计入 `received_qty` |
| `delivery_notice` | `notice_no`、`order_id`、`supplier_id`、`status`、`expected_arrival_at`、承运商/运单、`source_type`、`version` | 供应商到货通知 |
| `delivery_notice_item` | `notice_id`、`order_item_id`、`quantity` | 到货明细 |
| `receipt` | `receipt_no`、`order_id`、`notice_id`、`warehouse_id`、`status`、`received_by`、`idempotency_key` | 收货主表；收货和库存事务一致 |
| `receipt_item` | `receipt_id`、`order_item_id`、`material_id`、订单/实收/合格/拒收/差异数量、`difference_reason` | 收货差异明细 |
| `reconciliation` | `reconciliation_no`、`order_id`（唯一）、`status`、订单/实收/差异金额、争议与确认字段、`version` | 订单—收货差异主表 |
| `reconciliation_item` | `reconciliation_id`、`order_item_id`、订单/实收数量、单价、差异金额 | 对账明细 |

### 2.4 智能、预警和审计

| 表 | 关键字段 | 关系/用途 |
|---|---|---|
| `forecast_run` | `run_no`、`material_id`、`as_of_date`、`horizon_days`、`model_name/version`、`data_hash`、`data_label`、MAE/RMSE/MAPE、`fallback_reason`、`validation_details`、`split_details`、`feature_version`、`random_seed`、`status`、`suggestion_status`、`suggestion_decision_note/by/at`、`optimization_note`、`version` | 预测运行元数据及采购建议的待处理/采纳/拒绝状态；默认窗口 14 日 |
| `forecast_result` | `run_id`、`forecast_date`、`predicted_qty`、原始/区间值、`suggested_order_qty`、`warning_code`、`postprocess_note` | 每运行每日期唯一；保存后处理说明 |
| `procurement_scenario` | `scenario_no/name`、`material_id`、`forecast_run_id`、输入/来源/结果 JSON 快照、`data_fingerprint`、基准/模拟建议量与金额、`risk_level`、`status`、`version`、模拟/采纳幂等键、`adopted_demand_id`、创建/过期/采纳时间 | 冻结采购情景推演证据；`PREVIEW/ADOPTED/EXPIRED`；模拟不改业务事实，采纳后关联唯一需求 |
| `ai_parse_record` | `parse_no`、`task_type`、`schema_version`、`input_text`、上下文、`provider/model_name`、`prompt_version`、原始/规范 JSON、校验标记、`final_status`、确认人/时间、`request_id` | 受限解析预览、确认和审计 |
| `warning_record` | `warning_no`、`warning_type`、`severity`、目标、标题、原因/建议、`status`、`source_key`（唯一）、`condition_active`、`last_detected_at`、`updated_at`、处理人/结果 | 缺货、延期、差异等规则/事件风险闭环；唯一来源键用于去重，条件状态用于区分人工关闭与风险解除 |
| `operation_log` | `request_id`、操作者/角色、`action_code`、目标、前后状态、`detail_text`、时间 | 关键操作审计 |

## 3. 状态代码原则

路线图定义的主状态包括：计划 `DRAFT/PENDING_APPROVAL/APPROVED/ORDER_CREATED/CLOSED`（异常 `REJECTED/CANCELLED`）；订单 `PENDING_CONFIRMATION/CONFIRMED/PENDING_SHIPMENT/SHIPPED/ARRIVED/PARTIALLY_RECEIVED/RECEIVED/RECONCILING/COMPLETED`（异常 `REJECTED/CANCELLED`）；到货通知从 `DRAFT/SUBMITTED/IN_TRANSIT/ARRIVED/RECEIVED` 变化；对账从 `PENDING/CONFIRMED/COMPLETED` 或 `DISPUTED/RESOLVED` 变化。分批收货期间订单为 `PARTIALLY_RECEIVED`；只有全部订单数量合格收齐后才进入 `RECEIVED` 并允许创建对账。实际可用动作以当前后端状态服务和迁移数据为准。

## 4. 当前迁移补录

`V4__decision_loop_and_warning_rules.sql` 为 V1—V3 之后的增量迁移，执行时新增预测建议状态/版本/说明字段、`purchase_demand.forecast_run_id` 外键及唯一索引、预警条件与来源字段及唯一索引，并增加 `purchase_order(plan_id)` 唯一索引。迁移还把已有计划引用的需求从 `DRAFT` 校正为 `PLANNED`，把已有订单关联的计划从 `APPROVED` 校正为 `ORDER_CREATED`。

`V5__procurement_scenario_simulation.sql` 新增 `procurement_scenario`，保存输入、事实和结果快照、数据指纹、结果摘要、状态/版本、两类幂等键和采纳需求引用。当前 D 盘 H2 运行库已由 Flyway 升级到 V5，Spring Boot 54 项集成测试通过；便携 MySQL 8.4.11 干净库也已执行 V1—V5 并通过 164 项独立验收。Docker Compose 容器复现尚未执行。

## 5. CSV 导入字段字典

示例位于 `D:\论文\samples\import`，对应后端 `import_type` 为 `SUPPLIER`、`MATERIAL`、`INVENTORY`、`DEMAND_HISTORY`。提交前先调用 preview，确认无错误再 commit。

| 文件 | 字段（顺序即模板） | 类型/校验摘要 |
|---|---|---|
| `suppliers.csv` | `supplier_code,supplier_name,contact_name,contact_phone,level_code` | 编码和名称必填；编码唯一；level 为演示等级 |
| `materials.csv` | `material_code,material_name,category,unit,safety_stock,min_order_qty,pack_size,lead_time_days,standard_price` | 编码、名称、类别、单位必填；数量/价格非负；提前期为整数 |
| `inventory.csv` | `warehouse_code,material_code,on_hand_qty,reserved_qty,in_transit_qty` | 仓库和物料必须存在；数量非负；同仓库/物料唯一 |
| `demand_history.csv` | `material_code,demand_date,quantity` | 物料必须存在；日期为 ISO `YYYY-MM-DD`；数量非负；重复日期按批次/来源约束 |

CSV 是文件适配器原型，不代表已经接入真实 ERP/SRM。正式实验应另存固定种子、来源、批次和数据质量报告；本轮四份文件只作为 UAT 演示模板。
