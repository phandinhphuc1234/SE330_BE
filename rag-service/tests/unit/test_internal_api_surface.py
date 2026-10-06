from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

from app.core import internal_auth
from app.main import app


@pytest.fixture(autouse=True)
def configured_internal_auth(monkeypatch):
    # Tests must not inherit a developer's ignored .env or require a real key.
    monkeypatch.setattr(
        internal_auth,
        "get_settings",
        lambda: SimpleNamespace(rag_internal_api_key="test-only-internal-service-key"),
    )


def test_legacy_web_routes_are_not_exposed() -> None:
    client = TestClient(app)

    assert client.get("/api/v1/auth").status_code == 404
    assert client.get("/api/v1/workspaces").status_code == 404
    assert client.get("/api/v1/chat").status_code == 404
    assert client.post("/api/v1/ingestion/upload").status_code == 404


def test_internal_ingestion_rejects_missing_service_credential() -> None:
    client = TestClient(app)

    response = client.post(
        "/internal/ingestions",
        json={
            "sourceType": "LIBRARY_EBOOK",
            "bookId": 101,
            "ebookId": 55,
            "bucket": "library-private",
            "objectKey": "ebooks/101/55/original.pdf",
            "checksumSha256": "a" * 64,
        },
    )

    assert response.status_code == 401
    assert response.json()["error_code"] == "INVALID_INTERNAL_API_KEY"


@pytest.mark.parametrize("path", ["/internal/ingestions", "/internal/answers"])
def test_internal_routes_fail_closed_when_server_key_is_unconfigured(monkeypatch, path):
    monkeypatch.setattr(
        internal_auth, "get_settings", lambda: SimpleNamespace(rag_internal_api_key="")
    )
    response = TestClient(app).post(path, json={})

    assert response.status_code == 503
    assert response.json()["error_code"] == "INTERNAL_AUTH_NOT_CONFIGURED"


def test_internal_answer_rejects_missing_service_credential() -> None:
    client = TestClient(app)

    response = client.post(
        "/internal/answers",
        json={"question": "DIP là gì?", "ebookId": 55},
    )

    assert response.status_code == 401
    assert response.json()["error_code"] == "INVALID_INTERNAL_API_KEY"
