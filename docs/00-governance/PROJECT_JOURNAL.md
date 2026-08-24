# 项目真实日志

用途：作为项目日报、实训报告、测试报告、代码评审和答辩材料的原始事实来源。按真实日期记录，不倒填、不补造。

## 记录模板

- 日期与时区：
- 当前阶段/版本：
- 本次目标：
- 输入材料：
- 完成事项：
- 新增或修改文件：
- 验证及结果：
- 问题与处理：
- 决策或范围变化：
- 证据编号/路径：
- 未完成项：
- 唯一下一步：

---

## 2026-08-24（Asia/Shanghai）

- 当前阶段/版本：M0 项目规划 / 候选路线图 V1.0
- 本次目标：从零完成总体规划，并建立可跨会话交接的事实基线。
- 输入材料：学校课题登记与研究内容截图、项目总控文案、课程交付物清单截图。
- 完成事项：
  - 盘点 D:\论文，确认规划前为空，无代码、SQL、论文和实验；
  - 确定三项工程创新、四类角色、BCL-01 主闭环；
  - 选择 Vue 3 + Spring Boot 模块化单体 + FastAPI + MySQL；
  - 选择 MA7 基线与 XGBoost 主模型，明确 LLM 不负责数值预测；
  - 建立路线图、决策日志、当前状态、追踪矩阵和变更记录；
  - 按用户要求固定项目与大型缓存使用 D 盘；
  - 将临时附件复制到 D:\论文\docs\assets\source-evidence 保存；
  - 登记课程可见交付物及其原始记录要求；
  - 确认五份 Word/Excel 参考模板位于 D 盘，仅登记、不读取；
  - 按用户要求确定平台优先、材料后置、证据同步和个人 UAT 闭环。
- 新增或修改文件：
  - README.md；
  - docs/00-governance 下全部总控文件；
  - docs/assets/source-evidence 下六份原始证据。
- 验证及结果：
  - Luna Max 只读盘点确认工作区原为空；
  - Sol Max 首轮架构方向审查通过，但契约一致性结论为 FAIL，列出六类小修项；
  - 已修复预测窗口、服务边界、导入口径、治理状态、数据库关系和 LLM 评估口径；
  - Sol Max 第二轮仅发现“平台优先”与 M0 正式套模板任务冲突；
  - 已将 M0 改为工作型记录，将正式说明书和计划移至平台 UAT 后；
  - Sol Max 第三轮验收结论为 PASS。
- 问题与处理：
  - C盘空间紧张：不在C盘生成项目交付物，规划 D:\论文\.cache 与 .data；
  - 截图只显示部分课程任务：仅登记可见序号，不推测缺失项。
- 决策或范围变化：角色由初稿五类收敛为四类；收货和对账由采购协同人员承担。
- 证据路径：docs/assets/source-evidence。
- 未完成项：用户确认候选路线图。
- 唯一下一步：用户确认 ROADMAP_V1.0.md 作为正式项目基线。

---

## 2026-08-24（Asia/Shanghai）— V0.9 执行证据收口

- 当前阶段/版本：V0.9 个人 UAT 候选 / `0.9.0-UAT-RC2`。
- 本次目标：把已执行的后端、AI、静态检查、API 闭环、性能和实验事实写入可交接证据，并保持候选版边界。
- 输入材料：后端 Surefire 报告、FastAPI pytest 输出、`scripts/verify.ps1 -StaticOnly` 输出、本机 H2 运行记录、预测执行摘要和 AI 规则解析烟测结果。
- 完成事项：
  - Spring Boot `PlatformIntegrationTest` 10 项通过，0 failures、0 errors、0 skipped；
  - FastAPI AI 测试 13 项通过；
  - 静态检查通过，核对脚本、必需路径、D 盘路径约束、CSV 表头和部署静态文件；
  - API 真实跑通 BCL-01 后半闭环：供应商确认、备货、到货通知、发运、到达、收货入库、对账确认与完成，订单终态为 `COMPLETED`；
  - 记录 10 并发、200 次非 AI 看板请求：无非 2xx/5xx，P95 为 `88.81 ms`，运行环境为本机 H2；
  - 登记 `DEMO_SYNTHETIC` XGBoost 预测实验摘要：MAE `1.443933`、RMSE `3.214012`、MAPE `3.165298%`、3 折、14 点；
  - 登记 AI 规则解析烟测，明确 provider 为 `rule`，未配置或调用外部 OpenAI-compatible LLM；
  - 使用真实浏览器完成四角色基础页面巡检，保存 7 张候选版截图；
  - 发现并修复预测明细日期优先级错误，修复后 14 点区间为 `2026-08-24` 至 `2026-09-06`；补齐 favicon 并复核全新登录页控制台 0 errors / 0 warnings；
  - 将测试执行记录、预测实验记录、AI 烟测和浏览器证据纳入交接文档，更新交付物登记、变更记录和追踪矩阵。
