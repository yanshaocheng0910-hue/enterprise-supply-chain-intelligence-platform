from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app


@pytest.fixture()
def client() -> TestClient:
    settings = Settings(
        service_token="test-service-token",
        llm_provider="rule",
        cors_allow_origins="http://localhost:5173",
    )
    return TestClient(create_app(settings))


@pytest.fixture()
def service_headers() -> dict[str, str]:
    return {"X-Service-Token": "test-service-token"}

