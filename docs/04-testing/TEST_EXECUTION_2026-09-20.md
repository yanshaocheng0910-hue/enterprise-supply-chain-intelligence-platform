# 测试执行记录（2026-09-20）

文档编号：SCIC-TEST-EXEC-20260920
执行日期：2026-09-20（Asia/Shanghai）
测试对象：`0.9.0-UAT-RC2` 基线上的本轮未发布工作树
当前状态：**候选迭代；自动化、静态检查和指定运行态联调通过；用户 UAT 未完成**

> 本记录区分自动化测试、构建检查和运行态验收。代码改动尚未形成新发布标签；本轮旧 RC2 浏览器证据不能替代对当前工作树的浏览器复核。

## 1. 执行摘要

| 检查 | 结果 | 范围与证据 |
|---|---|---|
| Spring Boot 集成测试 | **18 passed，0 failures，0 errors，0 skipped** | `PlatformIntegrationTest`；RC2 基线 10 项加本轮 8 项；#17/#18 为采购情景主链及边界；报告路径 `backend/target/surefire-reports/TEST-com.scic.platform.PlatformIntegrationTest.xml` |
| FastAPI AI 测试 | **13 passed** | `ai-service/tests/`；本轮测试输出。覆盖当前规则解析与预测服务契约 |
| 前端类型检查 | **通过** | `vue-tsc` 本轮输出 |
| 前端生产构建 | **通过** | `npm run build` 本轮输出 |
| 项目静态检查 | **通过** | `scripts/verify.ps1 -StaticOnly` 本轮输出 |
| 本地三服务健康检查 | **通过** | 最终 JAR 重启后本轮运行态检查；AI、Backend、Frontend 均健康 |
| Spring Boot → FastAPI 真实 HTTP 预测 | **通过（范围受限）** | 本机真实调用返回 14 点预测；选择 `XGBOOST`，数据标签为 `DEMO_SYNTHETIC` |
| 本轮浏览器验证 | **通过（指定页面）** | 原 BUYER 预测/履约页面检查；新增 BUYER 情景模拟桌面与 390px 窄屏、MANAGER 情景只读边界；情景页控制台 0 errors / 0 warnings，窄屏无整页横向溢出；未覆盖四角色全量验收 |
| Sol Max 独立验收 | **功能与自动化验收 PASS；环境限制保留** | 采购情景功能未发现未关闭 P0/P1；18 项自动化覆盖过期审计落库、并发幂等和受影响订单余量边界。MySQL/Compose 仍未执行，不代表 V1.0、生产就绪或用户验收通过 |
| Docker / MySQL 干净环境 | **未执行** | 当前无本轮 Docker/Compose 启动及新库迁移证据 |
| 用户个人 UAT | **尚未开始** | 等待用户按测试计划进行实际体验并登记反馈 |

## 2. 本轮运行态联调证据

本轮本地 AI、Backend、Frontend 三服务均健康。Spring Boot 通过真实 HTTP 调用 FastAPI 预测服务，预测批次为 `FC-20260920194647-83DD9`（run id `6`），返回 14 个连续预测点，选择 `XGBOOST`，并标注 `data_label=DEMO_SYNTHETIC`。AI 服务 provider 仍为 `rule`；没有调用真实外部 LLM。

最终 JAR 和前端代码重启后，使用 BUYER 账号重新复核页面。预测批次 `FC-20260920194647-83DD9`（run id `6`）由 BUYER 采纳，采购需求列表回读到草稿 `REQ-20260920195144-48E69`。后续再次复核预测列表时，待处理及已采纳记录都显示建议数量 `290`，状态正确；待处理记录显示“拒绝/采纳为采购需求”入口。交付通知页真实显示创建时间和汇总申报数量 `600`；收货页、预警页完成页面级检查。以上页面控制台均为 0 errors / 0 warnings。收货过账与预警处置的完整浏览器写操作仍未覆盖。

可复核的本机临时证据（可能被 Git 忽略，不作为唯一长期证据）：

