# 企业供应链数智化协同平台

项目题目：企业供应链数智化协同平台设计与开发  
项目代号：`scic-platform`  
当前版本：`0.9.0-UAT-RC2`  
当前状态：可运行的个人验收候选，**不是最终 V1.0**  
当前工作树：基于 RC2 的 2026-09-21 未发布迭代；预测采纳、采购情景推演和本地大模型解析链已验
基线日期：2026-08-24

这是一个面向采购企业与供应商协同的毕业设计工程。系统以“数据导入—14 日需求预测—采购需求—计划审批—订单履约—到货—收货入库—对账完成”为主闭环，并提供受控的自然语言解析、风险预警、角色化工作台和审计留痕。

## 当前实现

- Vue 3 + TypeScript 前端：采购、供应商、管理者、管理员四类工作台；真实接口模式默认关闭演示回退。
- Spring Boot 业务服务：JWT、四角色权限、主数据、CSV 导入、预测记录、采购与协同状态机、收货事务、对账、预警、审计和 OpenAPI。
- FastAPI 智能服务：本地 Qwen2.5-1.5B 三类白名单文本解析，失败时显式规则降级；14 日 MA7 基线与具备资格时的 XGBoost 选择；业务库不向 AI 服务开放。
- 采购情景推演：基于已保存预测、库存、在途、提前期和物料采购约束，比较需求/交期/质量/价格变化前后的逐日库存、风险、建议量与金额；预览两小时有效，只有 BUYER 人工确认后才生成 `SCENARIO` 来源需求草稿。
- Flyway 数据库迁移：本地默认 H2 文件库，Compose 候选使用 MySQL 8。
- 工程证据：需求、设计、API、测试、实验、论文映射、运行手册、UAT 台账和源材料留档。

本轮自动化与构建检查已通过：Spring Boot 共 54 项（业务集成 19 项、AI 故障集成 35 项）、FastAPI 19 项、真实 MySQL 8.4.11 独立验收 164/164、前端 `vue-tsc` 与生产构建、静态与运行健康检查。本地 Qwen2.5 混合解析链固定样本回归 12/12，并已完成四角色当前工作树浏览器巡检；预测采纳、采购情景推演、`SCENARIO` 来源需求生成和账号停用后旧 JWT 立即失效均已验证。中文界面统一使用离线打包的 Noto Sans SC。用户个人 UAT 与 Docker Compose 容器启动仍未完成；执行明细见 `docs/04-testing/TEST_EXECUTION_2026-09-21.md`。

## 本地启动

项目、依赖缓存、数据库、日志和截图都放在 `D:\论文`。在 PowerShell 中执行：

```powershell
Set-Location D:\论文
.\scripts\install-local-llm-runtime.ps1
.\scripts\start-dev.ps1 -SkipFrontendInstall
.\scripts\verify.ps1
```

打开 `http://127.0.0.1:5173`。本地演示账号为 `admin`、`buyer`、`supplier`、`manager`，密码均为 `123456`；仅用于本机候选版本。停止服务：

```powershell
.\scripts\stop-dev.ps1
```

模型只需首次安装一次；文件和运行时均保存在项目 D 盘。详细环境、故障排查和 Compose 边界见 `docs/08-operations/LOCAL_RUNBOOK.md`。

## D 盘约束

- 根目录：`D:\论文`
- 依赖缓存：`D:\论文\.cache`（不提交）
- H2/MySQL、日志和进程清单：`D:\论文\.data`（不提交）
- 项目截图与书面证据：`D:\论文\docs`
- C 盘只保留操作系统、已有开发工具、Codex 自带组件和用户附件临时文件；本项目未额外安装推荐插件，也不主动把大型项目内容放入 C 盘。

## 交接阅读顺序

1. `docs/00-governance/CURRENT_STATUS.md`
2. `PRODUCT.md` 与 `DESIGN.md`
3. `docs/01-requirements/REQUIREMENTS_V1.0.md`
4. `docs/02-design/ARCHITECTURE_AND_API_V1.0.md`
5. `docs/03-api/API_CONTRACT.md`
6. `docs/04-testing/TEST_PLAN_AND_CASES.md` 与本轮执行记录
7. `docs/00-governance/TRACEABILITY_MATRIX.md`
8. `docs/00-governance/UAT_FEEDBACK.md`
9. `docs/00-governance/PROJECT_JOURNAL.md`

聊天记录不能替代仓库事实。每次用户测试后的修改都要登记 UAT 编号、复现、改动、回归和文档影响；没有通过个人 UAT、Docker Compose 干净环境复现和最终发布复核前，不得标记 V1.0。
