import math
import re
from typing import Protocol

from app.ingestion.chunkers.base import Chunk

APPROX_TOKEN_COUNTER = "approx_whitespace_char_v1"

_TOKENISH_PATTERN = re.compile(r"\w+|[^\w\s]", re.UNICODE)


class TokenCounter(Protocol):
    """Small boundary for counting chunk length in tokens.

    C5 intentionally keeps this as a swappable interface. The first
    implementation is approximate and dependency-light; once the embedding
    model is finalized, this protocol can be backed by the model's tokenizer
    without changing the ingestion pipeline.
    """

    name: str

    def count(self, text: str) -> int:
        """Return the token count for one text payload."""


class ApproxTokenCounter:
    """Estimate token count for debugging and MVP quality reporting.

    This is not a model-specific tokenizer. It combines two cheap signals:
    a word/punctuation count and a common character-based estimate. Taking the
    larger value is conservative enough for chunk quality reports while keeping
    the pipeline independent from a final embedding provider.
    """

    name = APPROX_TOKEN_COUNTER

    def count(self, text: str) -> int:
        stripped_text = (text or "").strip()
        if not stripped_text:
            return 0

        tokenish_count = len(_TOKENISH_PATTERN.findall(stripped_text))
        char_estimate = math.ceil(len(stripped_text) / 4)
        return max(1, tokenish_count, char_estimate)


def attach_token_counts(chunks: list[Chunk], token_counter: TokenCounter) -> None:
    """Attach token_count/token_counter metadata to chunks in-place."""

    for chunk in chunks:
        chunk.metadata["token_count"] = token_counter.count(chunk.text)
        chunk.metadata["token_counter"] = token_counter.name
