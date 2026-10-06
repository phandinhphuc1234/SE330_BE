"""Deterministic reranking that keeps provider cost and latency bounded."""

from __future__ import annotations

from dataclasses import replace
import math

from app.indexing import SearchResult
from app.retrieval.keyword_retriever import tokenize


class Reranker:
    def __init__(self, *, rank_weight: float = .40, semantic_weight: float = .50,
                 lexical_weight: float = .10) -> None:
        """Explicit injection for offline experiments; runtime defaults frozen.

        Weights are not exposed via HTTP or new environment settings. The
        public result score remains cosine, regardless of sorting weights.
        """
        weights = (rank_weight, semantic_weight, lexical_weight)
        if any(isinstance(value, bool) or not isinstance(value, (int, float))
               or not math.isfinite(value) or value < 0 for value in weights):
            raise ValueError("Reranker weights must be finite non-negative numbers.")
        if not math.isclose(sum(weights), 1., rel_tol=0., abs_tol=1e-12):
            raise ValueError("Reranker weights must sum to one.")
        self._weights = tuple(float(value) for value in weights)

    @property
    def weights(self) -> dict[str, float]:
        return dict(zip(("rrf", "cosine", "lexical"), self._weights, strict=True))

    async def rerank(
        self,
        query: str,
        results: list[SearchResult],
        top_k: int,
    ) -> list[SearchResult]:
        if top_k <= 0:
            return []
        query_tokens = set(tokenize(query))
        reranked: list[tuple[float, int, SearchResult]] = []
        seen: set[str] = set()
        for index, result in enumerate(results):
            key = str(result.vector_id or result.id)
            if key in seen:
                continue
            seen.add(key)
            document_tokens = set(tokenize(result.text))
            lexical_coverage = (
                len(query_tokens.intersection(document_tokens)) / len(query_tokens)
                if query_tokens
                else 0.0
            )
            rank_score = float((result.metadata or {}).get("rrf_score", result.score))
            # At k=60 RRF scores are close together. Combine ranking agreement
            # with semantic relevance so a generic lexical match does not
            # displace a much stronger meaning match.
            rank_weight, semantic_weight, lexical_weight = self._weights
            reranker_score = (rank_weight * rank_score + semantic_weight * float(result.score)
                              + lexical_weight * lexical_coverage)
            metadata = dict(result.metadata or {})
            metadata.update(
                {
                    "lexical_coverage": lexical_coverage,
                    "pre_rerank_score": float(result.score),
                    "reranker_score": reranker_score,
                }
            )
            reranked.append(
                (reranker_score, index, replace(result, metadata=metadata))
            )

        reranked.sort(key=lambda item: (-item[0], item[1]))
        return [result for _, _, result in reranked[:top_k]]
