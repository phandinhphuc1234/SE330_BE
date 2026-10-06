"""Deterministic information-retrieval metrics used by the eval runner."""

from __future__ import annotations

import math


def precision_at_k(relevant: set[str], retrieved: list[str], k: int) -> float:
    if k <= 0:
        return 0.0
    return len(relevant.intersection(retrieved[:k])) / k


def recall_at_k(relevant: set[str], retrieved: list[str], k: int) -> float:
    if k <= 0 or not relevant:
        return 0.0
    return len(relevant.intersection(retrieved[:k])) / len(relevant)


def hit_rate_at_k(relevant: set[str], retrieved: list[str], k: int) -> float:
    if k <= 0 or not relevant:
        return 0.0
    return float(bool(relevant.intersection(retrieved[:k])))


def reciprocal_rank(relevant: set[str], retrieved: list[str]) -> float:
    for rank, item_id in enumerate(retrieved, start=1):
        if item_id in relevant:
            return 1.0 / rank
    return 0.0


def ndcg_at_k(relevant: set[str], retrieved: list[str], k: int) -> float:
    if k <= 0 or not relevant:
        return 0.0
    unique_results = list(dict.fromkeys(retrieved))
    discounted_gain = sum(
        1.0 / math.log2(rank + 1)
        for rank, item_id in enumerate(unique_results[:k], start=1)
        if item_id in relevant
    )
    ideal_hits = min(len(relevant), k)
    ideal_gain = sum(1.0 / math.log2(rank + 1) for rank in range(1, ideal_hits + 1))
    return discounted_gain / ideal_gain if ideal_gain else 0.0
