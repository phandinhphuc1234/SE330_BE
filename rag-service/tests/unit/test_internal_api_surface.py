from fastapi.testclient import TestClient

from app.main import app


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
