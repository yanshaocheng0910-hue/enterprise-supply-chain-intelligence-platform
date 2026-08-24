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
- Docker：本机不可用，本轮只做 Compose/Dockerfile/Nginx 静态检查。

## 3. 用例目录

| 编号 | 场景 | 预期 | 当前状态 |
|---|---|---|---|
| TC-AI-001 | `GET /health` 无 token | 200，返回服务状态和实际 provider | FastAPI 测试已覆盖 |
| TC-AI-002 | parse 缺 token/错 token | 401，不泄漏原文或密钥 | FastAPI 测试已覆盖 |
| TC-AI-003 | parse 未知字段/空文本 | 422，严格 Pydantic schema | FastAPI 测试已覆盖 |
| TC-AI-004 | 三类白名单解析 | intent、fields、缺失字段、warnings、provider、确认标记完整 | FastAPI 测试已覆盖 |
| TC-AI-005 | 未配置 LLM | provider 为 `rule`，不伪装大模型 | FastAPI 测试已覆盖 |
| TC-AI-006 | parse confidence | 含“仅供参考”说明，不触发自动业务动作 | FastAPI 契约已覆盖；业务 UAT 待执行 |
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
| TC-OPS-001 | 启动脚本 | 进程隐藏启动，PID 清单和日志在 D 盘 | 脚本静态检查 |
| TC-OPS-002 | 停止脚本 | 只按本次 manifest 停止，不删除业务数据 | 脚本静态检查 |
| TC-OPS-003 | Compose 配置 | 服务、变量、健康检查、代理路径可解析 | 静态检查；未运行 Docker |

## 4. 预测与 AI 专项口径

预测测试必须保存输入快照、数据哈希、切分比例、三个 14 日扩展窗逐折指标、最终测试指标、模型选择理由和后处理说明。MAE 为绝对误差均值，RMSE 为平方误差均值开方，MAPE 仅统计真实值大于 0 的样本。模型是否“更好”只按验证 MAE 和至少 2/3 折胜出规则判断，不凭单次测试结果切换模型。

AI 测试集必须区分原文、白名单意图、字段真值、缺失字段和业务校验结果。`confidence` 未校准，仅作提示；任何确认接口仍需人工确认和后端事实校验。Provider 失败或未配置时，应保留原文并可回退结构化表单/规则解析。

## 5. 当前执行记录

| 检查 | 结果 | 证据 |
|---|---|---|
| FastAPI 基础测试 | 本轮已在 `ai-service` 工作目录执行通过（13 passed，含无 XGBoost 路径） | `ai-service/tests`、本轮 pytest 输出；应从 `D:\论文\ai-service` 执行 |
| Spring Boot 集成测试 | 2026-08-25 复跑 10 passed，0 failures，0 errors，0 skipped | `backend/target/surefire-reports/TEST-com.scic.platform.PlatformIntegrationTest.xml`；执行明细见 `TEST_EXECUTION_2026-08-24.md` |
| 前端浏览器/UAT | 四角色候选版巡检通过；用户个人 UAT 尚未开始 | `docs/assets/screenshots/README.md`、`TEST_EXECUTION_2026-08-24.md`；不能写成用户 UAT 已通过 |
| Docker Compose | 未执行（本机 `docker` 命令不存在） | 仅 `scripts/verify.ps1 -StaticOnly` 和 YAML/Dockerfile/Nginx 静态检查 |
| 路径/脚本/CSV | 静态检查通过 | `scripts/verify.ps1 -StaticOnly` |

任何“通过”只适用于上表明确的测试范围。当前项目状态仍为 UAT 候选，不得据此写最终 V1.0 或宣称无缺陷。
