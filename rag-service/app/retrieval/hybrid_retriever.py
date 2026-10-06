"""Reciprocal-rank fusion helpers for dense + keyword retrieval."""

from __future__ import annotations

from dataclasses import replace

from app.indexing import SearchResult
from app.retrieval.ranking_trace import RankingTrace


def reciprocal_rank_fusion(result_sets: list[list[str]], k: int = 60) -> dict[str, float]:
    if k <= 0:
        raise ValueError("k must be positive.")
    scores: dict[str, float] = {}
    for results in result_sets:
        for rank, item_id in enumerate(dict.fromkeys(results), start=1):
            scores[item_id] = scores.get(item_id, 0.0) + 1.0 / (k + rank)
    return scores


def fuse_search_results(
    *,
    vector_results: list[SearchResult],
    keyword_result_sets: list[list[SearchResult]],
    vector_weight: float,
    keyword_weight: float,
    graph_results: list[SearchResult] | None = None,
    graph_weight: float = 0.15,
    rrf_k: int = 60,
    limit: int | None = None,
    trace: RankingTrace | None = None,
) -> list[SearchResult]:
    """Fuse result rankings and retain explainable component scores."""

    if rrf_k <= 0:
        raise ValueError("rrf_k must be positive.")
    if vector_weight < 0 or keyword_weight < 0 or vector_weight + keyword_weight <= 0:
        raise ValueError("retrieval weights must be non-negative and not both zero.")

    weighted_sets: list[tuple[str, float, list[SearchResult]]] = [
        ("vector", vector_weight, vector_results)
    ]
    keyword_set_weight = keyword_weight / max(len(keyword_result_sets), 1)
    weighted_sets.extend(
        ("keyword", keyword_set_weight, results) for results in keyword_result_sets
    )
    if graph_results:
        weighted_sets.append(("graph", graph_weight, graph_results))

    by_key: dict[str, SearchResult] = {}
    fusion_scores: dict[str, float] = {}
    vector_scores: dict[str, float] = {}
    keyword_scores: dict[str, float] = {}
    sources: dict[str, set[str]] = {}

    keyword_index = 0
    for source, weight, results in weighted_sets:
        branch = source
        if source == "keyword":
            branch = f"bm25_{keyword_index}"
            keyword_index += 1
        seen: set[str] = set()
        for rank, result in enumerate(results, start=1):
            key = _result_key(result)
            if key in seen or weight == 0:
                continue
            seen.add(key)
            current = by_key.get(key)
            if current is None or (source == "vector" and current.retrieval_source != "vector"):
                by_key[key] = result
            fusion_scores[key] = fusion_scores.get(key, 0.0) + weight / (rrf_k + rank)
            if trace is not None:
                trace.add_rrf(key, branch=branch, rank=rank, weight=weight,
                              contribution=weight / (rrf_k + rank))
            sources.setdefault(key, set()).add(source)
            if source == "vector":
                vector_scores[key] = max(vector_scores.get(key, 0.0), _clamp(result.score))
            elif source == "keyword":
                keyword_scores[key] = max(keyword_scores.get(key, 0.0), _clamp(result.score))

    if not by_key:
        if trace is not None:
            trace.record("rrf_all", [])
        return []
    max_fusion = max(fusion_scores.values()) or 1.0
    fused: list[SearchResult] = []
    for key, result in by_key.items():
        vector_score = vector_scores.get(key, 0.0)
        keyword_score = keyword_scores.get(key, 0.0)
        normalized_rrf = fusion_scores[key] / max_fusion
        metadata = dict(result.metadata or {})
        metadata.update(
            {
                "retrieval_sources": sorted(sources[key]),
                "vector_score": vector_score,
                "keyword_score": keyword_score,
                "rrf_score": normalized_rrf,
                "rrf_raw_score": fusion_scores[key],
                "score_type": "cosine",
            }
        )
        fused.append(
            replace(
                result,
                # Keep the public score/answer threshold as cosine similarity.
                # A keyword-only result has no calibrated semantic confidence.
                score=vector_score,
                metadata=metadata,
                retrieval_source="hybrid",
            )
        )

    fused.sort(key=lambda item: (-item.metadata["rrf_score"], _result_key(item)))
    if trace is not None:
        # Observe BEFORE the candidate limit: otherwise candidate eviction is
        # indistinguishable from reranker demotion.
        trace.record("rrf_all", fused)
    return fused[:limit] if limit is not None else fused


def _result_key(result: SearchResult) -> str:
    return str(result.vector_id or result.id)


def _clamp(value: float) -> float:
    return max(0.0, min(float(value), 1.0))
