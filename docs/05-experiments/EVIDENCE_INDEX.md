# 实验与证据索引（工作版）

文档编号：SCIC-EVID-001  
基线日期：2026-08-24  
当前状态：**UAT 候选，非最终 V1.0**

> 索引只登记可定位的源码、配置、测试和运行记录，不把计划、静态配置或模板冒充为完成证据。当前工作树浏览器巡检和便携 MySQL 验收已有证据；Docker Compose、用户个人 UAT 和学校正式 Word/Excel 模板本轮尚未形成最终证据。

## 1. 证据清单

| 编号 | 证据路径 | 类型 | 当前状态/用途 |
|---|---|---|---|
| EV-01 | `PRODUCT.md` | 产品基线 | 已存在；范围、角色和价值约束 |
| EV-02 | `docs/00-governance/ROADMAP_V1.0.md` | 治理路线图 | 已存在；版本门槛、AI/预测边界和材料后置原则 |
| EV-03 | `backend/src/main/resources/db/migration/V1__core_schema.sql` | 数据库源码 | 已存在；表、约束、索引和字段事实 |
| EV-04 | `backend/src/main/resources/db/migration/V2__demo_seed.sql` | 演示数据源码 | 已存在；开发账号、基础数据和演示标签 |
| EV-05 | `backend/src/test/java/com/scic/platform/PlatformIntegrationTest.java`、`backend/src/test/java/com/scic/platform/intelligence/AiFailureIntegrationTest.java`、`backend/target/surefire-reports/TEST-*.xml` | 后端集成测试与执行报告 | 2026-09-21：54 passed，0 failures，0 errors，0 skipped；19 项业务集成含账号停用令牌撤销，35 项覆盖 AI 失败/超时边界 |
| EV-06 | `ai-service/tests/` | FastAPI pytest | 2026-09-21：19 passed；覆盖解析、认证、严格 schema、本地 provider、证据校正、14 日预测和降级契约 |
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
| EV-22 | `docs/assets/screenshots/README.md`、`docs/assets/screenshots/*.png` | 浏览器候选版证据 | UI-01—UI-17；RC2 历史证据、采购情景、本地模型、离线字体及四角色当前工作树巡检；对应复核控制台 0 errors / 0 warnings |
| EV-23 | `docs/04-testing/SOL_FINAL_REVIEW_2026-08-25.md` | 独立最终验收 | RC2 结论为 `PASS WITH NON-BLOCKING LIMITATIONS`，阻断项为 0；允许开始个人 UAT，明确保留本机安全、Docker/MySQL、外部 LLM、生产数据和 V1.0 边界 |
| EV-24 | `docs/04-testing/TEST_EXECUTION_2026-09-21.md` | 当前工作树最终复跑 | 后端 54、AI 19、MySQL 164/164、前端构建和静态/运行检查通过；明确 Compose/UAT 边界 |
| EV-25 | `docs/05-experiments/AI_PARSE_LOCAL_EVAL_2026-09-21.md` 及两个 JSON | 本地 LLM 实验 | 固定 12 条混合解析链从 7/12 修复为 12/12，保留原始逐条结果与耗时 |
| EV-26 | `docs/assets/screenshots/11-ai-local-llm-preview.png`、`12-noto-sans-sc-materials.png` | 浏览器证据 | 本地 Qwen 真实预览、provider/model/prompt/字段来源，以及离线 Noto Sans SC 实际渲染 |
| EV-27 | `docs/00-governance/TASK_BOOK_REQUIREMENTS_MAPPING.md` | 任务书对照 | 任务书原件与当前实现逐项映射，未完成项单独标识 |
| EV-28 | `scripts/verify_mysql_uat.py`、`output/acceptance/mysql-uat-202609210244488FAB74.json` | 真实 MySQL 独立验收 | MySQL 8.4.11 干净库 V1—V5；164/164，覆盖四角色 RBAC、完整闭环、幂等、隔离、审计、停用账号令牌撤销和原始数据保全；报告不含凭据/JWT |
| EV-29 | `docs/assets/screenshots/18-buyer-final-dashboard.png` | 最终浏览器复核 | BUYER 总览真实后端模式，控制台 0 errors / 0 warnings；内部巡检，不替代用户 UAT |

## 2. 尚缺证据

- 用户个人 UAT 反馈、复现请求号和修改后回归记录；候选版四角色基础巡检截图已完成；
- Docker Compose 干净环境启动、容器健康检查和 Nginx 前端代理实测；便携 MySQL 初始化与业务验收已经完成；
- 预测原始输入、数据哈希、逐折明细和完整可复现实验日志（当前仅有 `DEMO_SYNTHETIC` 指标摘要）；
- 60 条以上 AI Ground Truth、Prompt 对比和人工确认统计；当前仅有 12 条固定回归集及首次/修复后错误分类；
- 学校提供的 Word/Excel 正式模板填写件。

## 3. 证据使用规则

论文或答辩只引用已存在且可复核的证据；源码存在不等于运行通过，静态配置不等于部署成功，演示 CSV 不等于真实企业数据。每次 UAT 运行应登记日期、版本、数据标签、账号角色、请求号、日志路径和结果。当前仓库只能称为 UAT 候选，不能写最终完成或无缺陷。
