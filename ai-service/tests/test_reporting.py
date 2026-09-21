from __future__ import annotations

import pytest
import json

from app.config import Settings
from app.models import AnalysisReportRequest
from app.reporting import generate_analysis_report


class _Response:
    def raise_for_status(self):
        return None

    def json(self):
        return {"choices": [{"message": {"content": json.dumps({
            "summary_tone": "critical",
            "focus_order": [
                "executive_summary", "warning_risk", "supplier_fulfillment",
                "demand_inventory", "reconciliation_finance", "recommendations", "boundary",
            ],
        })}}]}


class _Client:
    async def __aenter__(self):
        return self

    async def __aexit__(self, *_):
        return None

    async def post(self, *_args, **_kwargs):
        return _Response()


def _request() -> AnalysisReportRequest:
    return AnalysisReportRequest.model_validate({
        "as_of_time": "2026-09-21T12:00:00+08:00",
        "timezone": "Asia/Shanghai",
        "scope": "企业全局供应链业务库，只读统计快照",
        "data_fingerprint": "a" * 64,
        "metrics": {
            "active_orders": 5,
            "overdue_orders": 2,
            "inventory_shortages": 3,
            "open_warnings": 4,
            "high_warnings": 1,
            "pending_plans": 2,
            "pending_reconciliations": 1,
            "reconciliation_difference_amount": 120.5,
            "rejected_quantity": 8,
            "active_suppliers": 4,
            "average_on_time_rate": 0.92,
        },
        "top_risks": [{
            "kind": "DELIVERY_DELAY",
            "label": "订单延期风险",
            "severity": "HIGH",
            "created_at": "2026-09-21T10:00:00+08:00",
        }],
    })


@pytest.mark.asyncio
async def test_rule_report_is_complete_readonly_and_actionable():
    result = await generate_analysis_report(
        _request(), Settings(service_token="test-service-token", llm_provider="rule")
    )
    assert result.provider == "rule"
    assert result.prompt_version == "analysis-v2-local-rank-rule-facts"
    assert len(result.sections) == 7
    assert {section.key for section in result.sections} == {
        "executive_summary", "demand_inventory", "supplier_fulfillment",
        "reconciliation_finance", "warning_risk", "recommendations", "boundary",
    }
    assert result.priority_actions[0].route == "/warnings"
    assert any("不会自动" in warning for warning in result.warnings)


@pytest.mark.asyncio
async def test_remote_model_configuration_stays_on_rule_path():
    result = await generate_analysis_report(
        _request(),
        Settings(
            service_token="test-service-token",
            llm_provider="openai-compatible",
            openai_base_url="https://api.example.com/v1",
            openai_api_key="secret",
            openai_model="remote-model",
            llm_local_only=True,
        ),
    )
    assert result.provider == "rule"
    assert "LLM" in (result.fallback_reason or "")


@pytest.mark.asyncio
async def test_local_provider_can_only_reorder_controlled_rule_sections(monkeypatch):
    monkeypatch.setattr("app.reporting.httpx.AsyncClient", lambda **_: _Client())
    result = await generate_analysis_report(
        _request(),
        Settings(
            service_token="test-service-token",
            llm_provider="openai-compatible",
            openai_base_url="http://127.0.0.1:11435/v1",
            openai_api_key="local",
            openai_model="qwen2.5:1.5b",
            llm_local_only=True,
        ),
    )
    assert result.provider == "openai-compatible"
    assert result.sections[1].key == "warning_risk"
    assert "1 条" in result.sections[1].content
