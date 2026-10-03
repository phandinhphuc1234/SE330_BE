from __future__ import annotations

from collections.abc import Iterable
from typing import Any


class QdrantFilterBuildError(ValueError):
    """Raised when vector search filters cannot be converted to Qdrant filters."""


def build_qdrant_filter(filters: dict[str, Any]):
    """Convert provider-neutral filters into a Qdrant Filter model.

    Only exact-match filters are supported in the baseline. This keeps A4 safe
    and predictable: Library scope filters become Qdrant `must` conditions.
    """

    normalized_filters = _normalize_filters(filters)
    if not normalized_filters:
        raise QdrantFilterBuildError("Qdrant search requires at least one metadata filter.")

    try:
        from qdrant_client.models import FieldCondition, Filter, MatchAny, MatchValue
    except ImportError as error:  # pragma: no cover - depends on environment install.
        raise QdrantFilterBuildError("qdrant-client models are required to build Qdrant filters.") from error

    conditions = []
    for key, value in normalized_filters.items():
        if isinstance(value, list):
            conditions.append(
                FieldCondition(
                    key=key,
                    match=MatchAny(any=value),
                )
            )
        else:
            conditions.append(
                FieldCondition(
                    key=key,
                    match=MatchValue(value=value),
                )
            )

    return Filter(must=conditions)


def _normalize_filters(filters: dict[str, Any]) -> dict[str, Any]:
    if not filters:
        return {}

    normalized: dict[str, Any] = {}
    for key, value in filters.items():
        normalized_key = str(key).strip()
        if not normalized_key:
            raise QdrantFilterBuildError("Filter key must not be empty.")

        normalized_value = _normalize_filter_value(value)
        if normalized_value is None:
            continue
        normalized[normalized_key] = normalized_value

    return normalized


def _normalize_filter_value(value: Any) -> Any:
    if value is None:
        return None
    if isinstance(value, bool | int | float):
        return value
    if isinstance(value, str):
        stripped = value.strip()
        return stripped or None
    if isinstance(value, Iterable) and not isinstance(value, (str, bytes, dict)):
        values = [_normalize_filter_value(item) for item in value]
        values = [item for item in values if item is not None]
        return values or None
    raise QdrantFilterBuildError(f"Unsupported filter value type: {type(value).__name__}.")
