from __future__ import annotations

import pytest

from app.config import Settings
from app.models import ParseRequest
from app.parsing import UnsupportedIntentError, parse_text


class _ProviderResponse:
    def __init__(self, payload: dict):
        self._payload = payload

    def raise_for_status(self):
        return None

    def json(self):
        return self._payload


class _ProviderClient:
    def __init__(self, payload: dict):
        self._payload = payload
        self.request_json = None

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_):
        return None

    async def post(self, _url, *, headers, json):
        assert headers["Authorization"] == "Bearer local-test-key"
        self.request_json = json
        return _ProviderResponse(self._payload)


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
    assert result.model_name == "deterministic-rule-v1"
    assert result.prompt_version == "parse-v3-intent-schema-guarded"


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
    assert "ship_date" not in delivery.fields

    set_quantity = await parse_text(
        ParseRequest(text="计划号 PL-02 数量改为 100"),
        settings,
    )
    assert set_quantity.intent == "plan_change"
    assert set_quantity.fields["change_type"] == "set_quantity"
    assert set_quantity.fields["new_quantity"] == 100.0

    notified = await parse_text(
        ParseRequest(text="采购订单 PO-02 到货通知：30 台预计 2026年10月28日送达。"),
        settings,
    )
    assert notified.fields["order_no"] == "PO-02"
    assert notified.fields["delivery_status"] == "notified"

    chinese_quantity = await parse_text(
        ParseRequest(text="采购物料名称 温度传感器T20 二百只，2026年10月18日前到货。"),
        settings,
    )
    assert chinese_quantity.fields["quantity"] == 200.0
    assert chinese_quantity.fields["unit"] == "只"


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


@pytest.mark.asyncio
async def test_openai_compatible_provider_is_labelled_and_bounded(monkeypatch):
    payload = {
        "choices": [{
            "message": {
                "content": '{"intent":"purchase_demand","fields":{"material_code":"MAT-BOX-05","quantity":120,"required_date":"2026-10-01"},"warnings":[],"confidence":0.91}'
            }
        }]
    }
    client = _ProviderClient(payload)
    client_options = {}

    def make_client(**kwargs):
        client_options.update(kwargs)
        return client

    monkeypatch.setattr("app.parsing.httpx.AsyncClient", make_client)
    result = await parse_text(
        ParseRequest(text="请采购 MAT-BOX-05 120 件，要求 2026-10-01 前到货", intent_hint="purchase_demand"),
        Settings(
            service_token="test-service-token",
            llm_provider="openai-compatible",
            openai_base_url="http://127.0.0.1:11434/v1",
            openai_api_key="local-test-key",
            openai_model="qwen2.5:1.5b",
            openai_max_tokens=384,
        ),
    )
    assert result.provider == "openai-compatible"
    assert result.model_name == "qwen2.5:1.5b"
    assert result.prompt_version == "parse-v3-intent-schema-guarded"
    assert result.fallback_reason is None
    assert result.fields["material_code"] == "MAT-BOX-05"
    assert client.request_json["max_tokens"] == 384
    assert client.request_json["response_format"] == {"type": "json_object"}
    assert client_options["trust_env"] is False


@pytest.mark.asyncio
async def test_provider_intent_mismatch_falls_back_without_false_llm_claim(monkeypatch):
    payload = {
        "choices": [{
            "message": {
                "content": '{"intent":"delivery_notice","fields":{"order_no":"PO-01","eta":"2026-10-01"}}'
            }
        }]
    }
    monkeypatch.setattr("app.parsing.httpx.AsyncClient", lambda **_: _ProviderClient(payload))
    result = await parse_text(
        ParseRequest(text="请采购物料编码 MAT-BOX-05 120 件，2026-10-01 前到货", intent_hint="purchase_demand"),
        Settings(
            service_token="test-service-token",
            llm_provider="openai-compatible",
            openai_base_url="http://127.0.0.1:11434/v1",
            openai_api_key="local-test-key",
            openai_model="qwen2.5:1.5b",
        ),
    )
    assert result.provider == "rule"
    assert result.model_name == "deterministic-rule-v1"
    assert "意图与受控任务不一致" in (result.fallback_reason or "")
    assert any("已降级为规则解析" in warning for warning in result.warnings)


