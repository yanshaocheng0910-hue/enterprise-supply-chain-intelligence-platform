"""Grounded, read-only supply-chain operating analysis.

The deterministic report owns every number and every action link.  A local
OpenAI-compatible model may add qualitative commentary, but provider text is
rejected if it introduces digits or changes the closed seven-section schema.
"""

from __future__ import annotations

import json
from typing import Literal

import httpx
from pydantic import BaseModel, ConfigDict, Field

from .config import Settings
from .models import AnalysisAction, AnalysisReportRequest, AnalysisReportResponse, AnalysisSection
from .parsing import _content_from_provider, _provider_endpoint


PROMPT_VERSION = "analysis-v2-local-rank-rule-facts"
RULE_MODEL_NAME = "supply-chain-rule-report-v1"

SECTION_TITLES = {
    "executive_summary": "经营摘要",
    "demand_inventory": "需求与库存",
    "supplier_fulfillment": "供应商与履约",
    "reconciliation_finance": "对账与资金风险",
    "warning_risk": "异常与预警",
    "recommendations": "处置建议",
    "boundary": "分析边界",
}


class _ProviderDecision(BaseModel):
    model_config = ConfigDict(extra="forbid")
    summary_tone: Literal["stable", "attention", "critical"]
    focus_order: list[str] = Field(min_length=1, max_length=7)


def _actions(request: AnalysisReportRequest) -> list[AnalysisAction]:
    m = request.metrics
    actions: list[AnalysisAction] = []
    if m.high_warnings:
        actions.append(AnalysisAction(code="RESOLVE_HIGH_WARNINGS", level="HIGH", title="优先处置高等级预警", rationale=f"当前有 {m.high_warnings} 条高等级未关闭预警，应先确认责任人与处置时限。", route="/warnings"))
    if m.overdue_orders:
        actions.append(AnalysisAction(code="EXPEDITE_OVERDUE_ORDERS", level="HIGH", title="核查逾期订单交期", rationale=f"当前有 {m.overdue_orders} 笔逾期未完结订单，需与供应商确认最新到货承诺。", route="/orders"))
    if m.inventory_shortages:
        actions.append(AnalysisAction(code="REPLENISH_SHORTAGE", level="HIGH", title="处理安全库存缺口", rationale=f"当前有 {m.inventory_shortages} 项库存低于安全库存，应结合在途与预测确定补货优先级。", route="/inventory"))
    if m.reconciliation_difference_amount > 0:
        actions.append(AnalysisAction(code="REVIEW_RECON_DIFFERENCE", level="MEDIUM", title="复核未结对账差异", rationale=f"未闭环对账绝对差异合计 {m.reconciliation_difference_amount:.2f} 元，需核对收货数量和单价。", route="/reconciliation"))
    if m.pending_plans:
        actions.append(AnalysisAction(code="APPROVE_PENDING_PLANS", level="MEDIUM", title="推进待审批采购计划", rationale=f"当前有 {m.pending_plans} 个采购计划待审批，避免审批等待扩大交付风险。", route="/purchase-plans"))
    if not actions:
        actions.append(AnalysisAction(code="MONITOR_OPERATIONS", level="LOW", title="保持日常监测", rationale="当前关键规则指标未发现需立即升级处置的事项，建议按业务节奏持续复核。", route="/dashboard"))
    return actions[:6]


def _rule_sections(request: AnalysisReportRequest) -> list[AnalysisSection]:
    m = request.metrics
    risk_labels = "、".join(item.label for item in request.top_risks[:3]) or "当前无未关闭重点预警"
    contents = {
        "executive_summary": f"截至统计时点，系统记录 {m.active_orders} 笔执行中订单、{m.open_warnings} 条未闭环预警和 {m.pending_reconciliations} 笔待闭环对账。建议按高等级预警、逾期交付、库存缺口的顺序组织处置。",
        "demand_inventory": f"共有 {m.inventory_shortages} 项库存低于安全库存。应结合十四天需求预测、现有可用量与在途数量逐项核实，避免只按当前库存直接下单。",
        "supplier_fulfillment": f"当前活跃供应商 {m.active_suppliers} 家，平均准时交付率为 {m.average_on_time_rate * 100:.2f}%；执行中订单 {m.active_orders} 笔，其中逾期 {m.overdue_orders} 笔。",
        "reconciliation_finance": f"待闭环对账 {m.pending_reconciliations} 笔，绝对差异金额合计 {m.reconciliation_difference_amount:.2f} 元；历史验收不合格数量合计 {m.rejected_quantity:.2f}。差异必须回到订单、收货与验收明细复核。",
        "warning_risk": f"当前未闭环预警 {m.open_warnings} 条，其中高等级 {m.high_warnings} 条。重点事项：{risk_labels}。预警结论来自规则记录，不代表已完成业务处置。",
        "recommendations": "先关闭高等级预警，再处理逾期订单和安全库存缺口；对账差异须保留双方确认及处理记录。任何建议均需由有权限人员在原业务单据中确认。",
        "boundary": "本报告仅基于生成时点的数据库统计快照，不读取数据库之外的合同、发票或沟通记录；AI 只提供只读辅助分析，不会自动审批、下单、收货、入库或确认对账。",
    }
    return [AnalysisSection(key=key, title=title, content=contents[key]) for key, title in SECTION_TITLES.items()]


