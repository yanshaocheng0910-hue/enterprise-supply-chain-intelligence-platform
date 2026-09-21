# 测试计划与用例（工作版）

文档编号：SCIC-TEST-001  
基线日期：2026-08-24  
当前状态：**UAT 候选，非最终 V1.0**

> 本文记录可执行的测试范围和当前证据状态，不虚构浏览器、Docker 或生产数据结果。学校正式测试报告/Excel 模板本轮未填写，仅保留项目内工作证据；最终测试报告须在 UAT-R1 和回归冻结后生成。

## 1. 测试目标

验证 BCL-01 业务闭环的接口、权限、状态、数据一致性和降级路径；验证 FastAPI 解析/预测契约在无 XGBoost、无 LLM provider 时仍可复现；验证运行脚本和配置不会把缓存、日志、数据库文件写到 C 盘。测试覆盖单元、接口、集成、权限/安全、数据导入、状态机、AI、预测和运维静态检查。

## 2. 环境与数据

- 项目根：`D:\论文`。
- FastAPI：`ai-service\.venv`；基础依赖不要求 XGBoost。XGBoost 是候选依赖，安装后仍须通过模型选择门槛。
- Spring Boot：Java 17、Maven；默认 H2 文件或 MySQL profile。
- 前端：Node/npm；Vite 开发端口 5173。
- 示例 CSV：`samples/import`，数据标签为演示模板，不是真实企业生产数据。
- 数据库：默认 H2 文件库；便携 MySQL 8.4.11 使用隔离端口 3307 和干净验收库；Docker 本机不可用，Compose/Dockerfile/Nginx 只做静态检查。

## 3. 用例目录

