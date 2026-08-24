# 论文证据映射（工作版）

文档编号：SCIC-THESIS-001  
基线日期：2026-08-24  
当前状态：**UAT 候选，非最终 V1.0**

> 本表只提供论文写作的追踪入口，不填充未经验证的效果数字，不把模拟数据、源码或静态配置写成最终实验结论。学校论文/实训 Word 模板本轮未填写，待 UAT、测试和实验冻结后再套用。

## 1. 功能—章节—证据

| 需求/模块 | 论文章节建议 | 实际证据入口 | 状态 |
|---|---|---|---|
| BR-01 / F01 RBAC、审计 | 第3章需求；第4章安全设计；第5章登录与审计；第6章权限测试 | `backend/src/main/java/com/scic/platform/security`、`AuthController`、`operation_log`、`PlatformIntegrationTest` | 源码/测试源可追溯；完整 UAT 待补 |
| BR-02 / F02 数据接入 | 第3章数据来源；第4章导入模型；第5章预览/提交；第6章数据质量 | `DataImportController`、V1 migration、`samples/import`、`data_import_*` | 模板和源码可追溯；上传回归待补 |
| BR-03 / F05 预测 | 第3章业务需求；第4章预测流程；第5章 MA7/XGBoost；第6章实验 | `ai-service/app/forecasting.py`、`models.py`、`tests/test_forecasting.py`、`forecast_*` 表 | 契约/测试已存在；正式数据实验待补 |
| BR-04 / F06-F08 需求计划订单 | 第3章 BCL-01；第4章状态机；第5章事务实现；第6章状态/幂等测试 | `ProcurementController`、采购表、状态服务、集成测试 | 源码/测试源可追溯；完整闭环待 UAT |
| BR-05 / F09 供应商协同 | 第3章角色场景；第4章数据范围；第5章供应商操作；第6章越权测试 | `CollaborationController`、supplier scope、订单/通知表 | 源码可追溯；浏览器证据待补 |
| BR-06 / F10-F11 到货收货对账 | 第3章差异规则；第4章事务/关系；第5章协同实现；第6章回滚/差异 | `CollaborationController`、receipt/reconciliation 表、收货测试 | 关键测试源存在；故障注入待补 |
| BR-07 / F06/F08 AI 解析 | 第3章人在回路；第4章 Schema/Provider；第5章预览确认；第6章解析测试 | `ai-service/app/parsing.py`、`models.py`、`tests/test_parsing.py`、`ai_parse_record` | 规则/降级契约可追溯；Ground Truth/UAT 待补 |
| BR-08 / F12-F13 预警看板 | 第3章决策需求；第4章聚合/预警；第5章页面实现；第6章口径测试 | `SystemController`、`DashboardController`、warning 表、前端页面 | 源码可追溯；页面截图和指标核对待补 |

## 2. 论文图表候选

| 编号 | 内容 | 原始来源 | 使用条件 |
|---|---|---|---|
| FIG-04-01 | Vue—Spring—FastAPI—DB 部署/逻辑架构 | `ARCHITECTURE_AND_API_V1.0.md`、Compose | Docker 实测后标注实际版本 |
| FIG-04-02 | BCL-01 状态流程 | 路线图、后端状态服务 | 与状态代码和截图逐项核对 |
| FIG-04-03 | AI 解析人在回路 | FastAPI schema、后端 parse preview/confirm | 需保存确认前后记录 |
| FIG-05-01 | 14 日预测序列 | `forecast_result`/FastAPI response | 只使用带数据哈希的实际运行记录 |
| TAB-06-01 | 模型逐折和测试指标 | FastAPI evaluation、实验日志 | 不能凭空补数；零值 MAPE 口径注明 |
| TAB-06-02 | AI 解析准确性/错误分类 | Ground Truth 和原始响应 | 至少形成可复核数据集后再写 |
| TAB-06-03 | 测试用例执行汇总 | `TEST_PLAN_AND_CASES.md`、运行日志 | UAT-R1 回归完成后冻结 |

## 3. 写作红线

1. XGBoost 只有在平均验证 MAE 严格低于 MA7 且三个 14 日扩展窗至少胜两次时才可写成选用模型；否则写 MA7 及降级原因。
2. `confidence` 未校准，只能作为解析提示，不能写成自动决策准确率或业务批准依据。
3. FastAPI 不写数据库；业务写入、权限和确认由 Spring Boot 完成。
4. CSV 适配器原型不等于 ERP/SRM 在线接入；演示数据不等于企业生产数据。
5. 未执行的 Docker、浏览器 UAT、性能和生产安全测试必须标记待执行。

完成 UAT-R1、冻结实验数据并收集学校要求后，再把本表转化为论文正式章节、图表和模板材料；当前项目状态只能写 UAT 候选。