async def _provider_sections(request: AnalysisReportRequest, settings: Settings) -> list[AnalysisSection]:
    facts = request.model_dump(mode="json")
    system_prompt = (
        "你是企业供应链风险排序器，只能在闭集标签中选择，不撰写报告、不执行业务。"
        "输出必须是 JSON 对象且只能包含 summary_tone 和 focus_order。"
        "summary_tone 只能是 stable、attention、critical：高等级预警或逾期订单大于零为 critical；"
        "否则库存缺口、未闭环预警、待闭环对账或待审批计划任一大于零为 attention；其余为 stable。"
        "focus_order 必须恰好包含以下七个键且不得重复：executive_summary、demand_inventory、"
        "supplier_fulfillment、reconciliation_finance、warning_risk、recommendations、boundary。"
        "executive_summary 必须第一，boundary 必须最后；中间章节按当前事实的重要性排序。"
        "不得输出任何解释、数字、Markdown 或额外字段。"
    )
    body = {
        "model": settings.openai_model,
        "temperature": 0,
        "max_tokens": settings.openai_max_tokens,
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": json.dumps(facts, ensure_ascii=False)},
        ],
        "response_format": {"type": "json_object"},
    }
    headers = {"Authorization": f"Bearer {settings.openai_api_key}", "Content-Type": "application/json"}
    async with httpx.AsyncClient(timeout=settings.openai_timeout_seconds, trust_env=False) as client:
        response = await client.post(_provider_endpoint(settings.openai_base_url or ""), headers=headers, json=body)
    response.raise_for_status()
    parsed = _ProviderDecision.model_validate(json.loads(_content_from_provider(response.json())))
    expected_keys = set(SECTION_TITLES)
    if any(key not in expected_keys for key in parsed.focus_order):
        raise ValueError("provider focus order contains an unknown section")
    middle_default = [
        "demand_inventory", "supplier_fulfillment", "reconciliation_finance",
        "warning_risk", "recommendations",
    ]
    provider_middle = []
    for key in parsed.focus_order:
        if key in middle_default and key not in provider_middle:
            provider_middle.append(key)
    if len(provider_middle) < 3:
        raise ValueError("provider did not produce enough controlled ranking evidence")
    # The small local model may omit a boundary or misclassify the aggregate
    # tone.  Neither is trusted: report boundaries and severity remain fully
    # deterministic, while only the relative order of known middle sections
    # is retained from the model.
    ordered_middle = provider_middle + [key for key in middle_default if key not in provider_middle]
    focus_order = ["executive_summary", *ordered_middle, "boundary"]
    controlled = {section.key: section for section in _rule_sections(request)}
    return [controlled[key] for key in focus_order]


async def generate_analysis_report(request: AnalysisReportRequest, settings: Settings) -> AnalysisReportResponse:
    actions = _actions(request)
    warnings = ["报告只读，不会自动改变任何业务单据", "精确数值以页面事实快照为准"]
    if settings.provider_configured:
        try:
            sections = await _provider_sections(request, settings)
            return AnalysisReportResponse(provider="openai-compatible", model_name=settings.openai_model or "openai-compatible-unknown", prompt_version=PROMPT_VERSION, warnings=warnings, sections=sections, priority_actions=actions)
        except (httpx.HTTPError, json.JSONDecodeError, ValueError, TypeError) as exc:
            reason = f"{type(exc).__name__}: 本地模型结果不可用或未通过事实边界校验"
            return AnalysisReportResponse(provider="rule", model_name=RULE_MODEL_NAME, prompt_version=PROMPT_VERSION, fallback_reason=reason, warnings=[f"本地模型调用失败，已使用可复现规则报告（{reason}）", *warnings], sections=_rule_sections(request), priority_actions=actions)
    reason = settings.provider_configuration_warning
    return AnalysisReportResponse(provider="rule", model_name=RULE_MODEL_NAME, prompt_version=PROMPT_VERSION, fallback_reason=reason, warnings=([reason] if reason else []) + warnings, sections=_rule_sections(request), priority_actions=actions)
