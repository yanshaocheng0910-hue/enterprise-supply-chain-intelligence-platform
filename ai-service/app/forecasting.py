"""14-day recursive demand forecasting.

The implementation keeps the baseline (MA7) first-class and treats XGBoost as
an optional, per-material model.  All splits are chronological; rolling
features use only values before the prediction timestamp.
"""

from __future__ import annotations

import math
from datetime import date, timedelta
from typing import Any, Iterable

import numpy as np

from .models import DemandPoint, ForecastRequest


class ForecastError(ValueError):
    def __init__(self, code: str, message: str, status_code: int = 422) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.status_code = status_code


def _metric_result(actual: Iterable[float], predicted: Iterable[float]) -> dict[str, Any]:
    y_true = np.asarray(list(actual), dtype=float)
    y_pred = np.asarray(list(predicted), dtype=float)
    if y_true.size == 0:
        return {
            "mae": None,
            "rmse": None,
            "mape": None,
            "sample_count": 0,
            "nonzero_actual_count": 0,
            "mape_definition": "仅在真实需求大于 0 的样本上计算，真实需求为 0 的样本不进入 MAPE 分母",
        }
    errors = y_pred - y_true
    nonzero = y_true > 0
    mape = None
    if np.any(nonzero):
        mape = float(np.mean(np.abs(errors[nonzero] / y_true[nonzero])) * 100)
    return {
        "mae": float(np.mean(np.abs(errors))),
        "rmse": float(np.sqrt(np.mean(np.square(errors)))),
        "mape": mape,
        "sample_count": int(y_true.size),
        "nonzero_actual_count": int(np.sum(nonzero)),
        "mape_definition": "仅在真实需求大于 0 的样本上计算，真实需求为 0 的样本不进入 MAPE 分母",
    }


def _daily_values(history: list[DemandPoint], as_of: date | None) -> tuple[date, list[float], int, int, int]:
    grouped: dict[date, float] = {}
    for point in history:
        grouped[point.date] = grouped.get(point.date, 0.0) + float(point.quantity)
    if not grouped:
        raise ForecastError("HISTORY_EMPTY", "history 至少需要一条有效需求记录")
    max_date = max(grouped)
    effective_days = len(grouped)
    nonzero_days = sum(1 for value in grouped.values() if value > 0)
    cutoff = as_of or max_date
    if cutoff < max_date:
        raise ForecastError("AS_OF_BEFORE_HISTORY", "as_of_date 不能早于 history 中的最新日期")
    start = min(grouped)
    day_count = (cutoff - start).days + 1
    if day_count > 20_000:
        raise ForecastError("HISTORY_TOO_WIDE", "history 的日期跨度不能超过 20000 天")
    values = [float(grouped.get(start + timedelta(days=i), 0.0)) for i in range(day_count)]
    return start, values, effective_days, nonzero_days, day_count


def _ma7_prediction(values: list[float]) -> float:
    window = values[-7:]
    return max(0.0, float(np.mean(window))) if window else 0.0


def _feature_vector(values_before: list[float], target_date: date) -> list[float]:
    """Build lag/rolling/calendar features using only values before target."""

    if len(values_before) < 28:
        raise ValueError("feature requires 28 prior observations")
    arr = np.asarray(values_before, dtype=float)

    def lag(days: int) -> float:
        return float(arr[-days])

    def rolling_mean(days: int) -> float:
        return float(np.mean(arr[-days:]))

    def rolling_std(days: int) -> float:
        return float(np.std(arr[-days:], ddof=0))

    return [
        lag(1),
        lag(7),
        lag(14),
        lag(28),
        rolling_mean(7),
        rolling_mean(14),
        rolling_mean(28),
        rolling_std(7),
        rolling_std(14),
        rolling_std(28),
        float(target_date.weekday()),
        float(target_date.day),
        float(target_date.month),
        float(target_date.isocalendar().week),
    ]


def _feature_matrix(start: date, values: list[float]) -> tuple[np.ndarray, np.ndarray, list[date]]:
    features: list[list[float]] = []
    targets: list[float] = []
    dates: list[date] = []
    for index in range(28, len(values)):
        target_date = start + timedelta(days=index)
        try:
            features.append(_feature_vector(values[:index], target_date))
        except ValueError:
            continue
        targets.append(float(values[index]))
        dates.append(target_date)
    return np.asarray(features, dtype=float), np.asarray(targets, dtype=float), dates


