"""Pydantic contracts for the stateless AI service.

The AI service deliberately exposes a small, versionable surface.  Input models
reject unknown fields so that a typo cannot silently change a forecast or an
AI parsing result.  Business persistence and authorization remain outside this
service.
"""

from __future__ import annotations

import math
from datetime import date, datetime
from typing import Any, Literal

from pydantic import (
    AliasChoices,
    BaseModel,
    ConfigDict,
    Field,
    field_validator,
    model_validator,
)


Intent = Literal["purchase_demand", "plan_change", "delivery_notice"]
ParseProvider = Literal["rule", "openai-compatible"]
ForecastModel = Literal["ma7", "xgboost"]


class StrictModel(BaseModel):
    """Base contract with a closed schema and predictable string handling."""

    model_config = ConfigDict(
        extra="forbid",
        populate_by_name=True,
        str_strip_whitespace=True,
    )


class ParseRequest(StrictModel):
    text: str = Field(
        min_length=1,
        max_length=4000,
        validation_alias=AliasChoices("text", "original_text"),
        description="待解析的中文原文，最长 4000 字符",
    )
    intent_hint: Intent | None = Field(
        default=None,
        validation_alias=AliasChoices("intent_hint", "intent"),
        description="可选的白名单意图提示；未提供时由规则或 Provider 推断",
    )

    @field_validator("text")
    @classmethod
    def text_must_not_be_blank(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("text 不能为空")
        return value.strip()


class ParsedFieldValues(StrictModel):
    """All fields that the three supported intents may produce.

    The response only serializes non-null values, while this closed model still
    validates provider output before it can be returned to the business layer.
    """

    material_code: str | None = None
    material_name: str | None = None
    supplier_code: str | None = None
    supplier_name: str | None = None
    warehouse_code: str | None = None
    quantity: float | None = Field(default=None, ge=0)
    unit: str | None = None
    required_date: date | None = None
    plan_no: str | None = None
    change_type: str | None = None
    old_quantity: float | None = Field(default=None, ge=0)
    new_quantity: float | None = Field(default=None, ge=0)
    quantity_delta: float | None = None
    order_no: str | None = None
    ship_date: date | None = None
    eta: date | None = None
    tracking_no: str | None = None
    delivery_status: str | None = None
    reason: str | None = None

    @field_validator(
        "quantity",
        "old_quantity",
        "new_quantity",
        "quantity_delta",
        mode="before",
    )
    @classmethod
    def finite_numbers(cls, value: Any) -> Any:
        if value is None:
            return None
        try:
            number = float(value)
        except (TypeError, ValueError) as exc:
            raise ValueError("数量必须是数字") from exc
        if not math.isfinite(number):
            raise ValueError("数量必须是有限数字")
        return number


class ParseResponse(StrictModel):
    original_text: str
    intent: Intent
    fields: dict[str, str | float | int | bool | None]
    missing_fields: list[str]
    warnings: list[str]
    confidence: float = Field(ge=0, le=1)
    confidence_note: str = "confidence 仅供参考，不用于自动决策"
    provider: ParseProvider
    model_name: str
    prompt_version: str
    fallback_reason: str | None = None
    requires_confirmation: Literal[True] = True


class DemandPoint(StrictModel):
    date: date
    quantity: float = Field(
        ge=0,
        le=1_000_000_000_000,
        validation_alias=AliasChoices("quantity", "demand", "value"),
    )

    @field_validator("quantity", mode="before")
    @classmethod
    def finite_quantity(cls, value: Any) -> Any:
        try:
            number = float(value)
        except (TypeError, ValueError) as exc:
            raise ValueError("quantity 必须是数字") from exc
        if not math.isfinite(number):
            raise ValueError("quantity 必须是有限数字")
        return number


class ForecastRequest(StrictModel):
    material_id: str | None = Field(
        default=None,
        validation_alias=AliasChoices("material_id", "id"),
    )
    material_code: str | None = Field(
        default=None,
        validation_alias=AliasChoices("material_code", "sku", "material"),
    )
    history: list[DemandPoint] = Field(min_length=1, max_length=20_000)
    lead_time: int = Field(
        default=0,
        ge=0,
        le=3650,
        validation_alias=AliasChoices("lead_time", "lead_time_days"),
    )
    as_of_date: date | None = None
    horizon: int = Field(default=14, ge=1, le=14)

    @field_validator("material_id", "material_code")
    @classmethod
    def material_reference_not_blank(cls, value: str | None) -> str | None:
        if value is not None and not value.strip():
            raise ValueError("物料标识不能是空字符串")
        return value.strip() if value is not None else value

    @model_validator(mode="after")
    def fixed_horizon(self) -> "ForecastRequest":
        if self.horizon != 14:
            raise ValueError("horizon 固定为 14")
        return self


class ForecastPoint(StrictModel):
    date: date
    forecast: float = Field(ge=0)


class MetricResult(StrictModel):
    mae: float | None = None
    rmse: float | None = None
    mape: float | None = None
    sample_count: int = Field(ge=0)
    nonzero_actual_count: int = Field(ge=0)
    mape_definition: str = (
        "仅在真实需求大于 0 的样本上计算，真实需求为 0 的样本不进入 MAPE 分母"
    )


class ExpandingWindowResult(StrictModel):
    window: int = Field(ge=1, le=3)
    train_end_index: int = Field(ge=0)
    validation_start_index: int = Field(ge=0)
    validation_end_index: int = Field(ge=0)
    metrics: MetricResult
    xgboost_metrics: MetricResult | None = None
    ma7_metrics: MetricResult | None = None


class EvaluationResult(StrictModel):
    split: dict[str, float | str]
    train_samples: int = Field(ge=0)
    validation_samples: int = Field(ge=0)
    test_samples: int = Field(ge=0)
    expanding_windows: list[ExpandingWindowResult]
    test: MetricResult
    selection: dict[str, float | int | str | bool | None] | None = None
    postprocessing: dict[str, bool | int | str] | None = None


class PostprocessingInfo(StrictModel):
    nonnegative_clamp: bool = True
    rounding_decimals: int = Field(default=6, ge=0, le=12)
    recursive: bool = True
    explanation: str = "预测值经过非负截断并保留 6 位小数；未来每一步将前一步预测写回序列"


class ForecastResponse(StrictModel):
    material_id: str | None
    material_code: str | None
    as_of_date: date
    horizon: Literal[14] = 14
    lead_time: int = Field(ge=0)
    model: ForecastModel
    fallback_reason: str | None = None
    metrics: MetricResult
    evaluation: EvaluationResult
    sequence: list[ForecastPoint] = Field(min_length=14, max_length=14)
    warnings: list[str]
    postprocessing: PostprocessingInfo


class AnalysisMetrics(StrictModel):
    active_orders: int = Field(ge=0)
    overdue_orders: int = Field(ge=0)
    inventory_shortages: int = Field(ge=0)
    open_warnings: int = Field(ge=0)
    high_warnings: int = Field(ge=0)
    pending_plans: int = Field(ge=0)
    pending_reconciliations: int = Field(ge=0)
    reconciliation_difference_amount: float = Field(ge=0)
    rejected_quantity: float = Field(ge=0)
    active_suppliers: int = Field(ge=0)
    average_on_time_rate: float = Field(ge=0, le=1)


class AnalysisRiskFact(StrictModel):
    kind: str = Field(min_length=1, max_length=64)
    label: str = Field(min_length=1, max_length=255)
    severity: str = Field(min_length=1, max_length=16)
    created_at: datetime


class AnalysisReportRequest(StrictModel):
    as_of_time: datetime
    timezone: Literal["Asia/Shanghai"]
    scope: Literal["企业全局供应链业务库，只读统计快照"]
    metrics: AnalysisMetrics
    top_risks: list[AnalysisRiskFact] = Field(max_length=8)
    data_fingerprint: str = Field(pattern=r"^[0-9a-f]{64}$")


class AnalysisSection(StrictModel):
    key: Literal[
        "executive_summary", "demand_inventory", "supplier_fulfillment",
        "reconciliation_finance", "warning_risk", "recommendations", "boundary",
    ]
    title: str = Field(min_length=1, max_length=40)
    content: str = Field(min_length=1, max_length=800)


class AnalysisAction(StrictModel):
    code: Literal[
        "RESOLVE_HIGH_WARNINGS", "EXPEDITE_OVERDUE_ORDERS", "REPLENISH_SHORTAGE",
        "REVIEW_RECON_DIFFERENCE", "APPROVE_PENDING_PLANS", "MONITOR_OPERATIONS",
    ]
    level: Literal["HIGH", "MEDIUM", "LOW"]
    title: str = Field(min_length=1, max_length=80)
    rationale: str = Field(min_length=1, max_length=300)
    route: str = Field(min_length=1, max_length=80)


class AnalysisReportResponse(StrictModel):
    provider: ParseProvider
    model_name: str
    prompt_version: str
    fallback_reason: str | None = None
    warnings: list[str]
    sections: list[AnalysisSection] = Field(min_length=7, max_length=7)
    priority_actions: list[AnalysisAction] = Field(min_length=1, max_length=6)


class HealthResponse(StrictModel):
    status: Literal["ok"]
    service: str
    version: str
    parser_provider: ParseProvider