- `D:\论文\.data\logs\ai-service.out.log`
- `D:\论文\.data\logs\backend.log`
- `D:\论文\.data\logs\backend.out.log`
- `D:\论文\.data\logs\frontend.out.log`
- `D:\论文\.playwright-cli\page-2026-09-20T11-50-09-779Z.yml`（采纳前）
- `D:\论文\.playwright-cli\page-2026-09-20T11-50-36-142Z.yml`（采纳弹窗）
- `D:\论文\.playwright-cli\page-2026-09-20T11-51-45-227Z.yml`（采纳后）
- `D:\论文\.playwright-cli\page-2026-09-20T11-52-12-831Z.yml`（收货页）
- `D:\论文\.playwright-cli\page-2026-09-20T11-52-37-745Z.yml`（预警页）
- `D:\论文\.playwright-cli\page-2026-09-20T12-05-03-334Z.yml`（最终重启后复核交付通知创建时间及申报数量）
- `D:\论文\.playwright-cli\page-2026-09-20T12-05-46-456Z.yml`（最终重启后复核预测拒绝/采纳入口）
- `D:\论文\.playwright-cli\page-2026-09-20T12-14-11-026Z.yml`（最终重启后复核预测运行列表建议量、状态及操作入口）

这些页面快照和日志来自内部运行态检查，不是用户个人 UAT；除后述 MANAGER 情景只读边界外，仍未覆盖 ADMIN / SUPPLIER / MANAGER 全量流程。

采购情景推演补充运行态证据：

- 真实 API 链路使用预测批次 `FC-20260920215947-26C57`（实际模型 `XGBOOST`）模拟 30% 需求增长、5 天供应延迟、10% 安全库存增长、90% 合格率和 10% 价格增长；得到情景 `SCN-20260920215947-AA0F8`、`HIGH` 风险、基准建议量 `0`、模拟建议量 `100`，随后由 BUYER 确认生成 `SCENARIO` 来源草稿 `REQ-20260920215947-1F7B8`。
- 浏览器再次创建只读预览 `SCN-20260920220442-5F363`，展示 14 日逐日库存、基准/模拟差异、金额、风险、指纹和边界说明；未采纳该记录，保留给用户体验。
- BUYER 桌面截图：`D:\论文\docs\assets\screenshots\08-scenario-buyer.png`；MANAGER 只读截图：`09-scenario-manager-readonly.png`；390px 展开结果截图：`10-scenario-mobile.png`。移动端 `documentElement.scrollWidth == clientWidth == 375`。
- MANAGER 读取同一预览时显示“可评估和复核，但不能生成采购需求”，页面不存在确认按钮；后端测试同时验证 MANAGER 采纳为 403、SUPPLIER 模拟为 403。
- V5 已在当前 D 盘 H2 运行库由 Flyway 执行；三服务最终健康检查均为 200。以上仍是演示数据环境和内部验收，不代表真实企业数据、用户 UAT 或 MySQL/Compose 验收。

## 3. 本轮改动与架构决策摘要

