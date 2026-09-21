from __future__ import annotations

import argparse
import json
import os
import time
from datetime import datetime
from pathlib import Path
from typing import Any

import httpx


def load_dotenv(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    if not path.exists():
        return values
    for line in path.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            continue
        key, value = stripped.split("=", 1)
        values[key.strip()] = value.strip().strip('"').strip("'")
    return values


def equal_value(actual: Any, expected: Any) -> bool:
    if isinstance(expected, (int, float)) and not isinstance(expected, bool):
        try:
            return abs(float(actual) - float(expected)) < 1e-9
        except (TypeError, ValueError):
            return False
    return actual == expected


def main() -> int:
    parser = argparse.ArgumentParser(description="Run the real local-LLM parse evaluation set")
    parser.add_argument("--base-url", default="http://127.0.0.1:8001/api/v1/parse")
    parser.add_argument("--cases", type=Path, default=Path(__file__).with_name("parse_cases.json"))
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    project_root = Path(__file__).resolve().parents[2]
    dotenv = load_dotenv(project_root / ".env")
    token = os.environ.get("X_SERVICE_TOKEN") or dotenv.get("X_SERVICE_TOKEN")
    if not token:
        raise SystemExit("X_SERVICE_TOKEN is required in the process environment or project .env")

    cases = json.loads(args.cases.read_text(encoding="utf-8"))
    results: list[dict[str, Any]] = []
    with httpx.Client(timeout=120, trust_env=False) as client:
        for case in cases:
            started = time.perf_counter()
            response = client.post(
                args.base_url,
                headers={"X-Service-Token": token, "X-Request-ID": f"eval-{case['id']}"},
                json={"intent_hint": case["intent_hint"], "text": case["text"]},
            )
            latency_ms = round((time.perf_counter() - started) * 1000, 3)
            errors: list[str] = []
            payload: dict[str, Any] = {}
            if response.status_code != 200:
                errors.append(f"HTTP {response.status_code}")
            else:
                payload = response.json()
                if payload.get("provider") != "openai-compatible":
                    errors.append(f"provider={payload.get('provider')}")
                if payload.get("fallback_reason"):
                    errors.append(f"fallback={payload['fallback_reason']}")
                if payload.get("intent") != case["intent_hint"]:
                    errors.append(f"intent={payload.get('intent')}")
                fields = payload.get("fields", {})
                for key, expected in case.get("expected", {}).items():
                    if not equal_value(fields.get(key), expected):
                        errors.append(f"{key}: expected={expected!r}, actual={fields.get(key)!r}")
                for key in case.get("absent", []):
                    if key in fields:
                        errors.append(f"{key}: expected absent, actual={fields.get(key)!r}")
            results.append(
                {
                    "id": case["id"],
                    "intent": case["intent_hint"],
                    "passed": not errors,
                    "latency_ms": latency_ms,
                    "errors": errors,
                    "provider": payload.get("provider"),
                    "model_name": payload.get("model_name"),
                    "prompt_version": payload.get("prompt_version"),
                    "fields": payload.get("fields", {}),
                    "warnings": payload.get("warnings", []),
                }
            )

    passed = sum(1 for item in results if item["passed"])
    report = {
        "executed_at": datetime.now().astimezone().isoformat(),
        "environment": "local-only",
        "endpoint": args.base_url,
        "summary": {
            "total": len(results),
            "passed": passed,
            "failed": len(results) - passed,
            "pass_rate": round(passed / len(results), 4) if results else 0,
            "average_latency_ms": round(sum(item["latency_ms"] for item in results) / len(results), 3) if results else 0,
        },
        "results": results,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report["summary"], ensure_ascii=False))
    for item in results:
        if not item["passed"]:
            print(f"{item['id']}: {'; '.join(item['errors'])}")
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
