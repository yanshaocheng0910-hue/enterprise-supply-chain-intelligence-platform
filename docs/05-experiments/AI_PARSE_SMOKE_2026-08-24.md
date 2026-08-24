# AI 解析烟测记录（2026-08-24）

文档编号：SCIC-AI-PARSE-SMOKE-20260824  
执行日期：2026-08-24（Asia/Shanghai）  
版本：`0.9.0-UAT-RC2`  
当前状态：**V0.9 UAT 候选，非最终 V1.0**

> 本烟测验证的是离线、可复现的规则 provider 契约。`rule` 不是外部 LLM；本次没有调用或冒充 OpenAI-compatible 外部模型。

## 1. 执行条件

| 项目 | 值 |
|---|---|
| 接口 | `POST /api/v1/parse` |
| Provider | `rule` |
| 外部 LLM | 未配置、未调用 |
| 鉴权 | `X-Service-Token`（本地测试 token） |
| 业务动作 | 不执行；结果必须人工确认后才能进入业务服务 |
| 数据标签 | 演示烟测文本，不是生产业务数据 |

执行方式为 AI 服务本地 TestClient 烟测；同一候选版本的完整 AI 自动化回归为 `13 passed`，见 `docs/04-testing/TEST_EXECUTION_2026-08-24.md`。

## 2. 请求

```http
POST /api/v1/parse
X-Service-Token: <local-test-token>
X-Request-ID: ai-smoke-20260824
Content-Type: application/json
```

```json
{
  "text": "请采购物料编码 M-100 120 个，2026-09-01 前到货，供应商为 S-01"
}
```

## 3. 结果摘要

HTTP 状态：`200`

| 字段 | 结果 |
|---|---|
| `intent` | `purchase_demand` |
| `provider` | `rule` |
| `fields.material_code` | `M-100` |
| `fields.supplier_code` | `S-01` |
| `fields.quantity` | `120.0` |
| `fields.unit` | `个` |
| `fields.required_date` | `2026-09-01` |
| `missing_fields` | `[]` |
| `confidence` | `0.94`（仅供参考） |
| `requires_confirmation` | `true` |

响应警告明确标识未配置 OpenAI-compatible provider，并保留“confidence 仅供参考、不用于自动决策”的说明。

## 4. 结论与限制

- 本次证明规则解析路径可在无外部 LLM、无网络依赖的本地环境返回受控结构；
- 本次不证明外部 LLM 的可用性、准确率、鲁棒性或生产 SLA；
- 本次不证明用户 UAT 已完成，也不替代 60 条以上 Ground Truth、错误分类和人工确认统计；
- 原文、结构化字段、缺失字段、provider 和确认标记必须持续留存，任何确认接口仍由后端事实校验和人工确认控制。