- 保留 B/S、Vue 3 + Spring Boot 模块化单体 + FastAPI 的现有边界。Spring Boot 继续作为业务数据库唯一写入方；FastAPI 提供预测/规则解析服务；没有改成微服务，也未增加 MQ。
- 新增 Flyway `V4__decision_loop_and_warning_rules.sql`：为预测建议增加处理状态、版本、决策说明和优化说明；为采购需求增加可唯一追溯的 `forecast_run_id`；为预警增加来源键、条件活跃状态、最近发现和更新时间；把已被计划引用的草稿需求校正为 `PLANNED`，把已有订单的已审批计划校正为 `ORDER_CREATED`；以唯一索引保证一个计划至多一张订单。
- 预测建议写入 `forecast_run` 并由采购人员人工采纳；`POST /api/v1/intelligence/forecast-runs/{id}/adopt` 创建 `FORECAST` 来源的 `DRAFT` 需求，保留预测批次关联，并处理重复请求。预测规则中的库存、安全库存、起订量/包装量、拒收历史和供应商准时率反馈均以系统计算结果及说明保存；本轮没有训练新模型。
- 收货允许分批登记。只有合格数量累计达到订单数量时订单才能成为 `RECEIVED`；不合格数量不增加库存和履约累计；未收齐前不能开始对账。收货须关联已到达的通知。
- 预警使用规则计算和来源键去重；已验证库存不足预警的“人工关闭—条件仍在—条件消失—条件再次出现”生命周期。定时刷新、延期/供应商风险及其它事件预警的完整运行态仍待联调验证。
- 角色边界沿用 ADMIN / BUYER / SUPPLIER / MANAGER：预测采纳/拒绝为 BUYER 专属；管理端维持系统管理边界；订单发运等动作经到货通知工作流处理。非 BUYER 对预测采纳/拒绝的拒绝路径尚未由本轮自动化测试验证。
- 合成历史开关 `SCIC_DEMO_SYNTHETIC_HISTORY_ENABLED` 只控制是否补充合成需求历史；并不清空或关闭 Flyway 种入的其它演示主数据。开发脚本可显式启用，默认配置/Compose 配置关闭。不得将其称作全量空白数据模式。
- AI provider 当前为 `rule`，未配置或调用真实 LLM API；当前预测建议规则反馈也不等于模型训练或机器学习优化。

详细决策编号见 `../00-governance/DECISION_LOG.md`；数据库字段见 `../02-design/DATABASE_DICTIONARY.md`；接口见 `../03-api/API_CONTRACT.md`。

## 4. 本轮后端新增/加强用例映射

| 测试用例 | 对应集成测试序号 / 方法 | 已验证结果 | 尚未覆盖 |
|---|---|---|---|
| TC-FC-006 | #11 `forecastSuggestionCanBeAdoptedOnceAndTracedToDemand` | 自动化验证采纳重放/批次关联及列表建议量；本机真实 HTTP 预测 + BUYER 浏览器采纳后回读到 `DRAFT` 草稿；预测列表 `290` 数量、状态及操作入口显示正确 | 非 BUYER 拒绝路径；用户个人 UAT |
| TC-BIZ-005 | #12 `partialReceiptRequiresQualifiedCompletionBeforeReconciliation` | 首批部分合格/部分拒收后订单为 `PARTIALLY_RECEIVED`；未收齐时对账被拒；后续合格收货完成通知及订单；拒收差异预警存在 | 并发收货、跨数据库行为和完整 UI 收货交互 |
| TC-WARN-001 | #13 `dynamicStockWarningDoesNotReopenUntilConditionRecurs` | 库存不足规则告警可人工关闭；条件不变时不重开，条件解除后再次出现可重开 | 延期/供应商规则、任务调度、完整 UI 处置 |
| TC-BIZ-006 | #14 `onePlanCreatesOnlyOneOrderAndIdempotencyKeyIsLiteral` | 同一计划只生成一个订单；同幂等键重放返回原单，不同键冲突；`%`、`_` 按普通字符处理 | 并发压力和 MySQL 唯一约束运行态复测 |
| TC-BIZ-007 | #3 `wrongRoleAndIllegalTransitionAreBlocked` | BUYER 不能确认供应商订单；直接执行 `SHIP` 返回 `USE_DELIVERY_NOTICE_WORKFLOW` | 其它直接状态动作及浏览器全链路 |
| TC-DEL-001 | #15 `noticeQuantityOverReceiptAndPartialOrderDelayUseRealRemainingQuantity` | 收货量超过到货通知申报量时拒绝且不建收货；到货通知列表返回明细汇总数量；部分收货逾期预警按剩余未履约量说明 | 并发收货及浏览器完整通知/收货操作 |
| TC-WARN-002 | #15 `noticeQuantityOverReceiptAndPartialOrderDelayUseRealRemainingQuantity` | 部分收货的逾期订单风险仍按未履约余量生成，告警原因含剩余数量 | 定时扫描长时间运行与浏览器处置 |
| TC-BIZ-008 | #16 `supplierPerformanceUsesFinalReceiptTimeForSplitDelivery` | 分批交付供应商准时率按最后一批收货时间判断；末批逾期则更新为逾期 | 多订单汇总表现和 MySQL 运行态 |
| TC-SCN-001 / TC-SCN-002 | #17 `procurementScenarioSimulatesWithoutBusinessMutationAndBuyerCanAdoptOnce`；#18 `procurementScenarioPersistsExpiryAndCoversIsolationIdempotencyAndOrderScope` | 固定预测/库存后计算基准与模拟差异；模拟不新增需求；顺序/并发同键回放；采纳生成 `SCENARIO` 草稿；过期状态、版本和 `EXPIRE_PROCUREMENT_SCENARIO` 审计持久化；旧版本、零建议、超长键拒绝；BUYER 隔离；已到货订单和未到货但余量为零的订单不计入延迟影响；MANAGER 可读不可采纳，SUPPLIER 不可模拟 | 并发采纳压力、真实过期 UI 等待体验和 MySQL 运行态 |

