# V0.9 UAT 候选追踪矩阵

本表在规划期基线之上补录到 `0.9.0-UAT-RC2` 的执行证据。接口编号、测试编号和截图编号应持续追加，禁止删除已有编号后重新编号。当前仍是 V0.9 UAT 候选；候选版浏览器截图已完成，用户个人 UAT、干净 Compose/MySQL 复现和最终 V1.0 材料尚未完成。

| 需求编号 | 核心需求 | 功能模块 | 核心数据表 | 主要论文位置 | 已定位的 V0.9 执行证据 | 当前状态/待补 |
|---|---|---|---|---|---|---|
| BR-01 | 用户、角色、权限和数据隔离 | F01 | sys_user、sys_role、operation_log | 3.角色与非功能需求；4.安全设计；5.权限实现；6.权限测试 | 后端 10 项集成测试中的四角色登录、供应商订单/主数据/预警隔离、错误角色/管理员写入拒绝；UI-01、UI-05、UI-06、UI-07；`TEST_EXECUTION_2026-08-24.md` | V0.9 候选接口与四角色页面已验证；完整用户 UAT 待补 |
| BR-02 | 多源数据接入、质量校验和溯源 | F02、F03、F04 | data_import_batch、supplier、material、inventory、demand_history、demand_event | 3.数据需求；4.数据设计；5.导入实现；6.数据质量测试 | 非法 CSV 预览不改业务表；`verify.ps1 -StaticOnly` 通过并核对四类 CSV 表头；`samples/import/*.csv` | V0.9 候选；真实上传、批次/错误报告和浏览器证据待补 |
| BR-03 | 智能需求预测和采购建议 | F05 | demand_history、demand_event、forecast_run、forecast_result、purchase_demand、warning_record | 2.预测技术；4.预测设计；5.预测实现；6.预测实验 | `FORECAST_RUN_2026-08-24.md`：`DEMO_SYNTHETIC`、XGBoost MAE 1.443933、RMSE 3.214012、MAPE 3.165298%、3 折、14 点；AI 测试 13 项；UI-03 | 候选实验和逐日页面证据已完成；生产数据和用户 UAT 待补 |
| BR-04 | 自然语言采购需求与变更解析 | F06、F07 | ai_parse_record、purchase_demand、purchase_plan | 2.大模型技术；4.AI安全设计；5.AI实现；6.AI实验 | `AI_PARSE_SMOKE_2026-08-24.md`；AI 13 项测试；UI-04；provider 明确为 `rule`，要求人工确认 | V0.9 离线规则契约与浏览器预览已验证；外部 LLM、Ground Truth、错误分类和用户 UAT 待补 |
| BR-05 | 采购需求、计划和订单闭环 | F06、F07、F08 | purchase_demand、purchase_plan、purchase_plan_item、purchase_order、purchase_order_item | 3.业务流程；4.状态设计；5.采购实现；6.流程测试 | BCL-01 后半 API 实测通过；后端测试覆盖需求/计划、非法状态和幂等；订单终态 `COMPLETED` | V0.9 候选接口闭环已通过；浏览器跨角色截图和用户 UAT 待补 |
| BR-06 | 供应商确认与到货协同 | F09、F10 | purchase_order、delivery_notice、delivery_notice_item、operation_log | 3.协同场景；4.协同设计；5.供应商实现；6.角色测试 | BCL-01 `CONFIRM`→`PREPARE_SHIPMENT`→到货通知 `SUBMIT`→`DISPATCH`→`ARRIVE`→收货后 `RECEIVED`；UI-05 | V0.9 API 与供应商页面证据已定位；完整用户 UAT 待补 |
| BR-07 | 收货、库存更新和对账 | F04、F10、F11 | receipt、receipt_item、inventory、inventory_transaction、reconciliation、reconciliation_item | 3.收货对账需求；4.事务设计；5.实现；6.一致性测试 | BCL-01 收货入库、库存增加、`RECEIPT` 流水、对账 `CONFIRM`→`COMPLETE`；收货/对账幂等复交返回同一记录 | V0.9 后端/API 候选已通过；差异/故障注入、浏览器证据和用户 UAT 待补 |
| BR-08 | 异常预警、风险说明和管理可视化 | F12、F13 | warning_record、forecast_result、operation_log | 3.管理需求；4.预警与展示设计；5.实现；6.测试 | 非 AI 看板 10 并发、200 请求无非 2xx/5xx，P95 88.81 ms；UI-02、UI-07；静态交付检查通过 | V0.9 本机 H2 接口与看板证据已记录；生产性能和用户 UAT 待补 |

## 实现阶段补录字段

每行后续补充：

- API 编号与 OpenAPI 路径；
- 页面编号与截图编号；
- 数据库迁移版本；
- 功能、异常、权限和状态测试编号；
- 对应提交或发布版本；
- 论文图、表、章节引用位置。
