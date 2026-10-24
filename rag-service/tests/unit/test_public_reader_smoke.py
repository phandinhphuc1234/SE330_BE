import importlib.util
import io
from pathlib import Path
from types import SimpleNamespace

import httpx
from pypdf import PdfWriter
import pytest


SPEC = importlib.util.spec_from_file_location(
    "public_reader_smoke", Path(__file__).parents[2] / "scripts" / "smoke_public_reader.py",
)
smoke = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(smoke)


def prepare(monkeypatch, *, missing_status=400, cross_status=403, page=1, close_status=200):
    monkeypatch.setenv("RAG_SMOKE_EMAIL", "reader@example.test")
    monkeypatch.setenv("RAG_SMOKE_PASSWORD", "test-only-password")
    pdf = io.BytesIO()
    writer = PdfWriter()
    writer.add_blank_page(width=100, height=100)
    writer.write(pdf)
    requests = []

    def handle(request):
        requests.append(request)
        path = request.url.path
        auth = bool(request.headers.get("Authorization"))
        reading_session = bool(request.headers.get("X-Reading-Session"))
        if path == "/api/auth/login":
            return httpx.Response(200, json={"data": {"accessToken": "test-bearer"}})
        if path.endswith(("/reader/ask", "/reader/semantic-search")) and not auth:
            return httpx.Response(401, json={"code": "UNAUTHORIZED"})
        if path == "/api/ebooks/3342/reading-sessions":
            return httpx.Response(200, json={"data": {
                "bookEbookId": 10, "sessionToken": "test-session", "sessionId": 7,
            }})
        if path.endswith("/reader/ask") and not reading_session:
            return httpx.Response(missing_status, json={"code": "READING_SESSION_REQUIRED"})
        if path == "/api/ebooks/3226/reader/ask":
            return httpx.Response(cross_status, json={"code": "READING_SESSION_FORBIDDEN"})
        if path.endswith("/reader/semantic-search"):
            return httpx.Response(200, headers={"X-Trace-Id": request.headers["X-Trace-Id"]},
                                  json={"data": {"results": [{"citation": {
                                      "bookId": 3342, "ebookId": 10, "pageStart": page,
                                  }}]}})
        if path.endswith("/reader/content"):
            return httpx.Response(200, json={"data": {"signedUrl": "http://source.test/source.pdf"}})
        if path == "/source.pdf":
            return httpx.Response(200, content=pdf.getvalue())
        if path == "/api/ebooks/reading-sessions/7/close":
            return httpx.Response(close_status, json={"data": {"status": "CLOSED"}})
        raise AssertionError("Unexpected request: " + path)

    client_class = httpx.Client
    monkeypatch.setattr(smoke.httpx, "Client", lambda **kwargs: client_class(
        **kwargs, transport=httpx.MockTransport(handle),
    ))
    args = SimpleNamespace(
        base_url="http://library.test", book_id=3342, denied_book_id=3226,
        question="How much?", unanswerable_question="Outside evidence?", skip_answers=True,
    )
    return args, requests


def test_smoke_checks_anonymous_missing_and_cross_book_ask_without_generation(monkeypatch):
    args, requests = prepare(monkeypatch)
    result = smoke.run(args)
    assert result["anonymousAskStatus"] == 401
    assert result["missingSessionAskStatus"] == 400
    assert result["crossBookAskStatus"] == 403
    assert result["answersTested"] is False
    assert result["pdfPages"] == 1
    assert requests[-1].url.path.endswith("/7/close")
    assert not any(request.url.path == "/api/ebooks/3342/reader/ask"
                   and request.headers.get("X-Reading-Session") for request in requests)
    assert "test-bearer" not in str(result) and "test-session" not in str(result)


@pytest.mark.parametrize("options", [{"missing_status": 200}, {"cross_status": 200}, {"page": True}, {"page": 2}])
def test_smoke_fails_closed_and_closes_created_session(monkeypatch, options):
    args, requests = prepare(monkeypatch, **options)
    with pytest.raises(ValueError):
        smoke.run(args)
    assert requests[-1].url.path.endswith("/7/close")


def test_smoke_reports_session_close_failure(monkeypatch):
    args, _requests = prepare(monkeypatch, close_status=503)
    with pytest.raises(httpx.HTTPStatusError):
        smoke.run(args)


@pytest.mark.parametrize("denied_book_id", [0, -1, 3342])
def test_smoke_rejects_invalid_denied_book_before_network(monkeypatch, denied_book_id):
    args, requests = prepare(monkeypatch)
    args.denied_book_id = denied_book_id
    with pytest.raises(ValueError, match="Denied book ID"):
        smoke.run(args)
    assert requests == []
