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

## 2026-09-21（Asia/Shanghai）— 首批用户 UAT 反馈修复

- 当前阶段/版本：`0.9.0-UAT-RC2` 基线上的未发布 UAT 修复；非 V1.0。
- 本次目标：修复用户截图中的仓库 401/巨型警告图标，并把数据导入补成可实际选择、预览和下载的闭环。
- 输入材料：用户仓库错误截图、数据导入页截图、Windows 文件选择窗口截图及用户自有公开项目的统一 API/401 自动跳转思路。
- 完成事项：失效会话清理与回跳；错误图标和互斥状态修复；原生 CSV 选择与拖放；四类模板下载；服务端预览；错误 CSV 下载；20 MB 前后端统一；中文可读错误明细。
- 验证及结果：前端构建通过；Spring Boot 54/54；四服务恢复健康；浏览器确认无效 token 自动回登录、系统 file chooser 可用、真实 multipart 预览成功、模板和错误报告文件内容正确；390px 无整页横向溢出。
- 问题与处理：首次预览暴露 Axios 全局 JSON Content-Type 导致 multipart 请求失败，移除该默认值后由 Axios/浏览器按数据类型生成正确请求头；后端 JAR 被运行进程锁定，按精确项目清单停止、打包、重启后生效。
- 决策或范围变化：数据导入仍只接受单个 CSV，不增加文件夹批量导入，避免绕开现有单批次审计和服务端校验边界。
- 证据编号/路径：`UAT-090-001`、`UAT-090-002`、`docs/04-testing/TEST_EXECUTION_2026-09-21.md`、本地 `.playwright-cli/` 临时浏览器快照。
- 未完成项：用户复验两条反馈、其余完整 UAT、Docker Compose 容器验收和最终材料冻结。
- 唯一下一步：让用户在已打开的平台复验仓库失效会话与数据导入体验，继续登记新反馈。

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

---

## 2026-09-20（Asia/Shanghai）— 决策闭环与本轮运行态验收

- 当前阶段/版本：`0.9.0-UAT-RC2` 基线上的未发布迭代；尚无新发布标签，非 V1.0。
- 本次目标：按 Astra High 的 P0 决策补强预测采纳、分批收货、动态预警和幂等边界，由 Sol Max 优化前端流程，并为后续交接记录决策、测试和未完成项。
- 完成事项：
  - 保留 Vue 3 + Spring Boot 模块化单体 + FastAPI；Spring Boot 是业务库唯一写入方；本轮未引入微服务、MQ 或外部 LLM。
  - 新增 Flyway V4，增加预测建议状态与版本、预测来源需求追踪、预警唯一来源键/条件状态，以及同计划最多生成一个订单的唯一约束，并修正已存在数据的关联状态。
  - 预测建议由 BUYER 人工采纳或拒绝；采纳产生 `FORECAST` 来源的 `DRAFT` 采购需求。建议计算记录库存、安全库存、包装/起订、拒收和供应商交付反馈，不代表模型训练。
  - 收货支持分批，只有合格量累计进入库存和订单实收；未收齐前不能对账；发运/到达走到货通知工作流。
  - 预警按来源键去重；库存不足告警的关闭、条件解除和复发路径加入自动测试。
  - 新增到货通知申报数量上限校验与列表真实汇总，部分收货逾期按未履约余量预警；供应商准时率按最后一批合格收货时间判定。
  - 后端集成测试 #11 增加预测建议列表数量断言；新增 #15 通知超收/部分逾期剩余预警和 #16 分批最终到货绩效测试；最终 Spring Boot 集成测试为 16/16。
  - 将 `SCIC_DEMO_SYNTHETIC_HISTORY_ENABLED` 的实际范围记录为仅影响合成需求历史初始化，V2 的其它演示种子并不会因此清除。
  - 后端集成测试 16/16、FastAPI 13/13、前端 `vue-tsc` 与生产构建通过，静态检查通过。
  - 三服务健康检查通过；真实 Spring Boot→FastAPI HTTP 预测返回 14 点 XGBoost 结果并标记 `DEMO_SYNTHETIC`，provider 为 `rule`。
  - BUYER 浏览器登录、总览、预测采纳及需求草稿回读通过；预测批次 `FC-20260920194647-83DD9`（run id `6`）采纳后生成草稿 `REQ-20260920195144-48E69`；收货与预警页面通过页面级检查，浏览器控制台 0 errors / 0 warnings。
  - JAR 和前端重启后再次复核交付通知页真实创建时间与汇总申报数量 `600`；预测页展示待处理建议的“拒绝/采纳”入口；控制台仍为 0 errors / 0 warnings。
  - 后续预测页快照复核显示待处理与已采纳两行建议量均为 `290`、状态正确；待处理建议的拒绝/采纳入口可见；快照见 `page-2026-09-20T12-14-11-026Z.yml`。