其它 #1—#10 覆盖项沿用 `TEST_EXECUTION_2026-08-24.md` 所列的角色、导入、业务闭环、管理员边界、预测物料过滤和解析预览确认测试；本轮自动化总数为 18 项。

## 5. 未完成的验收步骤

1. 扩展当前工作树的浏览器验收，覆盖 ADMIN、SUPPLIER、MANAGER 其它页面，以及 BUYER 对部分收货和预警处理的完整交互；MANAGER 当前只完成情景推演只读边界。
2. 继续检查真实 HTTP 链路的鉴权失败、服务超时/错误处理和预测失败路径；本轮仅记录成功预测路径。
3. 完成个人 UAT；问题按 `UAT-版本-序号` 写入 `docs/00-governance/UAT_FEEDBACK.md`，并关联本文件的 `TC-*` 编号。
4. 条件具备后再做 Docker/MySQL 8 全新库迁移与启动验收；没有实测前维持“未执行”。

本记录不表示当前候选无缺陷，不构成用户 UAT、全角色浏览器验收、最终测试报告或 V1.0 验收结论。

## 6. Sol Max 独立验收遗留 P1

- 预警规则的查询后插入、有效到货通知的检查后插入，以及库存首次建行，在并发请求下仍可能由唯一约束拒绝其中一个事务；数据不会重复，但尚未加入重试或数据库原子 upsert。
- 拒收后的补足继续沿用原 `ARRIVED` 通知；若业务要求供应商重新发运，应扩展为关闭原通知并创建补发通知。
- 运输异常上报/恢复已有后端接口和预警联动，前端尚未提供操作入口。
- 当前工作树尚未完成 ADMIN / SUPPLIER / MANAGER 浏览器回归及全新 MySQL/Compose 复现。
- 当前“全部合格收齐后才对账”的简化口径使对账差异较少触发；如论文要求复杂差异对账，需要单独扩展短收结案/价格或费用差异模型。
- 前端生产构建仍有大 chunk 提示，不阻塞本地个人 UAT，后续可按路由和依赖拆包。

## 7. 采购情景推演最终独立复验

Sol Max 对采购情景推演进行了三轮只读复验。第二轮确认功能性 P0/P1 已全部关闭，仅指出自动化尚未直接证明“过期审计行落库”和“未到货但剩余量为零的订单排除”。随后在 #18 增加两项数据库级断言并重新执行测试：

- `operation_log` 中必须且只能存在一条 `EXPIRE_PROCUREMENT_SCENARIO`，目标为该情景，状态从 `PREVIEW` 变为 `EXPIRED`；
- 构造状态为 `SHIPPED` 但 `quantity - received_qty = 0` 的订单，断言不进入 `affectedOrders`；同时保留 `SHIPPED` 且有余量应进入、`ARRIVED` 应排除的对照。

最终 Spring Boot 结果为 **18/18，0 failures，0 errors，0 skipped**。采购情景功能与其当前自动化验收可判定为 **PASS**；未执行的 MySQL/Compose 运行态复现继续作为环境验证限制单列，不把 H2 的锁行为外推成 MySQL 已验收。
