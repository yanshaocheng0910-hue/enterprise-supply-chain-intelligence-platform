# 总体架构与接口说明（工作版）

文档编号：SCIC-DES-001  
基线日期：2026-08-24  
当前状态：**UAT 候选，非最终 V1.0**

> 本说明按当前代码反向整理，用于交接、联调和 UAT。Docker 相关内容只完成静态配置检查；学校正式设计说明书的 Word 模板本轮未填写，仅留证，不能把本说明当作最终归档件。

## 1. 逻辑架构

```text
浏览器 / Vue3 + Vite + Element Plus
                 │ JWT Bearer / JSON
                 ▼
Spring Boot 3.3 模块化单体（唯一业务 API、权限、事务、审计、数据库写入）
                 │ X-Service-Token / JSON
                 ▼
FastAPI AI Service（无状态；规则解析、可选 OpenAI-compatible、MA7/XGBoost）
                 │
                 └── 不读写业务数据库、不持有数据库凭据

MySQL 8（Compose/生产候选）或 H2 文件（本地默认）
```

Spring Boot 负责业务事实校验、状态机、权限、事务和最终持久化。FastAPI 计算候选结果并返回解释，调用者自行记录 `forecast_run`/`ai_parse_record`；AI 结果不直接修改采购、库存或对账状态。

## 2. 部署拓扑与运行配置

| 组件 | 本地默认 | Compose 静态配置 | 数据/日志 |
|---|---:|---:|---|
| 前端开发服务器 | `127.0.0.1:5173` | `127.0.0.1:8088`（Nginx） | Vite/Nginx stdout 日志 |
| Spring Boot | `127.0.0.1:8080` | 容器 8080 | `D:\论文\.data\logs\backend.log` |
| FastAPI | `127.0.0.1:8001` | 内部 8001，不暴露宿主端口 | `D:\论文\.data\logs\ai-service.log` |
| MySQL | 可选 `127.0.0.1:3306` | `127.0.0.1:3306` | `D:\论文\.data\mysql` |
| H2 | `../.data/h2/scic` | 不使用 | `D:\论文\.data\h2` |

本地启动脚本把 Python、npm、Maven 缓存设置到 `D:\论文\.cache`，并把进程清单写到 `D:\论文\.data\run\dev-processes.json`。后台进程统一使用隐藏窗口。Docker 本机未安装，因此 `docker-compose.yml`、三个 Dockerfile 和 `frontend/nginx.conf` 只做路径、端口、变量和 YAML 静态验收。

## 3. 认证、请求追踪与跨域

- Spring Boot：登录 `/api/v1/auth/login` 返回 JWT；业务请求使用 `Authorization: Bearer <token>`。`admin`、`buyer`、`supplier`、`manager` 为开发种子账号，默认密码 `123456` 仅用于本地演示，部署前必须修改。
- FastAPI：除 `GET /health` 外均要求 `X-Service-Token`。令牌不写日志；每次响应提供 `X-Request-ID`，结构化日志使用同一请求号关联。
- CORS：Spring 和 FastAPI 都只允许环境变量中的显式来源，禁止 `*`。Compose 前端通过 Nginx 代理 `/api/` 到后端。
- 统一业务响应：Spring 返回 `{success,data,error,requestId,timestamp}`；错误包含机器可读 code 和 message。FastAPI 按 AI 契约直接返回 JSON；认证/验证错误为 HTTP 401/422，预测提前期错误为 HTTP 422。

## 4. Spring Boot 实际接口目录

除另有说明，以下接口均在 `/api/v1` 下、使用 JWT，并按角色和供应商范围授权。

| 模块 | 方法与路径 | 主要角色/用途 |
|---|---|---|
| auth | `POST /auth/login`、`GET /auth/me`、`POST /auth/logout` | 登录、当前用户、退出 |
| dashboard | `GET /dashboard/summary` | BUYER/MANAGER/ADMIN 看板汇总 |
| imports | `GET/POST /imports`、`POST /imports/preview`、`POST /imports/{batchId}/commit`、`GET /imports/{batchId}/errors` | 四类 CSV 批次、预览、提交、错误 |
| master-data | `GET/POST /master-data/suppliers`；`GET/POST /master-data/materials`；`GET /master-data/warehouses`；`GET /master-data/inventory` | 主数据和库存查询/维护 |
| procurement | `GET/POST /procurement/demands`；`GET /procurement/plans`、`/plans/{id}`；`POST /procurement/plans`、`/plans/{id}/actions`、`/plans/{id}/generate-orders`；`GET /procurement/orders`、`/orders/{id}`；`POST /procurement/orders/{id}/actions` | 需求、计划、订单和状态动作 |
| intelligence | `GET /intelligence/forecast-runs`、`/{id}`；`POST /intelligence/forecast-runs`；`GET /intelligence/parse-records`；`GET/POST/PATCH /intelligence/parse-previews/{id}`；`POST /parse-previews/{id}/confirm`、`/cancel` | 预测运行、AI 解析预览和确认 |
| collaboration | `GET/POST /collaboration/delivery-notices`、`/{id}`、`/{id}/actions`；`GET/POST /receipts`、`/{id}`；`GET/POST /reconciliations`、`/{id}`、`/{id}/actions` | 到货、收货、对账协同 |
| system | `GET /system/warnings`、`POST /system/warnings/{id}/handle`；`GET /system/audit-logs`；`GET /system/data-provenance` | 预警、操作审计、来源追踪 |
| admin | `GET /admin/roles`、`/users`；`POST /admin/users`；`PATCH /admin/users/{id}` | ADMIN 管理 |

