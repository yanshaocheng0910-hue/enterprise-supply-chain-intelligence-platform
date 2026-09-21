# API 契约索引（工作版）

文档编号：SCIC-API-001  
基线日期：2026-08-24  
当前状态：**UAT 候选，非最终 V1.0**

本文是接口导航和联调检查表，详细架构说明见 `docs/02-design/ARCHITECTURE_AND_API_V1.0.md`。实际实现以 Spring Boot controller、FastAPI Pydantic models 和运行时 OpenAPI 为准；学校正式接口文档模板本轮未填写。

## 1. 入口与认证

| 服务 | 入口 | 认证 | 写库边界 |
|---|---|---|---|
| Spring Boot | `/api/v1/**` | `Authorization: Bearer <JWT>` | 唯一业务数据库写入方 |
| FastAPI | `/internal/v1/**`（首选）或 `/api/v1/**`（兼容） | `X-Service-Token`；`/health` 除外 | 不读写业务数据库 |

Spring 统一返回 `{success,data,error,requestId,timestamp}`；FastAPI 返回契约 JSON，并返回 `X-Request-ID`。认证失败、严格 schema 错误和业务提前期错误都应保留可追踪 code，不在响应或日志泄漏密钥。

## 2. Spring Boot 路由清单

| 资源 | 路由 |
|---|---|
| 认证 | `POST /api/v1/auth/login`；`GET /api/v1/auth/me`；`POST /api/v1/auth/logout` |
| 看板 | `GET /api/v1/dashboard/summary` |
| 导入 | `GET/POST /api/v1/imports`；`POST /api/v1/imports/preview`；`POST /api/v1/imports/{batchId}/commit`；`GET /api/v1/imports/{batchId}/errors` |
| 主数据 | `GET/POST /api/v1/master-data/suppliers`；`GET/POST /api/v1/master-data/materials`；`GET /api/v1/master-data/warehouses`；`GET /api/v1/master-data/inventory` |
| 采购 | `GET/POST /api/v1/procurement/demands`；`GET /api/v1/procurement/plans[/{id}]`；`POST /api/v1/procurement/plans`；`POST /api/v1/procurement/plans/{id}/actions`；`POST /api/v1/procurement/plans/{id}/generate-orders`；`GET /api/v1/procurement/orders[/{id}]`；`POST /api/v1/procurement/orders/{id}/actions` |
| 智能 | `GET /api/v1/intelligence/forecast-runs[/{id}]`；`POST /api/v1/intelligence/forecast-runs`；`POST /api/v1/intelligence/forecast-runs/{id}/adopt`；`POST /api/v1/intelligence/forecast-runs/{id}/reject`；`GET/POST /api/v1/intelligence/scenarios[/{id}]`；`POST /api/v1/intelligence/scenarios/{id}/adopt`；`GET /api/v1/intelligence/parse-records`；`GET/POST/PATCH /api/v1/intelligence/parse-previews[/{id}]`；`POST /api/v1/intelligence/parse-previews/{id}/confirm`；`POST /api/v1/intelligence/parse-previews/{id}/cancel` |
| 协同 | `GET/POST /api/v1/collaboration/delivery-notices[/{id}]`；`POST /api/v1/collaboration/delivery-notices/{id}/actions`；`GET/POST /api/v1/collaboration/receipts[/{id}]`；`GET/POST /api/v1/collaboration/reconciliations[/{id}]`；`POST /api/v1/collaboration/reconciliations/{id}/actions` |
| 系统 | `GET /api/v1/system/warnings`；`POST /api/v1/system/warnings/{id}/handle`；`GET /api/v1/system/audit-logs`；`GET /api/v1/system/data-provenance` |
| 管理 | `GET /api/v1/admin/roles`；`GET/POST /api/v1/admin/users`；`PATCH /api/v1/admin/users/{id}` |

方括号表示同一路由的列表/详情变体，不表示文字方括号应出现在 URL 中。

### 2.1 预测建议处理

`GET /api/v1/intelligence/forecast-runs[/{id}]` 为采购人员/管理者提供预测及建议状态；只有 BUYER 可写入处理决定。

列表行返回汇总字段 `suggested_order_qty`、`suggestion_status`、`suggestion_decision_note`、`optimization_note` 和版本号；已处理的历史批次仍显示原建议量。详情附 `suggestedOrderQty` 与已关联的 `adoptedDemand`（无采纳需求时为空）。

`POST /api/v1/intelligence/forecast-runs/{id}/adopt`

- Header：`Authorization: Bearer <JWT>`、`Idempotency-Key`；角色：BUYER。
- 请求：`expectedVersion` 必填，`expectedDate` 必填且为当日或未来日期；`priority`、`note` 可选。
- 仅已完成、状态为 `PENDING` 且建议数量大于 0 的预测可采纳；预测提前期超出 14 日等没有可采纳数量的情况会被拒绝。
- 成功后生成一条 `source_type=FORECAST` 的 `DRAFT` 采购需求，关联来源预测批次；同一预测的重复请求返回已关联需求，不重复建单。请求键与预测批次的一对一数据库约束共同防止重复。
- 预测版本不匹配或建议已被处理时返回冲突；字段应以当前 Controller/运行时 OpenAPI 为准。

`POST /api/v1/intelligence/forecast-runs/{id}/reject` 仅 BUYER 可调用，请求包含 `expectedVersion` 和必填 `note`；成功后建议状态为 `REJECTED`，不创建采购需求。建议的采纳/拒绝只能由人工决策，不会自动生成订单。

### 2.2 采购情景推演

