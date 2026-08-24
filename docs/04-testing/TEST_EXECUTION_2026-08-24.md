# 测试执行记录（2026-08-24）

文档编号：SCIC-TEST-EXEC-20260824  
执行日期：2026-08-24（Asia/Shanghai）  
测试版本：`0.9.0-UAT-RC2`  
当前状态：**V0.9 UAT 候选，非最终 V1.0**

> 本记录只登记本轮已经执行并可定位的结果。浏览器候选版巡检已完成；用户个人 UAT 和 Docker/MySQL 干净环境复现没有在本记录中虚构为通过。

## 1. 执行摘要

| 执行项 | 结果 | 范围与证据 |
|---|---|---|
| Spring Boot 后端集成测试 | **10 passed，0 failures，0 errors，0 skipped** | `backend/target/surefire-reports/TEST-com.scic.platform.PlatformIntegrationTest.xml` |
| FastAPI AI 测试 | **13 passed** | `ai-service/tests/`；在 `D:\论文\ai-service` 执行 `.\.venv\Scripts\python.exe -m pytest -q` |
| 项目静态检查 | **通过** | `scripts/verify.ps1 -StaticOnly`；包含必需路径、PowerShell 语法、D 盘路径约束、CSV 表头和部署静态文件检查 |
| 本地三服务健康检查 | **通过** | `scripts/verify.ps1`；AI、Backend、Frontend 均返回 HTTP 200 |
| BCL-01 后半闭环 API 实测 | **真实通过** | 供应商确认→备货→到货通知→发运→到达→收货入库→对账确认→完成；订单终态 `COMPLETED` |
| 非 AI 看板接口并发检查 | **通过** | 10 并发、200 次请求；无非 2xx/5xx；P95 `88.81 ms`；当前本机 H2 环境 |
| 浏览器/页面巡检 | **通过（候选版巡检）** | 四角色真实登录与授权页面、采购看板、14 天预测、AI 解析预览、供应商订单、管理员用户页；7 张截图见 `docs/assets/screenshots/README.md`；全新登录页控制台 0 errors / 0 warnings |
| RC2 权限与修正确认运行态抽查 | **通过** | 供应商 6 个全局接口均 403、看板全局预警为 0；管理员处理预警 403；预测过滤仅返回 `MAT-BOX-05`；AI 补全后 Schema/业务均为 true 并确认生成 `PURCHASE_DEMAND` 草稿 |
| Docker/Compose 实测 | **未执行** | 本机未安装 Docker；仅完成静态检查，不能称为 Docker 已部署或 MySQL 已验收 |

## 2. 后端集成测试（10 项）

执行对象：`com.scic.platform.PlatformIntegrationTest`。10 项均通过，关键覆盖如下：

最终复跑使用 Java 21（满足项目 Java 17+ 要求）。系统默认 PATH 仍指向 Java 8，直接手工运行 Maven 会出现 class file version 61/52 不兼容；`start-dev.ps1` 会自动选择 Java 17+，该环境陷阱已写入本地运行手册。

1. 四个演示角色登录，错误密码拒绝；
2. 供应商不能查看其他供应商订单，也不能读取主数据、全局预警和数据来源；供应商看板不返回全局预警统计；
3. 错误角色和非法状态跳转被阻断；
4. 采购人员创建需求，跨供应商计划被拒绝；
5. 非法 CSV 预览不改变业务表；
6. 到货前收货不能改变库存；
7. BCL-01 后半闭环可关闭订单、到货、收货和对账；
8. 管理员不能写业务数据（含不能处理业务预警），未知接口返回 404；
9. 预测运行列表严格按所选真实物料编码过滤；
10. AI 缺失字段补全后重新执行 Schema/业务校验，并可经幂等人工确认生成草稿。

### BCL-01 后半闭环实测摘要

本轮 API 真实跑通以下动作顺序：

`CONFIRM` → `PREPARE_SHIPMENT` → 创建并 `SUBMIT` 到货通知 → `DISPATCH` → `ARRIVE` → 收货入库 → 创建对账 → 供应商 `CONFIRM` → 采购方 `COMPLETE`。

可核对结果：

- 到货通知在收货前到达 `ARRIVED`，收货后终态为 `RECEIVED`；
- 收货记录终态为 `COMPLETED`；
- 对账记录终态为 `COMPLETED`；
- 订单终态为 `COMPLETED`；
- 收货使库存增加，并产生 `RECEIPT` 来源的库存流水；
- 收货和对账重复提交使用幂等键时返回同一业务记录。

