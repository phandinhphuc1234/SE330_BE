"""Smoke-test the project's real Gemini batch embedding provider.

Usage:
    poetry run python scripts/check_gemini_embedding_batch.py
    poetry run python scripts/check_gemini_embedding_batch.py --count 10

The API key is loaded through ``Settings`` and is never printed.
"""

from __future__ import annotations

import argparse
import asyncio
import math
import sys

from app.core.config import get_settings
from app.indexing.providers import GeminiEmbeddingProvider


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Smoke test Gemini batch embedding.")
    parser.add_argument("--count", type=int, default=10, help="Number of independent texts to embed.")
    return parser.parse_args()


async def run(count: int) -> int:
    if count <= 0:
        print("ERROR: --count must be greater than zero.", file=sys.stderr)
        return 2

    settings = get_settings()
    provider = GeminiEmbeddingProvider(settings=settings)
    texts = [
        (
            f"title: The Great Gatsby | text: Independent smoke-test chunk {index}. "
            "Jay Gatsby watches the green light across the bay."
        )
        for index in range(1, count + 1)
    ]
    expected_batches = math.ceil(count / settings.embedding_batch_size)

    print("Gemini batch embedding smoke test")
    print(f"Model: {settings.embedding_model}")
    print(f"Batch size: {settings.embedding_batch_size}")
    print(f"Input count: {count}")
    print(f"Expected API batches: {expected_batches}")
    print("API key: loaded from GEMINI_API_KEY (not printed)")

    try:
        vectors = await provider.embed(texts)
    except Exception as error:  # noqa: BLE001 - smoke test should report provider errors.
        print("ERROR: Gemini batch embedding request failed.", file=sys.stderr)
        print(f"{type(error).__name__}: {error}", file=sys.stderr)
        return 1

    dimensions = {len(vector) for vector in vectors}
    if len(vectors) != count:
        print(f"ERROR: Expected {count} vectors, received {len(vectors)}.", file=sys.stderr)
        return 1
    if dimensions != {settings.embedding_dim}:
        print(
            f"ERROR: Expected dimension {settings.embedding_dim}, received {sorted(dimensions)}.",
            file=sys.stderr,
        )
        return 1

    print(f"Vector count: {len(vectors)}")
    print(f"Vector dimension: {settings.embedding_dim}")
    print("Gemini batch embedding smoke test completed successfully.")
    return 0


def main() -> int:
    return asyncio.run(run(parse_args().count))


if __name__ == "__main__":
    raise SystemExit(main())