源码中的 `@PreAuthorize`、状态服务和供应商范围服务是最终授权依据；本表用于导航，不替代 OpenAPI。

## 5. FastAPI AI 接口契约

### 5.1 健康检查

`GET /health` 不需要令牌，返回服务状态和实际解析 provider。`parser_provider` 为 `rule` 时表示当前未配置可用的 OpenAI-compatible provider，不得展示为大模型。

### 5.2 受限文本解析

同时提供兼容路径：

- `POST /internal/v1/parse`（Spring 内部调用的首选路径）
- `POST /api/v1/parse`（开发和兼容调用）

请求严格拒绝未知字段：

```json
{
  "text": "请采购物料编码 MAT-CPU-01 20 件，2026-09-01 前到货，供应商 SUP-001",
  "intent_hint": "purchase_demand"
}
```

`text` 也兼容别名 `original_text`，`intent_hint` 也兼容 `intent`。白名单意图只有：

- `purchase_demand`：物料、数量、需求日期等采购需求；
- `plan_change`：计划号、变更类型、数量/日期变化；
- `delivery_notice`：订单号、数量、预计到货日期、运输信息。

响应固定包含 `original_text`、`intent`、`fields`、`missing_fields`、`warnings`、`confidence`、`confidence_note`、`provider` 和 `requires_confirmation=true`。`confidence` 未经业务校准，仅供参考，不可单独驱动自动决策。未配置完整 `LLM_PROVIDER=openai-compatible`、`OPENAI_BASE_URL`、`OPENAI_API_KEY`、`OPENAI_MODEL` 时，provider 只能为 `rule`；规则降级可复现且不写数据库。相对日期不擅自换算，通常以 warning/缺失字段提示人工确认。

### 5.3 14 日预测

同时提供：`POST /internal/v1/forecast` 和 `POST /api/v1/forecast`。请求包含 `history: [{date,quantity}]`，可选 `material_id/material_code`、`as_of_date`、`lead_time`，`horizon` 必须为 14。数量非负且有限；同日数据按日聚合，缺失日期按 0 参与滞后计算。

模型选择和评估口径：

1. 固定未来窗口为连续 14 天；每一步递归写回前一步预测。
2. 有效天数至少 300 且非零日至少 60 才具备 XGBoost 候选资格。
3. 时间顺序 70%/15%/15% 切分；验证区间做三个 14 日扩展窗，逐折保存 XGBoost 与 MA7 的 MAE。
4. 只有 XGBoost 平均验证 MAE 严格低于 MA7 且至少赢得 3 折中的 2 折，才选择 XGBoost；否则使用 MA7，并返回 `fallback_reason` 和 warnings。
5. MAE、RMSE 在测试样本上计算；MAPE 只在真实需求大于 0 的样本上计算，零需求不进入分母。XGBoost 未安装、数据不足或训练失败均可安全降级。

响应包含 `model`、`fallback_reason`、`metrics`、`evaluation.split`、`evaluation.expanding_windows`、`evaluation.selection`、`postprocessing`、`warnings` 和恰好 14 个连续、非负的 `sequence`。后处理说明记录非负截断、保留小数和递归方式。`lead_time > 14` 返回 code `HORIZON_INSUFFICIENT`，不生成自动建议量。

## 6. 状态与一致性

计划主路径：`DRAFT → PENDING_APPROVAL → APPROVED → ORDER_CREATED → CLOSED`，异常可为 `REJECTED/CANCELLED`。订单主路径：`PENDING_CONFIRMATION → CONFIRMED → PENDING_SHIPMENT → SHIPPED → ARRIVED → RECEIVED → RECONCILING → COMPLETED`，异常可为 `REJECTED/CANCELLED`。到货通知和对账状态按路线图定义的后端状态服务执行。

后端动作必须同时检查当前状态、角色、数据范围和版本/幂等约束。收货、库存和库存流水由业务事务统一写入；FastAPI 只提供计算结果，不参与事务。

## 7. 当前联调状态与剩余风险

前端 `src/services/api.ts` 已集中维护语义路径到后端真实路径的映射（例如 `/purchase-demands` 对齐 `/procurement/demands`），并完成四角色候选版浏览器联调。当前材料仍只能标记 UAT 候选：用户个人 UAT、干净 MySQL 8/Compose 复现、生产数据实验和最终材料冻结尚未完成；后续接口变更必须同步更新路径映射、契约测试和追踪矩阵。
