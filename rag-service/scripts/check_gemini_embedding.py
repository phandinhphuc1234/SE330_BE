"""Smoke-test Gemini Embedding API without printing secrets.

Usage:
    poetry run python scripts/check_gemini_embedding.py
    poetry run python scripts/check_gemini_embedding.py --model gemini-embedding-001

The script reads GEMINI_API_KEY from .env and embeds one sample query plus one
sample document/chunk. It prints vector dimensions and a cosine similarity so
you can quickly verify that the API, key, and model are working.
"""

from __future__ import annotations

import argparse
import math
import os
import sys
from typing import Any

from dotenv import load_dotenv


DEFAULT_QUERY = "nhân vật chính gặp ai ở chương 1?"
DEFAULT_GEMINI_EMBEDDING_MODEL = "gemini-embedding-2"
DEFAULT_DOCUMENT = (
    "Book: Smoke Test\n"
    "Chapter: Chương 1\n"
    "Page: 1\n\n"
    "Minh bước vào thư viện khi trời vừa tối và nhìn thấy một cuốn sách cũ nằm trên bàn."
)


def main() -> int:
    load_dotenv()
    args = parse_args()

    api_key = os.getenv("GEMINI_API_KEY")
    if not api_key:
        print("ERROR: Missing GEMINI_API_KEY in .env", file=sys.stderr)
        return 2

    try:
        from google import genai
        from google.genai import types
    except ImportError as exc:
        print(
            "ERROR: google-genai is not installed. Run `poetry install` or add the google-genai package.",
            file=sys.stderr,
        )
        print(f"Import error: {exc}", file=sys.stderr)
        return 2

    client = genai.Client(api_key=api_key)
    model = args.model
    if not model.startswith("gemini-embedding"):
        print(
            (
                "ERROR: This script only tests Gemini embedding models. "
                f"Received model={model!r}. Use --model gemini-embedding-2 "
                "or --model gemini-embedding-001."
            ),
            file=sys.stderr,
        )
        return 2

    query_text, query_config = build_query_input(args.query, model=model, types_module=types)
    document_text, document_config = build_document_input(
        args.document,
        title=args.title,
        model=model,
        types_module=types,
    )

    print("Gemini embedding smoke test")
    print(f"Model: {model}")
    print("API key: loaded from GEMINI_API_KEY (not printed)")

    try:
        query_vector = embed_one(client, model=model, text=query_text, config=query_config)
        document_vector = embed_one(client, model=model, text=document_text, config=document_config)
    except Exception as exc:
        print("ERROR: Gemini embedding request failed.", file=sys.stderr)
        print(f"{type(exc).__name__}: {exc}", file=sys.stderr)
        return 1

    if not query_vector or not document_vector:
        print("ERROR: Gemini returned an empty embedding vector.", file=sys.stderr)
        return 1

    print(f"Query vector dimension: {len(query_vector)}")
    print(f"Document vector dimension: {len(document_vector)}")
    print(f"Query vector preview: {preview_vector(query_vector)}")
    print(f"Document vector preview: {preview_vector(document_vector)}")
    print(f"Cosine similarity(query, document): {cosine_similarity(query_vector, document_vector):.4f}")
    print("Gemini embedding API smoke test completed successfully.")
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Check whether Gemini Embedding API works with GEMINI_API_KEY.")
    parser.add_argument(
        "--model",
        default=resolve_default_model(),
        help=(
            "Gemini embedding model. Default: GEMINI_EMBEDDING_MODEL, "
            "or EMBEDDING_MODEL only when it is a Gemini model, otherwise gemini-embedding-2."
        ),
    )
    parser.add_argument(
        "--query",
        default=DEFAULT_QUERY,
        help="Sample user query to embed.",
    )
    parser.add_argument(
        "--document",
        default=DEFAULT_DOCUMENT,
        help="Sample document/chunk text to embed.",
    )
    parser.add_argument(
        "--title",
        default="Smoke Test",
        help="Document title used by Gemini Embedding 2 retrieval formatting.",
    )
    return parser.parse_args()


def resolve_default_model() -> str:
    """Resolve default model without accidentally using an OpenAI embedding model."""

    gemini_specific_model = os.getenv("GEMINI_EMBEDDING_MODEL")
    if gemini_specific_model:
        return gemini_specific_model

    embedding_model = os.getenv("EMBEDDING_MODEL", "")
    embedding_provider = os.getenv("EMBEDDING_PROVIDER", "").lower()
    if embedding_model.startswith("gemini-embedding"):
        return embedding_model
    if embedding_provider == "gemini" and embedding_model:
        return embedding_model

    return DEFAULT_GEMINI_EMBEDDING_MODEL


def build_query_input(query: str, *, model: str, types_module: Any) -> tuple[str, Any | None]:
    """Build query input using the right Gemini embedding format."""

    if model == "gemini-embedding-001":
        return query, types_module.EmbedContentConfig(task_type="RETRIEVAL_QUERY")

    # Gemini Embedding 2 expects task instruction in the text itself.
    return f"task: search result | query: {query}", None


def build_document_input(
    document: str,
    *,
    title: str,
    model: str,
    types_module: Any,
) -> tuple[str, Any | None]:
    """Build document/chunk input using the right Gemini embedding format."""

    if model == "gemini-embedding-001":
        return document, types_module.EmbedContentConfig(task_type="RETRIEVAL_DOCUMENT")

    title = title.strip() or "none"
    return f"title: {title} | text: {document}", None


def embed_one(client: Any, *, model: str, text: str, config: Any | None) -> list[float]:
    """Call Gemini embed_content for a single independent text input."""

    kwargs = {
        "model": model,
        "contents": text,
    }
    if config is not None:
        kwargs["config"] = config

    result = client.models.embed_content(**kwargs)
    return extract_first_embedding_values(result)


def extract_first_embedding_values(result: Any) -> list[float]:
    """Extract values from google-genai embedding response across minor shape changes."""

    embeddings = getattr(result, "embeddings", None)
    if embeddings:
        first_embedding = embeddings[0]
        values = getattr(first_embedding, "values", None)
        if values is not None:
            return [float(value) for value in values]

    embedding = getattr(result, "embedding", None)
    if embedding is not None:
        values = getattr(embedding, "values", None)
        if values is not None:
            return [float(value) for value in values]

    raise RuntimeError(f"Could not extract embedding values from response type {type(result).__name__}")


def preview_vector(vector: list[float], limit: int = 5) -> str:
    return "[" + ", ".join(f"{value:.5f}" for value in vector[:limit]) + (", ...]" if len(vector) > limit else "]")


def cosine_similarity(left: list[float], right: list[float]) -> float:
    if len(left) != len(right):
        raise ValueError(f"Vector dimensions differ: {len(left)} != {len(right)}")
    dot = sum(a * b for a, b in zip(left, right, strict=True))
    left_norm = math.sqrt(sum(value * value for value in left))
    right_norm = math.sqrt(sum(value * value for value in right))
    if left_norm == 0 or right_norm == 0:
        return 0.0
    return dot / (left_norm * right_norm)


if __name__ == "__main__":
    raise SystemExit(main())