def _load_xgb() -> Any | None:
    try:
        from xgboost import XGBRegressor  # type: ignore

        return XGBRegressor
    except Exception:
        return None


def _new_xgb(XGBRegressor: Any) -> Any:
    return XGBRegressor(
        n_estimators=240,
        max_depth=4,
        learning_rate=0.05,
        min_child_weight=1,
        subsample=0.9,
        colsample_bytree=0.9,
        objective="reg:squarederror",
        random_state=42,
        n_jobs=1,
    )


def _ma7_test_metrics(values: list[float], test_start: int) -> dict[str, Any]:
    actual: list[float] = []
    predicted: list[float] = []
    for index in range(max(1, test_start), len(values)):
        actual.append(values[index])
        predicted.append(_ma7_prediction(values[:index]))
    return _metric_result(actual, predicted)


def _recursive_ma7(start_date: date, as_of: date, values: list[float]) -> list[dict[str, Any]]:
    work = list(values)
    sequence: list[dict[str, Any]] = []
    for step in range(1, 15):
        value = _ma7_prediction(work)
        target_date = as_of + timedelta(days=step)
        sequence.append({"date": target_date, "forecast": round(value, 6)})
        work.append(value)
    return sequence


def _xgb_computation(
    start_date: date,
    as_of: date,
    values: list[float],
) -> tuple[list[dict[str, Any]], dict[str, Any], dict[str, Any], str, str | None, list[str]]:
    XGBRegressor = _load_xgb()
    if XGBRegressor is None:
        raise ForecastError("XGBOOST_UNAVAILABLE", "当前环境未安装 XGBoost")
    X, y, _ = _feature_matrix(start_date, values)
    sample_count = len(y)
    if sample_count < 3:
        raise ForecastError("XGBOOST_DATA_INSUFFICIENT", "特征样本不足以进行时间切分")
    train_end = max(1, int(math.floor(sample_count * 0.70)))
    validation_end = max(train_end + 1, int(math.floor(sample_count * 0.85)))
    validation_end = min(sample_count - 1, validation_end)
    test_start = validation_end

    # Three expanding windows in the validation segment.  For a short 15%
    # validation segment the windows overlap, which preserves the requested
    # three 14-day evaluations without borrowing from the sealed test set.
    val_span = validation_end - train_end
    if val_span < 14:
        raise ForecastError("XGBOOST_VALIDATION_INSUFFICIENT", "验证区间不足以形成三个 14 日扩展窗")
    latest_start = validation_end - 14
    starts = sorted({int(round(value)) for value in np.linspace(train_end, latest_start, num=3)})
    while len(starts) < 3:
        candidate = starts[-1] - 1 if starts else train_end
        if candidate < train_end or candidate in starts:
            break
        starts.append(candidate)
        starts = sorted(starts)
    if len(starts) != 3:
        raise ForecastError("XGBOOST_VALIDATION_INSUFFICIENT", "验证区间不足以形成三个 14 日扩展窗")

    windows: list[dict[str, Any]] = []
    xgb_window_metrics: list[dict[str, Any]] = []
    ma7_window_metrics: list[dict[str, Any]] = []
    for index, window_start in enumerate(starts, start=1):
        window_end = window_start + 14
        model = _new_xgb(XGBRegressor)
        model.fit(X[:window_start], y[:window_start])
        predictions = np.maximum(0.0, np.asarray(model.predict(X[window_start:window_end]), dtype=float))
        xgb_metrics = _metric_result(y[window_start:window_end], predictions)
        # The same 14 validation dates are scored with a leakage-free MA7
        # baseline so model selection is evidence-based rather than assumed.
        ma7_predictions = [
            _ma7_prediction(values[: sample_index + 28])
            for sample_index in range(window_start, window_end)
        ]
        ma7_metrics = _metric_result(y[window_start:window_end], ma7_predictions)
        xgb_window_metrics.append(xgb_metrics)
        ma7_window_metrics.append(ma7_metrics)
        windows.append(
            {
                "window": index,
                "train_end_index": window_start,
                "validation_start_index": window_start,
                "validation_end_index": window_end,
                "metrics": xgb_metrics,
                "xgboost_metrics": xgb_metrics,
                "ma7_metrics": ma7_metrics,
            }
        )

    average_xgb_mae = float(np.mean([item["mae"] for item in xgb_window_metrics]))
    average_ma7_mae = float(np.mean([item["mae"] for item in ma7_window_metrics]))
    xgb_wins = sum(
        1
        for xgb_metric, ma7_metric in zip(xgb_window_metrics, ma7_window_metrics)
        if xgb_metric["mae"] < ma7_metric["mae"]
    )
    select_xgb = average_xgb_mae < average_ma7_mae and xgb_wins >= 2
    selected_model = "xgboost" if select_xgb else "ma7"
    selection = {
        "average_validation_mae_xgboost": average_xgb_mae,
        "average_validation_mae_ma7": average_ma7_mae,
        "xgboost_wins": xgb_wins,
        "required_wins": 2,
        "selected_model": selected_model,
        "rule": "仅当 XGBoost 平均验证 MAE 低于 MA7 且三个 14 日扩展窗至少赢两折时选用 XGBoost",
    }
    postprocessing = {
        "nonnegative_clamp": True,
        "rounding_decimals": 6,
        "recursive": True,
        "explanation": "预测值经过非负截断并保留 6 位小数；未来每一步将前一步预测写回序列",
    }

    fallback_reason: str | None = None
    warnings: list[str] = []
    if select_xgb:
        final_model = _new_xgb(XGBRegressor)
        final_model.fit(X[:test_start], y[:test_start])
        test_predictions = np.maximum(0.0, np.asarray(final_model.predict(X[test_start:]), dtype=float))
        test_metrics = _metric_result(y[test_start:], test_predictions)
        work = list(values)
        sequence: list[dict[str, Any]] = []
        for step in range(1, 15):
            target_date = as_of + timedelta(days=step)
            prediction = float(final_model.predict(np.asarray([_feature_vector(work, target_date)], dtype=float))[0])
            if not math.isfinite(prediction):
                raise ForecastError("XGBOOST_NONFINITE", "XGBoost 产生了非有限预测值")
            prediction = max(0.0, prediction)
            sequence.append({"date": target_date, "forecast": round(prediction, 6)})
            work.append(prediction)
    else:
        sequence = _recursive_ma7(start_date, as_of, values)
        test_start_ma7 = max(1, int(len(values) * 0.85))
        test_metrics = _ma7_test_metrics(values, test_start_ma7)
        fallback_reason = (
            "XGBoost 未满足选模门槛：平均验证 MAE "
            f"{average_xgb_mae:.6f}（MA7 {average_ma7_mae:.6f}），仅赢 {xgb_wins}/3 折"
        )
        warnings.append(f"{fallback_reason}，已使用 MA7")
    evaluation = {
        "split": {
            "train_ratio": 0.70,
            "validation_ratio": 0.15,
            "test_ratio": 0.15,
            "method": "按时间顺序 70/15/15；滚动特征仅使用预测时点之前的数据",
        },
        "train_samples": train_end,
        "validation_samples": validation_end - train_end,
        "test_samples": sample_count - test_start,
        "expanding_windows": windows,
        "test": test_metrics,
        "selection": selection,
        "postprocessing": postprocessing,
    }
    return sequence, test_metrics, evaluation, selected_model, fallback_reason, warnings


