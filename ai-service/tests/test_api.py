from __future__ import annotations

from datetime import date, timedelta


def _history(days: int = 20) -> list[dict[str, object]]:
    start = date(2026, 8, 1)
    return [
        {"date": (start + timedelta(days=index)).isoformat(), "quantity": (index % 5) + 1}
        for index in range(days)
    ]


def test_health_does_not_require_token(client):
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json()["status"] == "ok"
    assert response.json()["parser_provider"] == "rule"


def test_protected_endpoints_require_token(client):
    response = client.post("/api/v1/parse", json={"text": "采购物料 M-100 10 个"})
    assert response.status_code == 401
    assert response.json()["detail"]["code"] == "AUTH_REQUIRED"

    response = client.post(
        "/api/v1/forecast",
        json={"material_code": "M-100", "history": _history()},
        headers={"X-Service-Token": "wrong"},
    )
    assert response.status_code == 403
    assert response.json()["detail"]["code"] == "AUTH_INVALID"


def test_internal_prefix_keeps_same_parse_contract(client, service_headers):
    response = client.post(
        "/internal/v1/parse",
        json={"text": "采购物料 M-100 10 个，2026-09-01 到货"},
        headers=service_headers,
    )
    assert response.status_code == 200
    body = response.json()
    assert body["provider"] == "rule"
    assert body["requires_confirmation"] is True
    assert "confidence_note" in body


def test_parse_rejects_unknown_fields(client, service_headers):
    response = client.post(
        "/api/v1/parse",
        json={"text": "采购物料 M-100 10 个", "unexpected": True},
        headers=service_headers,
    )
    assert response.status_code == 422
    assert response.json()["detail"]["code"] == "VALIDATION_ERROR"


def test_forecast_returns_fixed_fourteen_day_sequence(client, service_headers):
    response = client.post(
        "/api/v1/forecast",
        json={"material_code": "M-100", "lead_time": 7, "history": _history()},
        headers=service_headers,
    )
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["horizon"] == 14
    assert len(body["sequence"]) == 14
    assert body["model"] == "ma7"
    assert body["fallback_reason"]
    assert body["metrics"]["mape_definition"]


def test_forecast_lead_time_over_horizon_has_stable_error(client, service_headers):
    response = client.post(
        "/api/v1/forecast",
        json={"material_code": "M-100", "lead_time": 15, "history": _history()},
        headers=service_headers,
    )
    assert response.status_code == 422
    assert response.json()["detail"]["code"] == "HORIZON_INSUFFICIENT"


def test_analysis_report_endpoint_requires_strict_grounded_snapshot(client, service_headers):
    payload = {
        "as_of_time": "2026-09-21T12:00:00+08:00",
        "timezone": "Asia/Shanghai",
        "scope": "企业全局供应链业务库，只读统计快照",
        "data_fingerprint": "b" * 64,
        "metrics": {
            "active_orders": 1, "overdue_orders": 0, "inventory_shortages": 0,
            "open_warnings": 0, "high_warnings": 0, "pending_plans": 0,
            "pending_reconciliations": 0, "reconciliation_difference_amount": 0,
            "rejected_quantity": 0, "active_suppliers": 1, "average_on_time_rate": 0.95,
        },
        "top_risks": [],
    }
    response = client.post("/internal/v1/analysis-report", json=payload, headers=service_headers)
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["provider"] == "rule"
    assert len(body["sections"]) == 7
    assert body["priority_actions"][0]["code"] == "MONITOR_OPERATIONS"
