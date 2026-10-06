"""Opt-in, request-local ranking snapshots; never logs or exposes source text."""

from __future__ import annotations

from copy import deepcopy
from dataclasses import dataclass, field

from app.indexing import SearchResult


@dataclass
class RankingTrace:
    """One collector per request. No labels, raw query, text or arbitrary metadata.

    Callers explicitly supply this to ``search``; HTTP callers do not. Snapshots
    contain only IDs, page numbers and numeric scores, not live result references.
    """

    parameters: dict = field(default_factory=dict)
    stages: dict[str, list[dict]] = field(default_factory=dict)
    rrf_contributions: dict[str, list[dict]] = field(default_factory=dict)
    outcome: str = "pending"

    def begin(self, **parameters) -> None:
        self.parameters = dict(parameters)
        self.stages.clear()
        self.rrf_contributions.clear()
        self.outcome = "pending"

    def record(self, stage: str, results: list[SearchResult]) -> None:
        rows = []
        for rank, result in enumerate(results, 1):
            metadata = result.metadata or {}
            row = {"rank": rank, "vectorId": str(result.vector_id or result.id),
                   "score": float(result.score)}
            for source, target in (
                ("pageStart", "pageStart"), ("pageEnd", "pageEnd"),
                ("bm25_score", "bm25RawScore"), ("rrf_score", "rrfScore"),
                ("rrf_raw_score", "rrfRawScore"), ("lexical_coverage", "lexicalCoverage"),
                ("pre_rerank_score", "preRerankCosine"), ("reranker_score", "rerankerScore"),
            ):
                value = metadata.get(source)
                if isinstance(value, (int, float)) and not isinstance(value, bool):
                    row[target] = value
            rows.append(row)
        self.stages[stage] = rows

    def add_rrf(self, key: str, *, branch: str, rank: int, weight: float, contribution: float) -> None:
        self.rrf_contributions.setdefault(key, []).append(
            {"branch": branch, "rank": rank, "weight": weight, "contribution": contribution}
        )

    def export(self) -> dict:
        return deepcopy({"parameters": self.parameters, "outcome": self.outcome,
                         "stages": self.stages, "rrfContributions": self.rrf_contributions})
