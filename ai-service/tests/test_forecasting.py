from __future__ import annotations

import math
from datetime import date, timedelta

from app.forecasting import forecast
from app.models import ForecastRequest


def test_ma7_forecast_is_recursive_and_non_negative():
    start = date(2026, 1, 1)
    request = ForecastRequest(
        material_code="M-100",
        history=[
            {"date": (start + timedelta(days=i)).isoformat(), "quantity": 7 if i == 0 else 0}
            for i in range(8)
        ],
    )
    result = forecast(request)
    assert result["model"] == "ma7"
    assert len(result["sequence"]) == 14
    assert all(point["forecast"] >= 0 for point in result["sequence"])


def test_duplicate_dates_are_aggregated_before_forecast():
    request = ForecastRequest(
        material_code="M-200",
        history=[
            {"date": "2026-01-01", "quantity": 2},
            {"date": "2026-01-01", "quantity": 3},
        ],
    )
    result = forecast(request)
    assert result["sequence"][0]["forecast"] == 5


def test_qualified_history_keeps_reproducible_model_selection_evidence():
    start = date(2025, 8, 24)
    history = []
    for day in range(365):
        base = 52.0
        weekly = math.sin((day % 7) * math.pi / 3.5) * base * 0.16
        monthly = math.sin(day * math.pi / 15.0) * base * 0.08
        trend = day * 0.025
        campaign = base * 0.45 if 78 <= day % 91 <= 82 else 0.0
        noise = ((day * 37 + 19) % 17 - 8) * base * 0.009
        history.append({"date": (start + timedelta(days=day)).isoformat(), "quantity": max(0.0, base + weekly + monthly + trend + campaign + noise)})

    result = forecast(ForecastRequest(material_code="MAT-TEST-XGB", lead_time=7, history=history))

    assert len(result["sequence"]) == 14
    assert all(math.isfinite(point["forecast"]) and point["forecast"] >= 0 for point in result["sequence"])
    assert result["evaluation"]["selection"]["selected_model"] == result["model"]
    if result["model"] == "xgboost":
        assert len(result["evaluation"]["expanding_windows"]) == 3
        assert result["evaluation"]["selection"]["xgboost_wins"] >= 2
        assert result["evaluation"]["selection"]["average_validation_mae_xgboost"] < result["evaluation"]["selection"]["average_validation_mae_ma7"]
    else:
        # The project remains runnable without the optional wheel, but the
        # reason must be explicit and the selected baseline must be honest.
        assert result["fallback_reason"]
