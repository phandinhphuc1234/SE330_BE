"""Exercise the public Spring reader boundary without storing credentials/tokens."""

import argparse
import io
import json
import os
from uuid import uuid4

import httpx
from pypdf import PdfReader


def check(condition: bool, label: str) -> None:
    if not condition:
        raise ValueError(label)


def run(args) -> dict:
    email, password = os.getenv("RAG_SMOKE_EMAIL"), os.getenv("RAG_SMOKE_PASSWORD")
    check(bool(email and password), "Set RAG_SMOKE_EMAIL and RAG_SMOKE_PASSWORD in the environment.")
    denied_book_id = getattr(args, "denied_book_id", None)
    check(denied_book_id is None or (denied_book_id > 0 and denied_book_id != args.book_id),
          "Denied book ID must be positive and different from the permitted book.")
    base = args.base_url.rstrip("/")
    trace_id = "rag-smoke-" + str(uuid4())
    with httpx.Client(base_url=base, timeout=90) as client:
        anonymous = client.post(f"/api/ebooks/{args.book_id}/reader/semantic-search", json={"query": args.question})
        check(anonymous.status_code == 401, "Anonymous reader request must return 401.")
        anonymous_ask = client.post(f"/api/ebooks/{args.book_id}/reader/ask", json={"question": args.question})
        check(anonymous_ask.status_code == 401, "Anonymous Ask request must return 401.")
        login = client.post("/api/auth/login", json={"email": email, "password": password})
        login.raise_for_status()
        auth = {"Authorization": "Bearer " + login.json()["data"]["accessToken"], "X-Trace-Id": trace_id}
        session = client.post(f"/api/ebooks/{args.book_id}/reading-sessions", headers=auth)
        session.raise_for_status()
        session_data = session.json()["data"]
        ebook_id = session_data["bookEbookId"]
        headers = auth | {"X-Reading-Session": session_data["sessionToken"]}
        result = {"bookId": args.book_id, "ebookId": ebook_id, "traceId": trace_id,
                  "anonymousStatus": 401, "anonymousAskStatus": 401}
        try:
            missing_session = client.post(
                f"/api/ebooks/{args.book_id}/reader/ask", headers=auth,
                json={"question": args.question},
            )
            check(missing_session.status_code == 400
                  and missing_session.json().get("code") == "READING_SESSION_REQUIRED",
                  "Ask without a reading session must fail with READING_SESSION_REQUIRED.")
            result["missingSessionAskStatus"] = missing_session.status_code
            if denied_book_id is not None:
                denied = client.post(
                    f"/api/ebooks/{denied_book_id}/reader/ask", headers=headers,
                    json={"question": args.question},
                )
                check(denied.status_code == 403
                      and denied.json().get("code") == "READING_SESSION_FORBIDDEN",
                      "Ask must reject a session belonging to a different book.")
                result["crossBookAskStatus"] = denied.status_code
            search = client.post(
                f"/api/ebooks/{args.book_id}/reader/semantic-search", headers=headers,
                json={"query": args.question, "topK": 3},
            )
            search.raise_for_status()
            check(search.headers.get("X-Trace-Id") == trace_id, "Public response lost the trace ID.")
            hits = search.json()["data"]["results"]
            check(bool(hits), "Retrieval returned no evidence.")
            content = client.get(f"/api/ebooks/{args.book_id}/reader/content", headers=headers)
            content.raise_for_status()
            pdf = client.get(content.json()["data"]["signedUrl"])
            pdf.raise_for_status()
            page_count = len(PdfReader(io.BytesIO(pdf.content)).pages)

            def check_citations(citations):
                check(bool(citations), "Grounded response has no citations.")
                for citation in citations:
                    check(citation.get("bookId") == args.book_id, "Citation has the wrong book scope.")
                    check(citation.get("ebookId") == ebook_id, "Citation has the wrong ebook scope.")
                    page = citation.get("pageStart")
                    check(isinstance(page, int) and not isinstance(page, bool) and 1 <= page <= page_count,
                          "Citation has no valid PDF page.")

            check_citations([hit["citation"] for hit in hits])
            result.update(resultCount=len(hits), pdfPages=page_count, pdfBytes=len(pdf.content))
            if not args.skip_answers:
                answer = client.post(
                    f"/api/ebooks/{args.book_id}/reader/ask", headers=headers,
                    json={"question": args.question, "topK": 3},
                )
                answer.raise_for_status()
                data = answer.json()["data"]
                check(data["grounded"] and not data["abstained"], "Factual question did not receive a grounded answer.")
                check_citations(data["citations"])
                result["answerCitationPages"] = [item["pageStart"] for item in data["citations"]]
                abstention = client.post(
                    f"/api/ebooks/{args.book_id}/reader/ask", headers=headers,
                    json={"question": args.unanswerable_question, "topK": 3},
                )
                abstention.raise_for_status()
                data = abstention.json()["data"]
                check(data["abstained"] and not data["grounded"] and not data["citations"],
                      "Out-of-scope question did not abstain cleanly.")
                result["abstentionReason"] = data["reason"]
            result["answersTested"] = not args.skip_answers
            return result
        finally:
            # Close only the session created by this smoke run.
            closed = client.post(f"/api/ebooks/reading-sessions/{session_data['sessionId']}/close", headers=headers)
            closed.raise_for_status()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:18090")
    parser.add_argument("--book-id", required=True, type=int)
    parser.add_argument("--denied-book-id", type=int, help="Check that the created session cannot Ask another book.")
    parser.add_argument("--question", default="How much did their furnished rooms cost each week?")
    parser.add_argument("--unanswerable-question", default="According to this book, what is the capital of France?")
    parser.add_argument("--skip-answers", action="store_true", help="Check auth, retrieval and PDF without generation quota.")
    try:
        print(json.dumps(run(parser.parse_args()), ensure_ascii=False, indent=2))
    except httpx.HTTPStatusError as error:
        # Never print signed URLs, headers, credentials or provider response text.
        raise SystemExit(f"Public reader smoke failed: HTTP {error.response.status_code}.") from None
    except (httpx.RequestError, ValueError, KeyError) as error:
        message = str(error) if isinstance(error, ValueError) else type(error).__name__
        raise SystemExit(f"Public reader smoke failed: {message}") from None


if __name__ == "__main__":
    main()
