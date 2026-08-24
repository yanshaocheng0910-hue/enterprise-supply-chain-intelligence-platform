from __future__ import annotations

import pytest

from app.config import Settings
from app.models import ParseRequest
from app.parsing import UnsupportedIntentError, parse_text


@pytest.mark.asyncio
async def test_rule_parser_extracts_purchase_fields():
    result = await parse_text(
        ParseRequest(text="请采购物料编码 M-100 120 个，2026-09-01 前到货，供应商为 S-01"),
        Settings(service_token="test-service-token", llm_provider="rule"),
    )
    assert result.provider == "rule"
    assert result.intent == "purchase_demand"
    assert result.fields["material_code"] == "M-100"
    assert result.fields["quantity"] == 120.0
    assert result.fields["required_date"] == "2026-09-01"
    assert result.requires_confirmation is True


@pytest.mark.asyncio
async def test_rule_parser_supports_plan_change_and_delivery_notice():
    settings = Settings(service_token="test-service-token", llm_provider="rule")
    plan = await parse_text(
        ParseRequest(text="计划号 PL-01 延期到 2026-09-10"),
        settings,
    )
    assert plan.intent == "plan_change"
    assert plan.fields["plan_no"] == "PL-01"
    assert plan.fields["change_type"] == "reschedule"
    assert "required_date" not in plan.missing_fields

    delivery = await parse_text(
        ParseRequest(text="订单 PO-01 已发货 80 箱，预计 2026-09-03 到货，运单号 YD-1"),
        settings,
    )
    assert delivery.intent == "delivery_notice"
    assert delivery.fields["order_no"] == "PO-01"
    assert delivery.fields["quantity"] == 80.0
    assert delivery.fields["eta"] == "2026-09-03"

    set_quantity = await parse_text(
        ParseRequest(text="计划号 PL-02 数量改为 100"),
        settings,
    )
    assert set_quantity.intent == "plan_change"
    assert set_quantity.fields["change_type"] == "set_quantity"
    assert set_quantity.fields["new_quantity"] == 100.0


@pytest.mark.asyncio
async def test_rule_parser_reports_missing_fields_and_never_claims_llm():
    result = await parse_text(
        ParseRequest(text="请采购一些物料"),
        Settings(service_token="test-service-token", llm_provider="rule"),
    )
    assert result.provider == "rule"
    assert "material_code_or_name" in result.missing_fields
    assert "quantity" in result.missing_fields
    assert "required_date" in result.missing_fields
    assert any("未配置" in warning for warning in result.warnings)


@pytest.mark.asyncio
async def test_unsupported_text_is_not_falsely_classified():
    with pytest.raises(UnsupportedIntentError):
        await parse_text(
            ParseRequest(text="帮我查一下发票税务状态"),
            Settings(service_token="test-service-token", llm_provider="rule"),
        )
