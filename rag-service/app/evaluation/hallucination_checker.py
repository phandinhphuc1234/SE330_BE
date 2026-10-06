"""Citation/abstention checks based on trusted retrieval identifiers."""

from __future__ import annotations


def citation_precision(expected_chunk_ids: set[str], cited_chunk_ids: list[str]) -> float:
    if not cited_chunk_ids:
        return 0.0
    return sum(1 for chunk_id in cited_chunk_ids if chunk_id in expected_chunk_ids) / len(
        cited_chunk_ids
    )


def citation_recall(expected_chunk_ids: set[str], cited_chunk_ids: list[str]) -> float:
    if not expected_chunk_ids:
        return 0.0
    return len(expected_chunk_ids.intersection(cited_chunk_ids)) / len(expected_chunk_ids)


def abstention_accuracy(*, expected_answerable: bool, abstained: bool) -> float:
    return float(abstained is (not expected_answerable))


def is_grounded(
    answer: str,
    contexts: list[str],
    *,
    cited_chunk_ids: list[str] | None = None,
    available_chunk_ids: set[str] | None = None,
) -> bool:
    if not answer or not contexts:
        return False
    if cited_chunk_ids is None or available_chunk_ids is None:
        return False
    return bool(cited_chunk_ids) and set(cited_chunk_ids).issubset(available_chunk_ids)
