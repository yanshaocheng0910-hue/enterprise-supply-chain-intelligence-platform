# AI Service

供应链平台的无状态 FastAPI 智能能力服务。服务不读取、不写入业务数据库，也不持有业务库凭据；业务服务负责权限、业务事实校验、人工确认和最终落库。

## 本地运行

项目根目录固定为 `D:\论文`。依赖与缓存放在 D 盘：

```powershell
cd D:\论文
$env:PIP_CACHE_DIR = 'D:\论文\.cache\pip'
py -3.11 -m venv ai-service\.venv
& ai-service\.venv\Scripts\python.exe -m pip install -r ai-service\requirements.txt
& ai-service\.venv\Scripts\python.exe -m uvicorn app.main:app --app-dir ai-service --host 127.0.0.1 --port 8001
```

当前环境若只有其他 Python 版本，使用已安装且兼容项目依赖的版本创建 `.venv`。可选 XGBoost 不在基础依赖中：

```powershell
& ai-service\.venv\Scripts\python.exe -m pip install -r ai-service\requirements-optional.txt
```

复制 `.env.example` 为 `.env` 并替换 `X_SERVICE_TOKEN`。规则解析默认离线可用；没有完整的 `LLM_PROVIDER=openai-compatible`、`OPENAI_BASE_URL`、`OPENAI_API_KEY` 和 `OPENAI_MODEL` 配置时，响应的 `provider` 永远是 `rule`，不会伪装成大模型结果。`LLM_LOCAL_ONLY=true` 为默认安全策略，只允许 `127.0.0.1`、`localhost` 或 `::1` 上的本地模型；误填外部地址时不会发送业务文本，而是显式降级为规则解析。

## 认证与通用行为

除 `GET /health` 外，接口都要求请求头：

```text
X-Service-Token: <与服务配置一致的令牌>
```

服务返回 `X-Request-ID` 以便和结构化日志关联。令牌、API Key 和原始文本不会写入日志。CORS 只允许 `CORS_ALLOW_ORIGINS` 中的明确来源，不允许通配符。

## 接口契约

### `GET /health`

不需要令牌，响应示例：

```json
{
  "status": "ok",
  "service": "ai-service",
  "version": "0.1.0",
  "parser_provider": "rule"
}
```

### `POST /api/v1/parse`（内部同契约：`POST /internal/v1/parse`）

请求 Schema 只接受 `text`（也兼容输入别名 `original_text`）和可选 `intent_hint`（也兼容 `intent`）。未知字段返回 422。意图白名单固定为：

- `purchase_demand`：采购需求；必需物料编码/名称、数量、需求日期；
- `plan_change`：计划变更；必需计划号、变更类型，数量或日期字段按变更类型补齐；
- `delivery_notice`：到货通知；必需订单号、数量、预计到货日期。

请求示例：

```json
{
  "text": "请采购物料编码 M-100 120 个，2026-09-01 前到货，供应商为 S-01",
  "intent_hint": "purchase_demand"
}
```

响应始终包含原文、意图、结构化字段、缺失字段、警告、置信度、实际 Provider 和 `requires_confirmation=true`：

```json
{
  "original_text": "请采购物料编码 M-100 120 个，2026-09-01 前到货，供应商为 S-01",
  "intent": "purchase_demand",
  "fields": {
    "material_code": "M-100",
    "supplier_code": "S-01",
    "quantity": 120.0,
    "unit": "个",
    "required_date": "2026-09-01"
  },
  "missing_fields": [],
  "warnings": ["未配置 OpenAI-compatible provider，使用可复现规则解析"],
  "confidence": 0.96,
  "confidence_note": "confidence 仅供参考，不用于自动决策",
  "provider": "rule",
  "requires_confirmation": true
}
```

规则解析不把相对日期（如“明天”“下周一”）擅自换算为日期，而是返回警告和缺失字段；业务服务应传入明确日期或让用户确认。FastAPI 只生成候选草稿，不产生采购单、计划或到货记录。

### `POST /api/v1/forecast`（内部同契约：`POST /internal/v1/forecast`）

预测窗口固定为 14 天，不接受任意扩展。请求至少提供 `history`，每个点包含 `date` 和 `quantity`（也兼容 `demand`/`value`），可提供 `material_id` 或 `material_code`、`as_of_date` 和 `lead_time`。`lead_time > 14` 返回 422：

```json
{
  "detail": {
    "code": "HORIZON_INSUFFICIENT",
    "message": "固定预测窗口为 14 天，lead_time 大于 14 天无法覆盖",
    "request_id": "..."
  }
}
```

策略：

1. 先将同一物料历史按日聚合，缺失日期按 0 参与滞后计算；
2. 有效天数至少 300 且非零需求日至少 60 时优先逐物料 XGBoost；
3. XGBoost 特征为滞后、滚动统计和日历特征，滚动特征只使用预测时点之前的数据；
4. 按时间顺序 70%/15%/15% 切分，验证区间进行三个 14 日扩展窗评估，逐折同时计算 XGBoost 与 MA7 的 MAE，最后测试区间只评估一次；
5. 只有当 XGBoost 平均验证 MAE 低于 MA7 且三个扩展窗至少赢两折时才选用 XGBoost；否则、XGBoost 未安装、验证数据不足或训练失败时使用递归 MA7，并在 `fallback_reason` 和 `warnings` 说明原因；
6. MAE、RMSE 使用全部测试样本；MAPE 只在真实需求大于 0 的样本上计算，零需求样本不进入分母。

响应核心字段：`model`（`xgboost` 或 `ma7`）、`fallback_reason`、`metrics`、`evaluation.expanding_windows`（逐折双模型指标）、`evaluation.selection`、`postprocessing` 和长度严格为 14 的连续非负 `sequence`（`date` + `forecast`）。服务不保存模型或预测结果，调用方自行持久化带版本和快照的信息。

请求示例（完整示例见 `examples/forecast.json`）：

```json
{
  "material_code": "M-100",
  "lead_time": 7,
  "history": [
    {"date": "2026-08-01", "quantity": 12},
    {"date": "2026-08-02", "quantity": 15}
  ]
}
```

## 测试

```powershell
cd D:\论文\ai-service
$env:PIP_CACHE_DIR = 'D:\论文\.cache\pip'
```

Windows 下直接执行：

```powershell
& .venv\Scripts\python.exe -m pytest
```

测试覆盖健康检查、令牌缺失/错误、严格 Schema、三类规则解析、缺失字段、14 日递归序列、MA7 降级、指标口径和 `HORIZON_INSUFFICIENT`。基础测试不依赖 XGBoost；安装可选依赖后，达到门槛的测试数据会自动走主模型并仍验证相同契约。