- 主要变更范围：
  - 后端：`CollaborationService`、`DataImportService`、`IntelligenceController` / `IntelligenceService`、`ProcurementService`、`DemoDataInitializer`、`SystemController`、新增 `WarningService`、V4 migration、`PlatformIntegrationTest`。
  - 前端：`AppShell`、API/mappers/types 和 AI解析、预测、订单、收货、预警、物料、库存、供应商、到货/采购需求视图。
  - 配置与启动：`application.yml`、`.env.example`、`docker-compose.yml`、`scripts/start-dev.ps1`。
  - 记录：本文件、`CURRENT_STATUS.md`、`CHANGELOG.md`、`DECISION_LOG.md`、`TRACEABILITY_MATRIX.md`、`UAT_FEEDBACK.md`、`DELIVERABLE_REGISTER.md`、API/数据库字典、测试计划及本轮执行记录。
- 验证及结果：明细和证据索引见 `docs/04-testing/TEST_EXECUTION_2026-09-20.md`。后端 XML 报告在 `backend/target/surefire-reports/TEST-com.scic.platform.PlatformIntegrationTest.xml`；本机服务日志与 Playwright 页面快照在 `.data/logs/` 和 `.playwright-cli/`，后两者可能被 Git 忽略。
- 未完成项：当前工作树的 ADMIN / SUPPLIER / MANAGER 浏览器与权限复核、真实 HTTP 失败/超时路径、完整收货/预警 UI 写操作、用户个人 UAT、干净 MySQL 8/Compose 迁移验收；外部 LLM 未配置。旧 RC2 四角色截图不代表本轮工作树。
- 唯一下一步：先补齐其余角色与失败路径验收，然后由用户开展个人 UAT；按 UAT 编号修改、回归和用户复验。MySQL/Compose 与最终 V1.0 材料继续按实际完成情况登记。

## 2026-09-20（Asia/Shanghai）— 采购情景推演可运行闭环

- 当前阶段/版本：`0.9.0-UAT-RC2` 基线上的未发布迭代；非 V1.0。
- 本次目标：把“创新点”落成可操作、可复核的采购决策功能，而不是仅写入论文规划。
- 完成事项：
  - 新增 V5 `procurement_scenario` 迁移、`ScenarioService` 与 Spring 接口；Spring Boot 仍是业务库唯一写入方，没有增加微服务或 MQ。
  - 以成功预测、当前库存、未完订单、提前期、起订量和包装量为事实，模拟需求、供应延迟、安全库存、合格率和价格变化，冻结输入/来源/结果及 SHA-256 数据指纹。
  - 预览两小时有效，模拟不修改业务事实；BUYER 按版本和幂等键确认后创建 `SCENARIO` 来源采购需求草稿；MANAGER 可读不可采纳，SUPPLIER 不可模拟。
  - 新增前端采购情景推演页和采购需求来源展示，覆盖桌面、窄屏、空订单、过期/无建议和经理只读提示。
- 验证及结果：Spring Boot 18/18、FastAPI 13/13、前端 `vue-tsc`/生产构建、静态/运行健康检查和 `git diff --check` 通过；情景页控制台 0 errors / 0 warnings，390px 无整页横向溢出。最终 #18 直接断言过期审计行落库，并以 `SHIPPED` 但剩余量为零的订单证明其不会进入受影响订单；Sol Max 对该功能及当前自动化验收给出 `PASS`，MySQL/Compose 仍为未执行的环境限制。
- 运行态证据：预测 `FC-20260920215947-26C57` → 情景 `SCN-20260920215947-AA0F8`（HIGH，0→100）→ 草稿 `REQ-20260920215947-1F7B8`（`SCENARIO`）；另保留未采纳预览 `SCN-20260920220442-5F363` 供用户体验。
- 证据路径：`docs/04-testing/TEST_EXECUTION_2026-09-20.md`、`docs/assets/screenshots/08-scenario-buyer.png`、`09-scenario-manager-readonly.png`、`10-scenario-mobile.png`、后端 Surefire XML。
- 问题与处理：系统默认 Java 8/JRE 无编译器，改用 DevEco JBR 21；启动脚本会复用旧 JAR，源码更新后需先停止后端、重新 package、再启动并核对 Flyway 版本；一次测试断言忽略物料安全库存，按真实基准建议量修正；390px 空态最初正常但展开宽表后整页溢出，补充网格 `min-width: 0` 后复测为 375/375。
- 未完成项：用户个人 UAT、过期记录的浏览器体验、并发采纳压力、真实企业数据、全角色完整回归和干净 MySQL/Compose 迁移。
- 唯一下一步：用户先体验 `/scenario-simulation` 并登记 UAT 反馈；修改后再推进“建议—执行—偏差—下一轮参数”反馈闭环。

---

## 2026-09-21（Asia/Shanghai）— 本地模型、数据安全与字体收口

