from fastapi import FastAPI, HTTPException
from fastapi.testclient import TestClient

from app.core.exceptions import NotFoundError, register_exception_handlers
from app.core.middlewares.request_id import RequestIdMiddleware
from app.core.middlewares.timing import TimingMiddleware


class DummyLogger:
    def __init__(self) -> None:
        self.records: list[tuple[str, str, dict]] = []

    def info(self, event: str, **kwargs) -> None:
        self.records.append(("info", event, kwargs))

    def warning(self, event: str, **kwargs) -> None:
        self.records.append(("warning", event, kwargs))

    def error(self, event: str, **kwargs) -> None:
        self.records.append(("error", event, kwargs))


def build_error_test_app() -> FastAPI:
    app = FastAPI()
    app.add_middleware(TimingMiddleware)
    app.add_middleware(RequestIdMiddleware)
    register_exception_handlers(app)

    @app.get("/ok")
    async def ok() -> dict[str, str]:
        return {"status": "ok"}

    @app.get("/domain-error")
    async def domain_error() -> None:
        raise NotFoundError("Thing was not found", error_code="THING_NOT_FOUND")

    @app.get("/http-error")
    async def http_error() -> None:
        raise HTTPException(status_code=403, detail="Forbidden by test")

    @app.get("/items/{item_id}")
    async def read_item(item_id: int) -> dict[str, int]:
        return {"item_id": item_id}

    @app.get("/boom")
    async def boom() -> None:
        raise RuntimeError("internal secret should not leak")

    return app


def test_domain_error_response_has_standard_shape_and_request_id() -> None:
    client = TestClient(build_error_test_app())

    response = client.get("/domain-error", headers={"X-Request-ID": "req-domain"})

    assert response.status_code == 404
    assert response.headers["X-Request-ID"] == "req-domain"
    assert response.headers["X-Process-Time"]
    assert response.json() == {
        "error_code": "THING_NOT_FOUND",
        "message": "Thing was not found",
        "request_id": "req-domain",
    }


def test_http_exception_response_has_standard_shape() -> None:
    client = TestClient(build_error_test_app())

    response = client.get("/http-error", headers={"X-Request-ID": "req-http"})

    assert response.status_code == 403
    assert response.json() == {
        "error_code": "HTTP_403",
        "message": "Forbidden by test",
        "request_id": "req-http",
    }


def test_validation_error_response_is_sanitized() -> None:
    client = TestClient(build_error_test_app())

    response = client.get("/items/not-an-int", headers={"X-Request-ID": "req-validation"})

    body = response.json()
    assert response.status_code == 422
    assert body["error_code"] == "REQUEST_VALIDATION_ERROR"
    assert body["message"] == "Request validation failed"
    assert body["request_id"] == "req-validation"
    assert body["details"][0]["loc"] == ["path", "item_id"]
    assert "input" not in body["details"][0]


def test_unhandled_exception_response_is_generic() -> None:
    client = TestClient(build_error_test_app(), raise_server_exceptions=False)

    response = client.get("/boom", headers={"X-Request-ID": "req-boom"})

    assert response.status_code == 500
    assert response.headers["X-Request-ID"] == "req-boom"
    assert response.headers["X-Process-Time"]
    assert response.json() == {
        "error_code": "INTERNAL_SERVER_ERROR",
        "message": "Unexpected application error",
        "request_id": "req-boom",
    }
    assert "internal secret" not in response.text


def test_request_logging_middleware_records_safe_fields(monkeypatch) -> None:
    dummy_logger = DummyLogger()
    monkeypatch.setattr("app.core.middlewares.timing.logger", dummy_logger)
    client = TestClient(build_error_test_app())

    response = client.get("/ok?token=secret-value", headers={"X-Request-ID": "req-log"})

    assert response.status_code == 200
    assert response.headers["X-Request-ID"] == "req-log"
    assert response.headers["X-Process-Time"]

    events = {event: fields for _, event, fields in dummy_logger.records}
    assert events["request_started"]["request_id"] == "req-log"
    assert events["request_started"]["path"] == "/ok"
    assert events["request_finished"]["status_code"] == 200
    assert "duration_ms" in events["request_finished"]

    for _, _, fields in dummy_logger.records:
        assert "body" not in fields
        assert "headers" not in fields
        assert "query" not in fields
        assert "secret-value" not in repr(fields)
