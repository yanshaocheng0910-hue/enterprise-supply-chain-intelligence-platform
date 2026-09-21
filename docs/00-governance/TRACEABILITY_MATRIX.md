# V0.9 UAT 候选追踪矩阵（2026-09-20 增量）

本表保留 RC2 证据，并追加 2026-09-20—21 未发布工作树验证。接口、测试和截图编号持续追加。当前 Spring Boot 54 项、FastAPI 19 项、便携 MySQL 164/164、前端生产构建与静态检查通过；本地 Qwen 混合解析链固定集 12/12，并有四角色当前工作树浏览器证据。用户个人 UAT、Docker Compose 复现和最终 V1.0 材料尚未完成。

| 需求编号 | 核心需求 | 功能模块 | 核心数据表 | 主要论文位置 | 已定位的 V0.9 执行证据 | 当前状态/待补 |
|---|---|---|---|---|---|---|
| BR-01 | 用户、角色、权限和数据隔离 | F01 | sys_user、sys_role、operation_log | 3.角色与非功能需求；4.安全设计；5.权限实现；6.权限测试 | 后端四角色登录、供应商范围、错误角色拒绝；#19 与 MySQL `AUTH-REVOKED-TOKEN` 验证停用账号旧 JWT 失效；UI-13—UI-17 | V0.9 候选接口、当前账号状态回查与四角色页面已验证；完整用户 UAT 待补 |
| BR-02 | 多源数据接入、质量校验和溯源 | F02、F03、F04 | data_import_batch、supplier、material、inventory、demand_history、demand_event | 3.数据需求；4.数据设计；5.导入实现；6.数据质量测试 | 非法 CSV 预览不改业务表；`verify.ps1 -StaticOnly` 通过并核对四类 CSV 表头；`samples/import/*.csv` | V0.9 候选；真实上传、批次/错误报告和浏览器证据待补 |
| BR-03 | 智能需求预测和采购建议 | F05 | demand_history、demand_event、forecast_run、forecast_result、purchase_demand、warning_record | 2.预测技术；4.预测设计；5.预测实现；6.预测实验 | `FORECAST_RUN_2026-08-24.md`：`DEMO_SYNTHETIC`、XGBoost MAE 1.443933、RMSE 3.214012、MAPE 3.165298%、3 折、14 点；本轮 `TC-FC-006` / 集成测试 #11 验证建议量列表、人工采纳、需求追溯和重放；HTTP 预测及 BUYER 浏览器采纳已通过 | 自动化与成功运行态路径通过；非 BUYER 拒绝路径和个人 UAT 待补；旧 UI-03 不代表本轮页面验收 |
| BR-04 | 自然语言采购需求与变更解析 | F06、F07 | ai_parse_record、purchase_demand、purchase_plan | 2.大模型技术；4.AI安全设计；5.AI实现；6.AI实验 | AI 19 项测试；Qwen2.5 本地混合解析 12/12；UI-11；记录 provider/model/prompt/回退和字段来源；要求人工确认 | 真实本地 LLM、受控降级和浏览器预览已验证；更大 Ground Truth、人工修改率及用户 UAT 待补 |
| BR-05 | 采购需求、计划和订单闭环 | F06、F07、F08 | purchase_demand、purchase_plan、purchase_plan_item、purchase_order、purchase_order_item | 3.业务流程；4.状态设计；5.采购实现；6.流程测试 | BCL-01 后半 API 实测通过；本轮 `TC-BIZ-006` / 集成测试 #14 验证一个计划至多一单和精确幂等键；`TC-BIZ-007` / #3 验证直接发运被拒 | 候选逻辑和自动化已验证；并发数据库压力、本轮浏览器流程及用户 UAT 待补 |
| BR-06 | 供应商确认与到货协同 | F09、F10 | purchase_order、delivery_notice、delivery_notice_item、operation_log | 3.协同场景；4.协同设计；5.供应商实现；6.角色测试 | BCL-01 `CONFIRM`→`PREPARE_SHIPMENT`→到货通知 `SUBMIT`→`DISPATCH`→`ARRIVE`；本轮 `TC-BIZ-007` 确认直接 `SHIP` 被拒，`TC-DEL-001` 覆盖通知申报上限和列表汇总 | BUYER 通知列表时间及汇总数量浏览器检查通过；SUPPLIER 协同写操作和用户 UAT 待补 |
| BR-07 | 收货、库存更新和对账 | F04、F10、F11 | receipt、receipt_item、inventory、inventory_transaction、reconciliation、reconciliation_item | 3.收货对账需求；4.事务设计；5.实现；6.一致性测试 | BCL-01 收货入库、库存增加、`RECEIPT` 流水、对账 `CONFIRM`→`COMPLETE`；`TC-BIZ-005` / 集成测试 #12；MySQL 164 项验收覆盖分批拒收、库存、对账和数据保全 | H2 与 MySQL 自动化路径通过；故障注入、完整 UI 写操作和用户 UAT 待补 |
| BR-08 | 异常预警、风险说明和管理可视化 | F12、F13 | warning_record、forecast_result、operation_log | 3.管理需求；4.预警与展示设计；5.实现；6.测试 | 非 AI 看板 10 并发、200 请求无非 2xx/5xx，P95 88.81 ms；本轮 `TC-WARN-001` / 集成测试 #13 验证库存预警生命周期；`TC-WARN-002` / #15 验证部分收货逾期剩余量；静态交付检查通过 | 库存预警页面已做页面级浏览器检查；完整处置、长时间任务运行、生产性能和用户 UAT 待补 |
| BR-09 | 基于预测、库存与在途事实的采购情景推演和人工采纳 | F05、F06、F13 | forecast_run、forecast_result、inventory、purchase_order、procurement_scenario、purchase_demand、operation_log | 3.决策需求；4.情景模型与安全边界；5.情景实现；6.对照与权限测试 | V5 迁移；`TC-SCN-001/002`；集成测试 #17/#18；真实 H2 API；BUYER 桌面/390px 与 MANAGER 只读浏览器证据 | 确定性规则、冻结快照、幂等和权限路径通过；MySQL 本轮验证迁移兼容但未单列情景接口，仍需用户 UAT、真实数据、过期 UI、并发压力及 Compose |

## 实现阶段补录字段

每行后续补充：

- API 编号与 OpenAPI 路径；
- 页面编号与截图编号；
- 数据库迁移版本；
- 功能、异常、权限和状态测试编号；
- 对应提交或发布版本；
- 论文图、表、章节引用位置。
