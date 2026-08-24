# 实验与证据索引（工作版）

文档编号：SCIC-EVID-001  
基线日期：2026-08-24  
当前状态：**UAT 候选，非最终 V1.0**

> 索引只登记可定位的源码、配置、测试和运行记录，不把计划、静态配置或模板冒充为完成证据。候选版浏览器巡检已有证据；Docker、用户个人 UAT 和学校正式 Word/Excel 模板本轮尚未形成最终证据。

## 1. 证据清单

| 编号 | 证据路径 | 类型 | 当前状态/用途 |
|---|---|---|---|
| EV-01 | `PRODUCT.md` | 产品基线 | 已存在；范围、角色和价值约束 |
| EV-02 | `docs/00-governance/ROADMAP_V1.0.md` | 治理路线图 | 已存在；版本门槛、AI/预测边界和材料后置原则 |
| EV-03 | `backend/src/main/resources/db/migration/V1__core_schema.sql` | 数据库源码 | 已存在；表、约束、索引和字段事实 |
| EV-04 | `backend/src/main/resources/db/migration/V2__demo_seed.sql` | 演示数据源码 | 已存在；开发账号、基础数据和演示标签 |
| EV-05 | `backend/src/test/java/com/scic/platform/PlatformIntegrationTest.java`、`backend/target/surefire-reports/TEST-com.scic.platform.PlatformIntegrationTest.xml` | 后端集成测试与执行报告 | 2026-08-25：10 passed，0 failures，0 errors，0 skipped；覆盖登录、供应商隔离、管理员写边界、状态、导入、收货、预测过滤和 AI 补全确认链 |
| EV-06 | `ai-service/tests/` | FastAPI pytest | 2026-08-24：13 passed；覆盖解析、认证、严格 schema、14 日预测和降级契约 |
| EV-07 | `ai-service/examples/` | 接口示例 | 已存在；三类解析和预测请求样例 |
| EV-08 | `samples/import/*.csv` | 导入模板 | 本轮新增；四类演示 CSV，待真实上传 UAT |
| EV-09 | `docs/01-requirements/REQUIREMENTS_V1.0.md` | 需求工作基线 | 本轮新增；UAT 候选，不是学校正式模板 |
| EV-10 | `docs/02-design/ARCHITECTURE_AND_API_V1.0.md` | 架构/接口工作说明 | 本轮新增；按当前代码反向核对 |
| EV-11 | `docs/02-design/DATABASE_DICTIONARY.md` | 数据字典工作说明 | 本轮新增；按 V1 migration 和 CSV 模板整理 |
| EV-12 | `docs/03-api/API_CONTRACT.md` | API 契约索引 | 本轮新增；Spring/FastAPI 路由、认证和错误口径 |
| EV-13 | `docs/04-testing/TEST_PLAN_AND_CASES.md` | 测试计划/用例 | 本轮新增；执行状态分开标注，未编造 UAT |
| EV-14 | `docs/08-operations/LOCAL_RUNBOOK.md` | 运维手册 | 本轮新增；本地脚本和 D 盘约束 |
| EV-15 | `docs/08-operations/USER_MANUAL_DRAFT.md` | 用户手册草案 | 本轮新增；UAT 候选、模板未填 |
| EV-16 | `scripts/start-dev.ps1`、`stop-dev.ps1`、`verify.ps1` | 运行/静态验证脚本 | 本轮新增；进程隐藏、PID、路径和配置检查 |
| EV-17 | `.env.example`、`docker-compose.yml`、各 Dockerfile、`frontend/nginx.conf` | 部署配置 | 本轮新增；仅静态验收，Docker 未运行 |
| EV-18 | `.data/logs`、`.data/run` | 本地运行记录位置 | 目录可由脚本创建；真实启动日志待 UAT 留存 |
| EV-19 | `docs/04-testing/TEST_EXECUTION_2026-08-24.md` | 测试执行记录 | 2026-08-25 复跑：后端 10、AI 13、前端生产构建、静态检查、BCL-01 后半 API、10 并发 200 请求 P95 88.81 ms、四角色候选版浏览器巡检；Docker 未安装 |
| EV-20 | `docs/05-experiments/FORECAST_RUN_2026-08-24.md` | 预测实验摘要 | `DEMO_SYNTHETIC`；XGBoost MAE 1.443933、RMSE 3.214012、MAPE 3.165298%、3 折、14 点；明确非生产 |
| EV-21 | `docs/05-experiments/AI_PARSE_SMOKE_2026-08-24.md` | AI 解析烟测 | `rule` provider 本地烟测；未配置或调用外部 OpenAI-compatible LLM，需人工确认 |
| EV-22 | `docs/assets/screenshots/README.md`、`docs/assets/screenshots/*.png` | 浏览器候选版证据 | 7 张真实页面截图；四角色、看板、预测逐日结果、AI 解析预览、供应商订单和管理员用户页；全新登录页控制台 0 errors / 0 warnings |
| EV-23 | `docs/04-testing/SOL_FINAL_REVIEW_2026-08-25.md` | 独立最终验收 | RC2 结论为 `PASS WITH NON-BLOCKING LIMITATIONS`，阻断项为 0；允许开始个人 UAT，明确保留本机安全、Docker/MySQL、外部 LLM、生产数据和 V1.0 边界 |

## 2. 尚缺证据

- 用户个人 UAT 反馈、复现请求号和修改后回归记录；候选版四角色基础巡检截图已完成；
- 干净环境启动、MySQL 初始化、Docker Compose 健康检查和前端代理实测；
- 预测原始输入、数据哈希、逐折明细和完整可复现实验日志（当前仅有 `DEMO_SYNTHETIC` 指标摘要）；
- 60 条以上 AI Ground Truth、Prompt 对比、错误分类和人工确认统计；
- 学校提供的 Word/Excel 正式模板填写件。

## 3. 证据使用规则

论文或答辩只引用已存在且可复核的证据；源码存在不等于运行通过，静态配置不等于部署成功，演示 CSV 不等于真实企业数据。每次 UAT 运行应登记日期、版本、数据标签、账号角色、请求号、日志路径和结果。当前仓库只能称为 UAT 候选，不能写最终完成或无缺陷。