后端测试报告：`backend/target/surefire-reports/TEST-com.scic.platform.PlatformIntegrationTest.xml`。对应测试源：`backend/src/test/java/com/scic/platform/PlatformIntegrationTest.java`。

## 3. AI 服务测试（13 项）

执行命令（工作目录 `D:\论文\ai-service`）：

```powershell
.\.venv\Scripts\python.exe -m pytest -q
```

结果：`13 passed`。覆盖内容包括：

- 健康检查无需 token；受保护接口缺失/错误 token 拒绝；
- `/api/v1/parse` 和内部解析契约、严格 schema 以及未知字段拒绝；
- 固定 14 日预测序列与提前期超窗口错误；
- MA7 递归、非负输出、重复日期聚合；
- 达到样本条件时的可复现模型选择证据；
- 采购需求、计划变更、到货通知三类规则解析；
- 缺少必填字段、未知意图和不冒充 LLM 的行为。

本轮 AI 解析 provider 为 `rule`，未配置或调用外部 OpenAI-compatible LLM。独立烟测记录见 `docs/05-experiments/AI_PARSE_SMOKE_2026-08-24.md`。

## 4. 静态检查

执行命令：

```powershell
.\scripts\verify.ps1 -StaticOnly
```

结果：`Static verification passed (Docker and running services not required).`

本次静态检查核对：

- 项目入口、启动/停止/验证脚本、四个服务部署文件和演示 CSV 均存在；
- PowerShell 脚本可解析；
- 启动脚本显式使用隐藏窗口；
- `.env.example` 和 `docker-compose.yml` 未将项目数据/缓存指向 C 盘；
- 四类导入 CSV 表头与后端契约一致；
- Compose、Dockerfile 和 Nginx 文件只按静态配置检查，未执行容器启动。

## 5. 性能摘要

检查对象为非 AI 看板接口，当前运行环境为本机 H2 文件库。采用 10 个并发、总计 200 次请求的检查：

| 指标 | 结果 |
|---|---:|
| 并发数 | 10 |
| 请求总数 | 200 |
| 非 2xx 响应数 | 0 |
| 5xx 响应数 | 0 |
| P95 | `88.81 ms` |

该结果是当前本机候选版本的接口检查，不等同于生产容量、MySQL/Compose 性能或安全压测结论。

## 6. 浏览器候选版巡检

- 四角色均使用真实后端登录：`admin`、`buyer`、`supplier`、`manager`；
- 采购角色核对总览、预测运行批次、逐日 14 点序列和 AI 结构化预览；
- 供应商角色只显示绑定供应商订单且不能读取全局主数据/预警；管理员角色显示用户与角色维护；管理者角色显示管理范围导航与看板；
- AI 页面解析请求返回 200，展示 provider=`rule`、字段证据、校验和影响预览，未点击“确认并保存草稿”；
- 预测页面使用服务端真实物料选项并按物料过滤批次；修复后选择器、标题和重算提交编码一致，并显示 `2026-08-24` 至 `2026-09-06`；
- 登录页 favicon 补齐后，以全新会话复核控制台为 0 errors / 0 warnings；
- 截图与口径索引：`docs/assets/screenshots/README.md`。

## 7. 未完成与边界

- 用户个人 UAT：尚未开始，不能写成用户验收通过；
- Docker：本机未安装，未执行 `docker compose up`、健康检查或全新 MySQL 8 迁移复现；
- 示例和预测数据：保持 `DEMO_SYNTHETIC` 标签，不是企业生产数据；
- 本记录不构成最终 V1.0 测试报告，需在 UAT、干净 Compose/MySQL 复现及最终阻断项复核后再冻结。

## 8. RC2 阻断回归补录（2026-08-25）

- 供应商身份读取供应商、物料、仓库、库存、全局预警、数据来源六个接口均返回 HTTP 403；供应商看板 `openWarnings=0`、`highWarnings=0`、`warningDistribution=[]`。
- 管理员身份处理业务预警返回 HTTP 403，保持“管理员不写业务数据”的边界。
- `materialCode=MAT-BOX-05` 的预测批次查询只返回 `MAT-BOX-05`；浏览器选择器、标题与批次一致。
- 真实规则解析创建缺少日期的采购需求预览，补全后返回 `schemaValid=true`、`businessValid=true`、`previewVersion=1`，再经幂等确认生成 `PURCHASE_DEMAND` 草稿。
- 以上场景同时纳入 Spring Boot 10 项自动回归；截图 UI-03、UI-05 已在 RC2 运行态重新取得并逐图检查。