- 按“公司财务/供应链数据不能联网外发”的要求，将默认智能解析改为本地 Qwen2.5-1.5B-Instruct Q4_K_M + llama.cpp；模型与运行时位于 D 盘，只监听 `127.0.0.1:11435`。
- 增加 `install/start/stop-local-llm.ps1`，并接入一键启动、停止和健康检查；增加 `LLM_LOCAL_ONLY=true`，非本机 provider 配置不发送业务文本。
- AI 解析新增实际 provider/model/prompt/fallback 与字段来源证据，前端明确显示“本地大模型”或“规则降级”；超时只对 AI 链路放宽。
- 建立 12 条三类意图固定评测集。首次真实运行 7/12，保留原始 JSON；修复量词、标点、状态上下文和数量变更语义后回归 12/12，平均 3266.396 ms。
- 停止本地模型验证真实降级，再恢复服务；浏览器完成本地 Qwen 预览与人工确认检查，控制台 0 errors / 0 warnings，保存 UI-11。
- 前端引入 `@fontsource-variable/noto-sans-sc`，统一使用离线 Noto Sans SC Variable，并保留 Windows 中文字体回退；生产构建和浏览器计算样式通过，保存 UI-12。
- 最终复跑：Spring Boot 18/18、FastAPI 19/19、前端生产构建和静态检查通过。当前环境没有 Docker/MySQL，真实 MySQL 8/Compose 仍明确记录为未执行。

---

## 2026-09-21（Asia/Shanghai）— 真实 MySQL、安全撤销与最终回归

- 在 D 盘安装并运行便携 MySQL 8.4.11，使用隔离端口 3307 和 `scic_platform_uat_clean` 干净库；Flyway 从 V1 执行至 V5，未依赖 Docker。
- 新增并执行 `scripts/verify_mysql_uat.py`。首轮 163/163 通过；审查发现账号被停用后已签发 JWT 仍可继续访问，因此不把首轮结果作为最终安全结论。
- 修改 `JwtAuthenticationFilter`：JWT 签名通过后每次重新读取 `sys_user` 当前启用状态、角色和供应商绑定。补充 H2 集成测试 #19 与 MySQL `AUTH-REVOKED-TOKEN`，验证停用后旧 token 返回 401、重新登录也被拒绝。
- 最终 MySQL 独立验收为 164/164，报告 `output/acceptance/mysql-uat-202609210244488FAB74.json`；覆盖四角色、完整采购闭环、状态边界、幂等、供应商隔离、库存/对账一致性、审计和原始演示数据保全。报告不含数据库密码或 JWT。
- 后端最终自动化为 54/54（业务集成 19、AI 故障集成 35），FastAPI 19/19；前端 `vue-tsc` 与生产构建、静态/运行健康检查全部通过。本地 Qwen、AI、H2 后端和前端均健康。
- ADMIN、SUPPLIER、MANAGER、BUYER 当前工作树浏览器证据已保存为 UI-13—UI-17；最终 BUYER 总览复核控制台 0 errors / 0 warnings，并归档 UI-18 `docs/assets/screenshots/18-buyer-final-dashboard.png`。浏览器保持打开供用户体验。
- 重新打包前发现旧 Java 进程锁定 JAR；按绝对路径和项目进程清单停止后解决。随后发现 PowerShell `ConvertFrom-Json` 已将 ISO UTC 字段转成 `DateTime`，旧校验再次解析导致 8 小时重复偏移；修复 `local-process-identity.ps1`，继续使用 PID、启动时间、程序路径、JAR 参数与端口联合校验，未降低安全边界。
- Docker Compose 仍未实跑，用户个人 UAT 仍未开始，学校正式 Word/Excel、论文和答辩 PPT 尚未冻结；当前仍为 `0.9.0-UAT-RC2` 基线上的未发布候选，不标记 V1.0。
- 唯一下一步：用户先在已打开页面进行个人 UAT；按 UAT 编号修复并回归。平台确认后，再进入论文初稿和学校模板整理。

---

## 2026-09-21（Asia/Shanghai）— 角色待办中心

- 参考实际财务/资产系统的“责任到人、原单追踪”思路，新增角色待办中心，但不复制一套容易失真的任务状态。
- 后端从既有预警、需求、计划、订单、收货和对账事实实时生成待办；BUYER、MANAGER、SUPPLIER 各取职责范围，供应商按绑定主数据隔离，ADMIN 被拒绝。
- 前端新增“我的待办”、摘要、优先级/类别筛选和原业务入口；所有处理仍回原模块，保留原权限、版本、幂等和状态机校验。
- Spring Boot 56/56、前端生产构建通过；BUYER 桌面和 390px 浏览器无整页横向溢出，离线 Noto Sans SC 生效，控制台 0 errors / 0 warnings。
- 证据：`docs/assets/screenshots/21-work-queue-desktop.png`、`22-work-queue-mobile.png`。该内部验证不代替用户个人 UAT。
