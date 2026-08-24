# Sol Max 独立最终验收报告——0.9.0-UAT-RC2

验收日期：2026-08-25  
验收方式：只读源码、文档、原图、既有测试报告及本机运行态复核  
复验基线：`b004b3695ea854767e193d676593ca63647cf222`  
提交链：`f1e1719`（RC2 主候选）→ `27cd434`（更新 UI-07）→ `b004b36`（校正 README 测试计数）

## 一、最终结论

**PASS WITH NON-BLOCKING LIMITATIONS**

未发现阻断用户个人 UAT 的权限、数据隔离、业务状态、预测或 AI 人工确认缺陷。该版本可以作为 **0.9.0-UAT-RC2 个人 UAT 候选**交付。

本结论不表示 Docker/MySQL、用户个人 UAT、外部 LLM、企业生产数据或最终 V1.0 已通过。

## 二、阻断项

**无。**

首轮六项阻断均已复核关闭：

- 供应商访问主数据、全局预警和数据来源接口均受限；供应商看板全局预警指标及列表清零。
- 管理员不能处理业务预警；管理者可读/审批但不能创建采购需求。
- 预测页使用真实物料，服务端按 `materialCode` 精确过滤，重算编码一致。
- AI 补全后重新计算 `schema_valid`、`business_valid`，只有人工确认才写入业务对象。
- UI-03、UI-05、UI-07 已用 RC2 运行态重新取得并逐图检查。
- EV-04 已指向正确的 `V2__demo_seed.sql`。

关键源码证据包括：

- `backend/src/main/java/com/scic/platform/masterdata/MasterDataController.java`
- `backend/src/main/java/com/scic/platform/system/SystemController.java`
- `backend/src/main/java/com/scic/platform/dashboard/DashboardController.java`
- `backend/src/main/java/com/scic/platform/intelligence/IntelligenceService.java`
- `frontend/src/views/ForecastView.vue`
- `frontend/src/views/DashboardView.vue`

## 三、实际复核结果

| 项目 | 实际结果 |
|---|---|
| Git 基线 | `git rev-parse HEAD` 为 `b004b369…`；基线检查时工作区干净；`git fsck` 无异常输出 |
| Spring Boot | 解析 Surefire 原始 XML：10 tests、0 failures、0 errors、0 skipped，耗时 17.748 秒；测试源码覆盖隔离、管理员边界、预测过滤、AI 补全确认和完整 BCL |
| FastAPI | 只读方式实跑 pytest：`13 passed`；仅有依赖弃用警告 |
| Vue | `vue-tsc --noEmit` 返回 0；既有生产构建产物及执行记录存在 |
| 静态检查 | `scripts/verify.ps1 -StaticOnly`：通过 |
| 三服务健康 | FastAPI、Spring Boot、Vue 均返回 HTTP 200 |
| 供应商隔离 | 供应商访问 suppliers、materials、warehouses、inventory、warnings、data-provenance 六个全局接口均为 403 |
| 供应商看板 | `openWarnings=0`、`highWarnings=0`，预警分布和库存风险列表均为空 |
| 管理员边界 | 管理员处理预警请求为 403 |
| 预测契约 | `MAT-BOX-05` 过滤结果只包含该物料；明细为固定 14 点，保留 `DEMO_SYNTHETIC` |
| AI 边界 | 补全后 `schemaValid=true`、`businessValid=true`；人工确认后状态为 `CONFIRMED`，目标为采购需求；确认前不写业务事实 |
| 业务状态链 | 测试覆盖确认、备货、通知提交、发运、到达、收货、对账确认与完成，并覆盖非法状态及幂等边界 |
| 截图 | 7 张原图全部检查；UI-03 编码一致，UI-05 为绑定供应商订单，UI-07 顶部仅有“刷新数据” |
| D 盘约束 | 数据库、日志、PID 清单、Maven/npm/pip 缓存均定位于项目 D 盘目录；启动脚本使用隐藏窗口，停止脚本按清单终止进程树并保留数据与日志 |
| 秘密扫描 | 实际 `.env` 文件 0；跟踪的私钥/密钥类文件 0；常见私钥、OpenAI、GitHub、AWS、Google 密钥模式命中 0 |

`README.md`、`CURRENT_STATUS.md`、测试执行记录、追踪矩阵及证据索引的核心事实已与 RC2 代码和证据对齐。

## 四、非阻断限制

- Spring Boot 当前实际监听 `[::]:8080`，而项目包含公开演示账号及默认开发密钥。候选版只适用于隔离本机个人 UAT；不得直接用于共享网络或生产环境。建议后续显式绑定 `127.0.0.1` 或配置等效防火墙限制。
- FastAPI 测试出现 Starlette/httpx 及 Python 未来版本相关弃用警告，目前不影响功能，后续升级依赖时需处理。
- Docker Compose、全新 MySQL 8 迁移、外部 LLM、真实企业数据和用户个人 UAT 均尚未完成。
- 报告归档与项目日志语态修正是复验基线后的文档专用提交；UAT 标签应指向该最终归档提交。

## 五、交付建议

可以交给用户开始个人 UAT。交付时应：

1. 固定并记录最终提交哈希及 `0.9.0-UAT-RC2` 标签。
2. 保持 `DEMO_SYNTHETIC`、`rule` provider 和“需要人工确认”等界面标签。
3. 按 `UAT_FEEDBACK.md` 登记每条用户反馈、复现步骤、修复提交及回归结果。
4. 在用户 UAT、Docker/MySQL 干净环境复现及其他发布门槛完成前，不得升级或描述为 V1.0。