| 编号 | 场景 | 预期 | 当前状态 |
|---|---|---|---|
| TC-AI-001 | `GET /health` 无 token | 200，返回服务状态和实际 provider | FastAPI 测试已覆盖 |
| TC-AI-002 | parse 缺 token/错 token | 401，不泄漏原文或密钥 | FastAPI 测试已覆盖 |
| TC-AI-003 | parse 未知字段/空文本 | 422，严格 Pydantic schema | FastAPI 测试已覆盖 |
| TC-AI-004 | 三类白名单解析 | intent、fields、缺失字段、warnings、provider、确认标记完整 | FastAPI 测试已覆盖 |
| TC-AI-005 | 未配置 LLM | provider 为 `rule`，不伪装大模型 | FastAPI 测试已覆盖 |
| TC-AI-006 | parse confidence | 含“仅供参考”说明，不触发自动业务动作 | FastAPI 契约已覆盖；业务 UAT 待执行 |
| TC-AI-007 | 本地经营分析事实边界 | Qwen 只排序闭集章节；数值、风险事实与行动由规则生成；非法模型输出降级，不写业务单据 | FastAPI 规则/远程拒绝/本地闭集测试与真实 Qwen HTTP 通过 |
| TC-AI-008 | 经营报告快照与权限 | 同幂等键只保存一份；快照、64 位指纹和审计可追溯；SUPPLIER 不可读取 | Spring Boot 集成测试 #20 通过；BUYER 真实浏览器通过 |
| TC-WQ-001 | 角色待办只读投影 | BUYER/MANAGER/SUPPLIER 仅看到职责及供应商范围内事项；ADMIN 403；读取不写操作日志或业务状态 | Spring Boot 集成测试 #21、BUYER 桌面/390px 浏览器通过 |
| TC-BIZ-009 | 订单履约证据链 | 从订单生成到对账完成聚合业务记录、审计日志和库存流水；供应商隔离、ADMIN 拒绝；读取不新增审计或改变状态 | Spring Boot 集成测试 #22；真实闭环订单 17 条事件；桌面/390px 浏览器通过 |
| TC-FC-001 | history 递归预测 | 固定 14 个连续日期，forecast 非负 | FastAPI 测试已覆盖 |
| TC-FC-002 | MA7 降级 | 样本不足/无 XGBoost 时返回 `model=ma7` 和原因 | FastAPI 测试已覆盖 |
| TC-FC-003 | XGBoost 选择 | 仅当平均验证 MAE 低于 MA7 且 3 折至少赢 2 折时选用 | `DEMO_SYNTHETIC` 长序列实测选择 XGBoost，3/3 折胜；仍需用户 UAT |
| TC-FC-004 | 指标口径 | MAE、RMSE、MAPE（零真实值不入分母）明确返回 | FastAPI 测试已覆盖 |
| TC-FC-005 | 预测提前期 | `lead_time>14` 返回 `HORIZON_INSUFFICIENT`，不产生建议量 | FastAPI 测试已覆盖 |
| TC-SEC-001 | 四角色登录 | 合法账号得 JWT，非法密码拒绝 | 后端集成测试与四角色候选版浏览器巡检通过 |
| TC-SEC-002 | 供应商隔离 | supplier 只能读写自身订单/通知 | 后端集成测试通过；供应商订单页候选版巡检通过；用户 UAT 待执行 |
| TC-SEC-003 | 权限/状态动作 | 错误角色或非法状态返回业务错误，不写入 | 后端集成测试源覆盖；待全链路回归 |
| TC-IMP-001 | 四类 CSV 预览 | 文件类型、字段、引用和数量错误可定位到行 | 后端实现/迁移可核对；待实际上传 UAT |
| TC-IMP-002 | CSV commit 幂等 | 相同 hash/idempotency 不重复写业务数据 | 迁移约束存在；待接口执行 |
| TC-BIZ-001 | 需求→计划→订单 | 只有审批计划可以生成订单 | 后端测试源/状态服务；待完整跨角色 UAT |
| TC-BIZ-002 | 到货前收货 | 前置状态不满足时拒绝且库存不变 | `PlatformIntegrationTest` 源覆盖 |
| TC-BIZ-003 | 收货事务 | 收货、库存和流水同事务成功或整体回滚 | 设计/代码路径已具备；待故障注入 |
| TC-BIZ-004 | 对账差异 | 数量/金额差异进入争议和可审计处理 | 后端接口/表已具备；待 UAT |
| TC-FC-006 | 预测采购建议采纳 | BUYER 对有数量的待处理建议确认后只创建一条 `FORECAST` 来源草稿；重复采纳返回同一需求，`forecast_run_id` 可追溯；历史列表继续显示建议数量和已采纳状态 | 后端集成测试 #11、真实 HTTP 预测和最终 BUYER 浏览器采纳/需求回读通过；列表两行显示 290、状态和待决策入口正确；非 BUYER 拒绝路径与用户 UAT 待验 |
| TC-BIZ-005 | 部分收货与拒收 | 只允许关联已到达通知；合格数量累计更新订单与库存，拒收数量不计入履约；全部合格收齐前禁止对账 | 后端集成测试 #12 通过；当前浏览器收货页仅页面级复核，完整 UI 写操作待验 |
| TC-WARN-001 | 库存预警条件生命周期 | 规则预警按来源键去重；人工关闭后条件未消失时保持关闭，条件清除后再次出现可重新打开 | 后端集成测试 #13 通过；当前浏览器预警页仅页面级复核，完整 UI 处置待验 |
| TC-BIZ-006 | 计划生成订单幂等 | 同一计划最多生成一个订单；同一键重放返回既有订单，其他键冲突；幂等键中的 `%`、`_` 按普通字符处理 | 后端集成测试 #14 通过；并发数据库压力验证待做 |
| TC-BIZ-007 | 发运状态只能经通知工作流 | 直接对订单执行 `SHIP` 被拒绝并提示使用到货通知工作流 | 后端集成测试 #3 通过；其他直接动作及浏览器流程待验 |
| TC-SEC-004 | 预测建议角色边界 | 仅 BUYER 可采纳或拒绝建议；其他角色只能按授权范围查看，不得写入 | Controller 已声明 BUYER 权限；非 BUYER 拒绝路径尚未在本轮自动化测试验证，列入用户 UAT |
| TC-SEC-005 | 停用账号令牌撤销 | ADMIN 停用账号后，该账号既不能重新登录，已签发 JWT 也必须在下一次请求返回 401；角色/供应商绑定变化采用数据库当前值 | H2 集成测试 #19 与真实 MySQL `AUTH-REVOKED-TOKEN` 通过 |
| TC-OPS-004 | 合成历史数据开关范围 | `SCIC_DEMO_SYNTHETIC_HISTORY_ENABLED` 仅控制初始化器补充合成需求历史；不代表关闭系统其他种子演示数据 | 已核对实现与配置，自动化启动矩阵待补 |
| TC-DEL-001 | 到货通知申报数量上限与列表汇总 | 实收不能超过本次到货通知申报数量；列表展示数据库汇总申报量和创建时间 | 后端集成测试 #15 通过；BUYER 浏览器通知列表已复核数量 `600` 与创建时间 |
| TC-WARN-002 | 部分收货逾期剩余风险 | 部分履约订单已逾期时，预警说明剩余未履约量，不把已收部分计为未履约 | 后端集成测试 #15 通过；定时扫描长时间运行和用户 UAT 待验 |
| TC-BIZ-008 | 分批到货的供应商准时率 | 供应商绩效按最后一批合格收货时间判断订单是否准时 | 后端集成测试 #16 通过；多订单汇总表现待用户 UAT |
| TC-SCN-001 | 采购情景只读模拟 | BUYER 使用已保存预测、库存和在途事实模拟需求/交期/质量/价格变化；返回冻结的基准与模拟结果、逐日投影、风险和数据指纹；模拟前后业务需求数量不变；同幂等键顺序/并发重放不重复建记录 | 后端集成测试 #17/#18、真实 H2 API 和 BUYER 桌面/390px 浏览器操作通过 |
| TC-SCN-002 | 情景采纳与角色边界 | 仅 BUYER 可在有效期内按版本确认建议量并生成一条 `SCENARIO` 来源草稿；重复采纳返回原需求；MANAGER 只读且不可采纳，SUPPLIER 不可模拟；过期状态持久化，旧版本/零建议/超长幂等键被拒，BUYER 记录隔离 | 后端集成测试 #17/#18 通过；MANAGER 浏览器只读提示及无采纳入口已验证；MySQL 已验证 V5 迁移兼容但未单列情景接口，用户 UAT 待验 |
| TC-OPS-001 | 启动脚本 | 进程隐藏启动，PID 清单和日志在 D 盘 | 脚本静态检查 |
| TC-OPS-002 | 停止脚本 | 只按本次 manifest 停止，不删除业务数据 | 脚本静态检查 |
| TC-OPS-003 | Compose 配置 | 服务、变量、健康检查、代理路径可解析 | 静态检查；未运行 Docker |
| TC-DB-001 | 真实 MySQL 干净库迁移与完整闭环 | 空库完成 V1—V5；四角色权限、业务状态、幂等、隔离、审计和原数据保全全部通过 | 2026-09-21 便携 MySQL 8.4.11，164/164；报告位于 `output/acceptance/` |