- `POST /api/v1/intelligence/scenarios`：BUYER/MANAGER，Header 必须包含 `Idempotency-Key`。输入情景名称、物料编码，以及需求变化、供应延迟、安全库存变化、到货合格率和采购价格变化；可指定成功的预测批次，不指定时取该物料最新成功批次。
- 返回并持久化两小时有效的冻结结果：来源预测、输入参数、库存/在途事实、基准与模拟汇总、14 日逐日库存投影、受影响订单、假设、数据指纹和版本。模拟阶段不写采购需求、库存或订单。
- `GET /api/v1/intelligence/scenarios` 与 `GET /api/v1/intelligence/scenarios/{id}`：BUYER 只能读取本人记录，MANAGER 可读取全部记录；过期预览以 `EXPIRED` 返回。
- `POST /api/v1/intelligence/scenarios/{id}/adopt`：仅 BUYER，Header 必须包含 `Idempotency-Key`，请求包含 `expectedVersion`、到货日期、优先级和可选说明。仅未过期、状态为 `PREVIEW` 且建议量大于 0 的记录可确认；成功创建 `source_type=SCENARIO` 的 `DRAFT` 采购需求。同键重放返回原需求，不重复写入。
- MANAGER 可创建和比较只读情景，但不能调用采纳接口；SUPPLIER/ADMIN 无情景推演业务权限。全部数量、金额和风险由后端确定性规则计算，不由 LLM 直接决定。

### 2.3 订单与收货状态命令

- 订单直接 `SHIP` / `MARK_ARRIVED` 命令被拒绝；发运及到达通过 `delivery-notices/{id}/actions` 的通知工作流完成。
- 收货请求仅 BUYER 可写，需提供 `Idempotency-Key`、订单版本、订单明细及本次实收/合格/拒收数量，并关联 `ARRIVED` 到货通知。
- `PARTIALLY_RECEIVED` 表示分批收货尚未全部合格入库；合格数量累计更新订单与库存，拒收数量需要原因且不计入已收合格数量。订单全部合格收齐后才可创建对账。
- `START_RECONCILIATION` 不是订单直接状态命令；对账经 `POST /api/v1/collaboration/reconciliations` 建立，再使用对账动作处理。

### 2.4 供应链经营分析报告

- `GET /api/v1/intelligence/analysis-reports`、`GET /api/v1/intelligence/analysis-reports/{id}`：BUYER/MANAGER 读取快照列表与详情；SUPPLIER/ADMIN 无企业经营报告权限。
- `POST /api/v1/intelligence/analysis-reports`：BUYER/MANAGER，Header 必须包含 `Idempotency-Key`；后端汇总订单、库存、供应商、对账和预警统计，计算 SHA-256 数据指纹后调用 FastAPI。
- FastAPI `/internal/v1/analysis-report` 只接收严格统计 Schema，不连接业务数据库。Qwen 只提供闭集章节排序；精确数值、事实文本和行动路由由规则生成。模型不可用或输出越界时返回明确的 `provider=rule` 和 `fallback_reason`。
- 报告写入独立快照和操作审计，不修改需求、计划、订单、库存、收货或对账状态。同一幂等键重放返回原报告。

## 3. FastAPI parse

`POST /internal/v1/parse` 与 `POST /api/v1/parse` 请求：

```json
{
  "text": "请采购物料编码 MAT-CPU-01 20 件，2026-09-01 前到货，供应商 SUP-001",
  "intent_hint": "purchase_demand"
}
```

只允许 `text`/别名 `original_text` 和 `intent_hint`/别名 `intent`；未知字段 422。意图白名单为 `purchase_demand`、`plan_change`、`delivery_notice`。响应必须有：

```text
original_text, intent, fields, missing_fields, warnings,
confidence, confidence_note, provider, requires_confirmation=true
```

`provider=rule` 是默认可复现降级；只有完整配置可用的 OpenAI-compatible provider 才能返回 `openai-compatible`。confidence 未校准，仅供参考，不得自动决策。FastAPI 不创建需求、计划、订单或到货记录。

## 4. FastAPI forecast

`POST /internal/v1/forecast` 与 `POST /api/v1/forecast`：

- 请求：`history`（`date`、非负 `quantity`；也兼容 demand/value）、可选物料标识、`as_of_date`、`lead_time`，`horizon` 必须为 14；
- 响应：`material_id/material_code`、`as_of_date`、`horizon=14`、`lead_time`、`model`、`fallback_reason`、`metrics`、`evaluation`、`sequence`、`warnings`、`postprocessing`；
- `sequence` 恰好 14 天、日期连续、预测非负；后处理说明需保留非负截断、保留小数和递归计算；
- 数据达到至少 300 个有效天和 60 个非零天，才进入 XGBoost 候选；70/15/15 时间切分，验证区间三个 14 日扩展窗；只有平均验证 MAE 低于 MA7 且至少赢 2/3 折才选择 XGBoost；
- MAE/RMSE 为测试样本指标；MAPE 只对真实值大于 0 的样本计算；无依赖/数据不足/训练失败返回 MA7 和降级理由；
- `lead_time > 14` 返回 HTTP 422 的 `HORIZON_INSUFFICIENT`，不生成自动建议量。

## 5. 联调检查

1. 从根 `.env.example` 复制配置，保持 Spring 与 FastAPI token 一致。
2. 先请求 `/health`，再用 token 调 parse/forecast；核对 `X-Request-ID` 和日志。
3. 核对前端服务适配路径与本清单；本轮成功预测路径和 BUYER 采纳页已有运行态证据；失败/超时路径、其它角色和完整页面交互仍待联调，不以离线测试代替运行态证据。
4. 确认解析结果经过业务预览/确认后才写库；预测结果带模型、指标、降级说明、建议状态和人工处理记录。
5. 本轮 Docker 仅静态检查，实际容器接口和 Nginx 代理待有 Docker 的环境验收。
