"""FastAPI entrypoint for the stateless supply-chain AI service."""

from __future__ import annotations

import logging
import secrets
import time
import uuid
from typing import Any

from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.encoders import jsonable_encoder
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from .config import Settings, get_settings
from .forecasting import ForecastError, forecast
from .logging_config import configure_logging
from .models import (
    ForecastRequest,
    ForecastResponse,
    HealthResponse,
    ParseRequest,
    ParseResponse,
)
from .parsing import UnsupportedIntentError, parse_text


LOGGER = logging.getLogger("ai-service.api")


def _request_id(request: Request) -> str:
    current = getattr(request.state, "request_id", None)
    return current or "unknown"


def _error_payload(code: str, message: str, request: Request) -> dict[str, Any]:
    return {
        "detail": {
            "code": code,
            "message": message,
            "request_id": _request_id(request),
        }
    }


def _auth_dependency(
    request: Request,
    x_service_token: str | None = Header(default=None, alias="X-Service-Token"),
) -> None:
    """Require the internal service token without ever logging its value."""

    settings: Settings = request.app.state.settings
    if not x_service_token:
        raise HTTPException(
            status_code=401,
            detail={"code": "AUTH_REQUIRED", "message": "缺少 X-Service-Token"},
            headers={"WWW-Authenticate": "X-Service-Token"},
        )
    if not secrets.compare_digest(x_service_token, settings.service_token):
        raise HTTPException(
            status_code=403,
            detail={"code": "AUTH_INVALID", "message": "X-Service-Token 无效"},
        )


def create_app(settings: Settings | None = None) -> FastAPI:
    active_settings = settings or get_settings()
    configure_logging(active_settings.log_level)
    app = FastAPI(
        title="供应链 AI 服务",
        description="仅提供三类中文结构化解析与 14 日物料需求预测；不持有业务数据库凭据。",
        version=active_settings.service_version,
    )
    app.state.settings = active_settings

    origins = [origin for origin in active_settings.cors_origins() if origin != "*"]
    if not origins:
        origins = ["http://localhost:5173"]
    app.add_middleware(
        CORSMiddleware,
        allow_origins=origins,
        allow_credentials=False,
        allow_methods=["GET", "POST", "OPTIONS"],
        allow_headers=["Content-Type", "X-Service-Token", "X-Request-ID"],
        max_age=600,
    )

    @app.middleware("http")
    async def request_context_middleware(request: Request, call_next):  # type: ignore[no-untyped-def]
        incoming_id = request.headers.get("X-Request-ID", "")
        request_id = incoming_id[:96] if incoming_id and len(incoming_id) <= 128 else str(uuid.uuid4())
        request.state.request_id = request_id
        started = time.perf_counter()
        response = None
        try:
            response = await call_next(request)
            return response
        except Exception:
            LOGGER.exception(
                "request_failed",
                extra={
                    "request_id": request_id,
                    "method": request.method,
                    "path": request.url.path,
                },
            )
            raise
        finally:
            latency_ms = round((time.perf_counter() - started) * 1000, 3)
            LOGGER.info(
                "request_completed",
                extra={
                    "request_id": request_id,
                    "method": request.method,
                    "path": request.url.path,
                    "status_code": response.status_code if response is not None else 500,
                    "latency_ms": latency_ms,
                },
            )
            if response is not None:
                response.headers["X-Request-ID"] = request_id

    @app.exception_handler(ForecastError)
    async def forecast_error_handler(request: Request, exc: ForecastError) -> JSONResponse:
        return JSONResponse(
            status_code=exc.status_code,
            content=_error_payload(exc.code, exc.message, request),
        )

    @app.exception_handler(UnsupportedIntentError)
    async def unsupported_intent_handler(request: Request, exc: UnsupportedIntentError) -> JSONResponse:
        return JSONResponse(
            status_code=422,
            content=_error_payload(UnsupportedIntentError.code, str(exc), request),
        )

    @app.exception_handler(RequestValidationError)
    async def validation_error_handler(request: Request, exc: RequestValidationError) -> JSONResponse:
        # Keep FastAPI's useful field-level errors while making the envelope
        # consistent for callers and preserving the correlation id.
        return JSONResponse(
            status_code=422,
            content={
                "detail": {
                    "code": "VALIDATION_ERROR",
                    "message": "请求参数未通过严格 Schema 校验",
                    "errors": jsonable_encoder(exc.errors()),
                    "request_id": _request_id(request),
                }
            },
        )

    @app.exception_handler(StarletteHTTPException)
    async def http_exception_handler(request: Request, exc: StarletteHTTPException) -> JSONResponse:
        detail = exc.detail
        if isinstance(detail, dict):
            payload_detail = {**detail, "request_id": detail.get("request_id", _request_id(request))}
        else:
            payload_detail = {
                "code": "HTTP_ERROR",
                "message": str(detail),
                "request_id": _request_id(request),
            }
        return JSONResponse(status_code=exc.status_code, content={"detail": payload_detail}, headers=exc.headers)

    @app.get("/health", response_model=HealthResponse, tags=["system"])
    async def health() -> HealthResponse:
        provider = "openai-compatible" if active_settings.provider_configured else "rule"
        return HealthResponse(
            status="ok",
            service=active_settings.service_name,
            version=active_settings.service_version,
            parser_provider=provider,
        )

    @app.post(
        "/internal/v1/parse",
        response_model=ParseResponse,
        dependencies=[Depends(_auth_dependency)],
        tags=["ai"],
        summary="内部调用：解析受限的三类中文业务文本",
        include_in_schema=False,
    )
    @app.post(
        "/api/v1/parse",
        response_model=ParseResponse,
        dependencies=[Depends(_auth_dependency)],
        tags=["ai"],
        summary="解析受限的三类中文业务文本",
    )
    async def parse(request: Request, payload: ParseRequest) -> ParseResponse:
        result = await parse_text(payload, active_settings)
        LOGGER.info(
            "parse_completed",
            extra={
                "request_id": _request_id(request),
                "intent": result.intent,
                "provider": result.provider,
            },
        )
        return result

    @app.post(
        "/internal/v1/forecast",
        response_model=ForecastResponse,
        dependencies=[Depends(_auth_dependency)],
        tags=["forecast"],
        summary="内部调用：按物料递归生成固定 14 日需求预测",
        include_in_schema=False,
    )
    @app.post(
        "/api/v1/forecast",
        response_model=ForecastResponse,
        dependencies=[Depends(_auth_dependency)],
        tags=["forecast"],
        summary="按物料递归生成固定 14 日需求预测",
    )
    async def forecast_endpoint(request: Request, payload: ForecastRequest) -> ForecastResponse:
        result = forecast(payload)
        response = ForecastResponse.model_validate(result)
        LOGGER.info(
            "forecast_completed",
            extra={
                "request_id": _request_id(request),
                "model": response.model,
            },
        )
        return response

    return app


app = create_app()