## 4. 预测与 AI 专项口径

预测测试必须保存输入快照、数据哈希、切分比例、三个 14 日扩展窗逐折指标、最终测试指标、模型选择理由和后处理说明。MAE 为绝对误差均值，RMSE 为平方误差均值开方，MAPE 仅统计真实值大于 0 的样本。模型是否“更好”只按验证 MAE 和至少 2/3 折胜出规则判断，不凭单次测试结果切换模型。

AI 测试集必须区分原文、白名单意图、字段真值、缺失字段和业务校验结果。`confidence` 未校准，仅作提示；任何确认接口仍需人工确认和后端事实校验。Provider 失败或未配置时，应保留原文并可回退结构化表单/规则解析。

## 5. 当前执行记录

| 检查 | 结果 | 证据 |
|---|---|---|
| FastAPI 基础测试 | 2026-09-21：23 passed | `ai-service/tests`、本轮 pytest 输出；应从 `D:\论文\ai-service` 执行 |
| Spring Boot 集成测试 | 2026-09-21：57 passed，0 failures，0 errors，0 skipped；业务集成 22 项、AI 故障集成 35 项 | `backend/target/surefire-reports/TEST-*.xml`；执行明细见 `TEST_EXECUTION_2026-09-21.md` |
| 真实 MySQL 8.4.11 | 164/164；干净库迁移与完整业务闭环、RBAC、幂等、范围隔离、审计、令牌撤销及数据保全通过 | `output/acceptance/mysql-uat-202609210244488FAB74.json` |
| 当前工作树服务健康检查 | 本地 AI、Backend、Frontend 三服务均健康 | 本轮运行态检查；临时日志索引见 `TEST_EXECUTION_2026-09-20.md` |
| 当前工作树浏览器检查 | ADMIN、SUPPLIER、MANAGER、BUYER 及 390px BUYER 页面已复核；最终 BUYER 总览控制台 0 errors / 0 warnings | `docs/assets/screenshots/13-admin-current.png`—`18-buyer-final-dashboard.png` |
| 用户个人 UAT | 尚未开始 | `docs/00-governance/UAT_FEEDBACK.md`；内部浏览器检查不可代替用户验收 |
| Spring Boot → FastAPI HTTP | 成功路径通过；35 项 AI 故障集成测试覆盖鉴权、超时/错误和受控失败边界 | `TEST_EXECUTION_2026-09-21.md`、`AiFailureIntegrationTest` |
| Docker Compose | 未执行（本机 `docker` 命令不存在） | 仅 `scripts/verify.ps1 -StaticOnly` 和 YAML/Dockerfile/Nginx 静态检查 |
| 路径/脚本/CSV | 静态检查通过 | `scripts/verify.ps1 -StaticOnly` |
| 前端类型检查与生产构建 | `vue-tsc` 与生产构建通过 | 本轮前端验证输出；详见 `TEST_EXECUTION_2026-09-20.md` |

任何“通过”只适用于上表明确的测试范围。当前项目状态仍为 UAT 候选，不得据此写最终 V1.0 或宣称无缺陷。