- 新增或修改文件：
  - `docs/04-testing/TEST_EXECUTION_2026-08-24.md`；
  - `docs/05-experiments/FORECAST_RUN_2026-08-24.md`；
  - `docs/05-experiments/AI_PARSE_SMOKE_2026-08-24.md`；
  - `docs/00-governance/DELIVERABLE_REGISTER.md`；
  - `docs/00-governance/PROJECT_JOURNAL.md`；
  - `docs/00-governance/CHANGELOG.md`；
  - `docs/00-governance/TRACEABILITY_MATRIX.md`。
- 验证及结果：后端 Surefire 报告为 10/0/0/0；AI pytest 为 13 passed；前端生产构建通过；`verify.ps1 -StaticOnly` 返回“静态验收通过”；本机 `docker` 命令未找到。
- 问题与处理：本机未安装 Docker，未声称 Docker/Compose/MySQL 已部署；浏览器只登记真实候选版截图，不虚构用户 UAT；实验数据保留 `DEMO_SYNTHETIC` 标签。
- 决策或范围变化：当前仍为 V0.9 UAT 候选，不升级为 V1.0；外部 LLM、生产数据、用户个人 UAT 和干净 Compose 复现继续属于未完成发布门槛。
- 证据路径：`docs/04-testing/TEST_EXECUTION_2026-08-24.md`、`docs/05-experiments/FORECAST_RUN_2026-08-24.md`、`docs/05-experiments/AI_PARSE_SMOKE_2026-08-24.md`、`docs/assets/screenshots/README.md`、`backend/target/surefire-reports/TEST-com.scic.platform.PlatformIntegrationTest.xml`、`ai-service/tests/`、`.data/logs/`。
- 未完成项：用户个人 UAT、干净 MySQL 8/Compose 复现、最终阻断项复核及 V1.0 材料冻结。
- 唯一下步：完成 Sol Max 对 RC2 的独立复验后，由用户开始个人 UAT；收到反馈时按编号复现、修改、回归和复验。

---

## 2026-08-25（Asia/Shanghai）— Sol 独立验收阻断修复与 RC2 收口

- Sol 首轮独立验收结论为 FAIL，指出供应商全局数据泄露、预测物料错配、供应商截图错误、AI 补全确认链断点、管理员业务写边界不一致和证据路径错误。
- 已限制供应商读取主数据、全局预警和数据来源，并清空供应商看板全局预警指标；订单范围继续按绑定供应商过滤。
- 已让预测页读取真实物料，后端按 `materialCode` 严格过滤批次，选择器、标题及重算请求使用同一编码。
- 已在 AI 预览补全时重算 `schema_valid` 与 `business_valid`，使补全后人工确认链闭合。
- 已移除管理员处理业务预警权限，并隐藏管理者看板上的“创建采购需求”动作。
- 新增两项集成测试并扩充原测试断言，最终 Spring Boot 10/0/0/0、FastAPI 13 passed、前端生产构建通过。
- 已修正 EV-04 migration 路径；供应商订单和预测一致性截图已在 RC2 运行态重新取得并逐张核对。
- 本次仍不声称用户 UAT、Docker/MySQL 或最终 V1.0 已完成。
