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
| 智能 | `GET /api/v1/intelligence/forecast-runs[/{id}]`；`POST /api/v1/intelligence/forecast-runs`；`GET /api/v1/intelligence/parse-records`；`GET/POST/PATCH /api/v1/intelligence/parse-previews[/{id}]`；`POST /api/v1/intelligence/parse-previews/{id}/confirm`；`POST /api/v1/intelligence/parse-previews/{id}/cancel` |
| 协同 | `GET/POST /api/v1/collaboration/delivery-notices[/{id}]`；`POST /api/v1/collaboration/delivery-notices/{id}/actions`；`GET/POST /api/v1/collaboration/receipts[/{id}]`；`GET/POST /api/v1/collaboration/reconciliations[/{id}]`；`POST /api/v1/collaboration/reconciliations/{id}/actions` |
| 系统 | `GET /api/v1/system/warnings`；`POST /api/v1/system/warnings/{id}/handle`；`GET /api/v1/system/audit-logs`；`GET /api/v1/system/data-provenance` |
| 管理 | `GET /api/v1/admin/roles`；`GET/POST /api/v1/admin/users`；`PATCH /api/v1/admin/users/{id}` |

方括号表示同一路由的列表/详情变体，不表示文字方括号应出现在 URL 中。

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
3. 核对前端服务适配路径与本清单；当前已知部分语义路径仍需 UAT 联调，不以 404 作为理由绕过后端。
4. 确认解析结果经过业务预览/确认后才写库，预测结果带模型、指标和降级说明。
5. 本轮 Docker 仅静态检查，实际容器接口和 Nginx 代理待有 Docker 的环境验收。