def forecast(request: ForecastRequest) -> dict[str, Any]:
    if request.lead_time > 14:
        raise ForecastError(
            "HORIZON_INSUFFICIENT",
            "固定预测窗口为 14 天，lead_time 大于 14 天无法覆盖",
        )
    start_date, values, effective_days, nonzero_days, _ = _daily_values(request.history, request.as_of_date)
    as_of = request.as_of_date or (start_date + timedelta(days=len(values) - 1))
    fallback_reason: str | None = None
    warnings: list[str] = []
    model_name = "ma7"
    postprocessing = {
        "nonnegative_clamp": True,
        "rounding_decimals": 6,
        "recursive": True,
        "explanation": "预测值经过非负截断并保留 6 位小数；未来每一步将前一步预测写回序列",
    }

    use_xgb = effective_days >= 300 and nonzero_days >= 60
    if use_xgb:
        try:
            sequence, metrics, evaluation, model_name, fallback_reason, model_warnings = _xgb_computation(start_date, as_of, values)
            warnings.extend(model_warnings)
            postprocessing = evaluation.get("postprocessing", postprocessing)
        except ForecastError as exc:
            sequence = _recursive_ma7(start_date, as_of, values)
            # The explicit XGBoost dependency/data reason is retained for the
            # caller and never presented as a successful model run.
            fallback_reason = exc.message
            warnings.append(f"XGBoost 已降级为 MA7：{exc.message}")
            model_name = "ma7"
            sample_count = max(0, len(values) - max(1, int(len(values) * 0.85)))
            test_start = max(1, int(len(values) * 0.85))
            metrics = _ma7_test_metrics(values, test_start)
            evaluation = {
                "split": {
                    "train_ratio": 0.70,
                    "validation_ratio": 0.15,
                    "test_ratio": 0.15,
                    "method": "按时间顺序 70/15/15；MA7 作为降级基线",
                },
                "train_samples": int(len(values) * 0.70),
                "validation_samples": int(len(values) * 0.15),
                "test_samples": sample_count,
                "expanding_windows": [],
                "test": metrics,
                "selection": {
                    "selected_model": "ma7",
                    "rule": "XGBoost 依赖或数据不可用时使用 MA7",
                },
                "postprocessing": postprocessing,
            }
        except Exception as exc:
            sequence = _recursive_ma7(start_date, as_of, values)
            fallback_reason = f"XGBoost 训练失败（{type(exc).__name__}）"
            warnings.append(f"XGBoost 已降级为 MA7：{fallback_reason}")
            model_name = "ma7"
            test_start = max(1, int(len(values) * 0.85))
            metrics = _ma7_test_metrics(values, test_start)
            evaluation = {
                "split": {
                    "train_ratio": 0.70,
                    "validation_ratio": 0.15,
                    "test_ratio": 0.15,
                    "method": "按时间顺序 70/15/15；XGBoost 异常时使用 MA7",
                },
                "train_samples": int(len(values) * 0.70),
                "validation_samples": int(len(values) * 0.15),
                "test_samples": max(0, len(values) - test_start),
                "expanding_windows": [],
                "test": metrics,
                "selection": {
                    "selected_model": "ma7",
                    "rule": "XGBoost 训练异常时使用 MA7",
                },
                "postprocessing": postprocessing,
            }
    else:
        reasons: list[str] = []
        if effective_days < 300:
            reasons.append(f"有效天数 {effective_days} < 300")
        if nonzero_days < 60:
            reasons.append(f"非零需求日 {nonzero_days} < 60")
        fallback_reason = "；".join(reasons)
        warnings.append(f"样本未达到 XGBoost 门槛，使用 MA7：{fallback_reason}")
        sequence = _recursive_ma7(start_date, as_of, values)
        test_start = max(1, int(len(values) * 0.85))
        metrics = _ma7_test_metrics(values, test_start)
        evaluation = {
            "split": {
                "train_ratio": 0.70,
                "validation_ratio": 0.15,
                "test_ratio": 0.15,
                "method": "按时间顺序 70/15/15；MA7 作为样本不足时的降级基线",
            },
            "train_samples": int(len(values) * 0.70),
            "validation_samples": int(len(values) * 0.15),
            "test_samples": max(0, len(values) - test_start),
            "expanding_windows": [],
            "test": metrics,
            "selection": {
                "selected_model": "ma7",
                "rule": "有效天数不足 300 或非零需求日不足 60 时使用 MA7",
            },
            "postprocessing": postprocessing,
        }

    return {
        "material_id": request.material_id,
        "material_code": request.material_code,
        "as_of_date": as_of,
        "horizon": 14,
        "lead_time": request.lead_time,
        "model": model_name,
        "fallback_reason": fallback_reason,
        "metrics": metrics,
        "evaluation": evaluation,
        "sequence": sequence,
        "warnings": warnings,
        "postprocessing": postprocessing,
    }