@pytest.mark.asyncio
async def test_provider_flat_fields_are_rejected_and_labelled_as_rule_fallback(monkeypatch):
    payload = {
        "choices": [{
            "message": {
                "content": '{"intent":"purchase_demand","material_code":"MAT-BOX-05","quantity":120}'
            }
        }]
    }
    monkeypatch.setattr("app.parsing.httpx.AsyncClient", lambda **_: _ProviderClient(payload))
    result = await parse_text(
        ParseRequest(
            text="请采购物料编码 MAT-BOX-05 120 件，2026-10-01 前到货",
            intent_hint="purchase_demand",
        ),
        Settings(
            service_token="test-service-token",
            llm_provider="openai-compatible",
            openai_base_url="http://127.0.0.1:11434/v1",
            openai_api_key="local-test-key",
            openai_model="qwen2.5:1.5b",
        ),
    )
    assert result.provider == "rule"
    assert "顶层结果包含不允许的字段" in (result.fallback_reason or "")


@pytest.mark.asyncio
async def test_provider_null_strings_and_placeholders_are_not_accepted_as_facts(monkeypatch):
    payload = {
        "choices": [{
            "message": {
                "content": '{"intent":"purchase_demand","fields":{"material_code":"null","material_name":"物料名称","supplier_name":"供应商","quantity":120,"required_date":"2026-10-01"},"warnings":[],"confidence":0.8}'
            }
        }]
    }
    monkeypatch.setattr("app.parsing.httpx.AsyncClient", lambda **_: _ProviderClient(payload))
    result = await parse_text(
        ParseRequest(text="请采购 120 件，2026-10-01 前到货", intent_hint="purchase_demand"),
        Settings(
            service_token="test-service-token",
            llm_provider="openai-compatible",
            openai_base_url="http://127.0.0.1:11434/v1",
            openai_api_key="local-test-key",
            openai_model="qwen2.5:1.5b",
        ),
    )
    assert result.provider == "openai-compatible"
    assert "material_code" not in result.fields
    assert "material_name" not in result.fields
    assert "supplier_name" not in result.fields
    assert "material_code_or_name" in result.missing_fields


@pytest.mark.asyncio
async def test_provider_plan_fields_are_grounded_to_original_text(monkeypatch):
    payload = {
        "choices": [{
            "message": {
                "content": '{"intent":"plan_change","fields":{"plan_no":"PL-01","material_code":"MAT-BOX-05","change_type":"increase","new_quantity":300,"quantity_delta":-300,"required_date":"2026-09-20","reason":"客户需求增加"},"warnings":[],"confidence":0.9}'
            }
        }]
    }
    monkeypatch.setattr("app.parsing.httpx.AsyncClient", lambda **_: _ProviderClient(payload))
    result = await parse_text(
        ParseRequest(
            text="请将计划号 PL-01 中物料 MAT-BOX-05 的数量调整为 300 个，原因是客户需求增加。",
            intent_hint="plan_change",
        ),
        Settings(
            service_token="test-service-token",
            llm_provider="openai-compatible",
            openai_base_url="http://127.0.0.1:11435/v1",
            openai_api_key="local-test-key",
            openai_model="qwen2.5:1.5b",
        ),
    )
    assert result.provider == "openai-compatible"
    assert result.fields["change_type"] == "set_quantity"
    assert result.fields["new_quantity"] == 300.0
    assert "quantity_delta" not in result.fields
    assert "required_date" not in result.fields
    assert result.fields["reason"] == "客户需求增加"
    assert any("原文证据校正或移除" in warning for warning in result.warnings)


@pytest.mark.asyncio
async def test_remote_provider_is_blocked_by_local_only_policy():
    settings = Settings(
        service_token="test-service-token",
        llm_provider="openai-compatible",
        openai_base_url="https://external.example.com/v1",
        openai_api_key="must-not-be-used",
        openai_model="external-model",
        llm_local_only=True,
    )
    result = await parse_text(
        ParseRequest(
            text="请采购物料编码 MAT-BOX-05 120 个，2026-10-01 前到货",
            intent_hint="purchase_demand",
        ),
        settings,
    )
    assert settings.provider_configured is False
    assert result.provider == "rule"
    assert any("安全策略已拒绝外部 LLM 地址" in warning for warning in result.warnings)
