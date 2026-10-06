"""Deterministic, low-cost query rewriting for hybrid retrieval."""

from __future__ import annotations

import re
import unicodedata


_TOKEN_PATTERN = re.compile(r"[^\W_]+(?:['’][^\W_]+)?", re.UNICODE)
_QUESTION_STOP_WORDS = {
    "a", "an", "the", "what", "which", "who", "whom", "where", "when", "why", "how",
    "is", "are", "was", "were", "do", "does", "did", "tell", "me", "about",
    "ai", "cái", "cho", "của", "đã", "được", "gì", "hãy", "khi", "là", "một",
    "nào", "ở", "sao", "thế", "trong", "về", "vì",
}


class QueryRewriter:
    """Create one conservative keyword-focused variant without an LLM call.

    The original query is always first and therefore remains the semantic
    embedding input. The second variant only removes question scaffolding; it
    is useful for lexical/BM25 matching and never invents new facts or entities.
    """

    def __init__(self, *, max_queries: int = 2) -> None:
        if max_queries <= 0:
            raise ValueError("max_queries must be positive.")
        self.max_queries = max_queries

    async def rewrite(self, query: str) -> list[str]:
        original = _normalize_query(query)
        if not original:
            raise ValueError("query must not be blank.")

        variants = [original]
        focused_tokens = [
            token
            for token in _TOKEN_PATTERN.findall(original.casefold())
            if token not in _QUESTION_STOP_WORDS
        ]
        focused = " ".join(focused_tokens)
        if len(focused_tokens) >= 2 and focused.casefold() != original.casefold():
            variants.append(focused)
        return variants[: self.max_queries]


async def rewrite_query(query: str) -> list[str]:
    """Backward-compatible function used by older callers."""

    return await QueryRewriter().rewrite(query)


def _normalize_query(query: str) -> str:
    normalized = unicodedata.normalize("NFKC", str(query or ""))
    return " ".join(normalized.split()).strip()
