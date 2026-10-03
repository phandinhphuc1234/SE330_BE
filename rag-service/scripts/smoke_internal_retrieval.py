"""Smoke test the internal Library retrieval API against a running RAG service.

This script intentionally calls the HTTP API instead of importing app services.
It verifies the same boundary Spring Boot will use:

    Spring Boot -> POST /internal/retrieval/search -> RAG -> Gemini -> Qdrant

Required:
    - RAG API running, usually http://localhost:8000
    - RAG_INTERNAL_API_KEY set in .env or passed via --api-key
    - GEMINI_API_KEY configured in the running API container
    - Qdrant collection has indexed chunks if you expect non-empty results

Example:
    poetry run python scripts/smoke_internal_retrieval.py \
      --query "nhân vật chính tìm thấy gì?" \
      --book-id 101 \
      --ebook-id 55 \
      --top-k 5
"""

from __future__ import annotations

import argparse
import os
import sys
from typing import Any

import httpx
from dotenv import load_dotenv


DEFAULT_BASE_URL = "http://localhost:8000"


def main() -> int:
    load_dotenv()
    args = parse_args()
    api_key = args.api_key or os.getenv("RAG_INTERNAL_API_KEY")
    if not api_key:
        print("ERROR: RAG_INTERNAL_API_KEY is required via .env or --api-key.", file=sys.stderr)
        return 2

    payload = build_payload(args)
    try:
        response = httpx.post(
            f"{args.base_url.rstrip('/')}/internal/retrieval/search",
            headers={"X-RAG-API-Key": api_key},
            json=payload,
            timeout=args.timeout,
        )
    except httpx.HTTPError as error:
        print(f"ERROR: could not call RAG API: {error}", file=sys.stderr)
        return 3

    print(f"HTTP {response.status_code}")
    try:
        body = response.json()
    except ValueError:
        print(response.text)
        return 4

    if response.status_code >= 400:
        print_error_body(body)
        return 5

    print_success_body(body, fail_on_empty=args.fail_on_empty)
    if args.fail_on_empty and int(body.get("resultCount") or 0) == 0:
        return 6
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Smoke test internal Library vector retrieval.")
    parser.add_argument("--base-url", default=os.getenv("RAG_API_BASE_URL", DEFAULT_BASE_URL))
    parser.add_argument("--api-key", default=None)
    parser.add_argument("--query", required=True)
    parser.add_argument("--book-id", type=int, default=None)
    parser.add_argument("--ebook-id", type=int, default=None)
    parser.add_argument("--document-id", type=int, default=None)
    parser.add_argument("--top-k", type=int, default=5)
    parser.add_argument("--score-threshold", type=float, default=None)
    parser.add_argument("--timeout", type=float, default=30.0)
    parser.add_argument(
        "--fail-on-empty",
        action="store_true",
        help="Exit non-zero when retrieval succeeds but returns zero chunks.",
    )
    return parser.parse_args()


def build_payload(args: argparse.Namespace) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "query": args.query,
        "topK": args.top_k,
    }
    if args.book_id is not None:
        payload["bookId"] = args.book_id
    if args.ebook_id is not None:
        payload["ebookId"] = args.ebook_id
    if args.document_id is not None:
        payload["documentId"] = args.document_id
    if args.score_threshold is not None:
        payload["scoreThreshold"] = args.score_threshold
    return payload


def print_error_body(body: dict[str, Any]) -> None:
    print("Retrieval smoke test failed.")
    print(f"error_code: {body.get('error_code')}")
    print(f"message: {body.get('message')}")
    details = body.get("details")
    if details:
        print(f"details: {details}")


def print_success_body(body: dict[str, Any], *, fail_on_empty: bool) -> None:
    print("Retrieval smoke test completed.")
    print(f"embeddingVersion: {body.get('embeddingVersion')}")
    print(f"queryTextPolicy: {body.get('queryTextPolicy')}")
    print(f"topK: {body.get('topK')}")
    print(f"resultCount: {body.get('resultCount')}")
    print(f"appliedFilters: {body.get('appliedFilters')}")

    results = body.get("results") or []
    if not results:
        note = "No chunks were returned."
        if fail_on_empty:
            note += " --fail-on-empty is enabled, so this is treated as failure."
        print(note)
        return

    print("Top hits:")
    for index, hit in enumerate(results[:5], start=1):
        text = " ".join(str(hit.get("text") or "").split())
        preview = text[:180] + ("..." if len(text) > 180 else "")
        print(f"{index}. score={hit.get('score')} vectorId={hit.get('vectorId')}")
        print(f"   citation={hit.get('citation')}")
        print(f"   text={preview}")


if __name__ == "__main__":
    raise SystemExit(main())
