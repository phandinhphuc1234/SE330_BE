"""Answer-quality metrics that do not require another judge model."""

from __future__ import annotations

from collections import Counter
import re
import unicodedata


def exact_match(expected: str, actual: str) -> float:
    return float(_normalize(expected) == _normalize(actual))


def token_f1(expected: str, actual: str) -> float:
    expected_tokens = _tokens(expected)
    actual_tokens = _tokens(actual)
    if not expected_tokens or not actual_tokens:
        return float(expected_tokens == actual_tokens)
    overlap = sum((Counter(expected_tokens) & Counter(actual_tokens)).values())
    if overlap == 0:
        return 0.0
    precision = overlap / len(actual_tokens)
    recall = overlap / len(expected_tokens)
    return 2 * precision * recall / (precision + recall)


def answer_point_coverage(expected_points: list[str], actual: str) -> float:
    if not expected_points:
        return 1.0
    normalized_actual = _normalize(actual)
    matched = sum(1 for point in expected_points if _normalize(point) in normalized_actual)
    return matched / len(expected_points)


def _normalize(value: str) -> str:
    normalized = unicodedata.normalize("NFKC", str(value or "")).casefold()
    return " ".join(re.findall(r"[^\W_]+(?:['’][^\W_]+)?", normalized, re.UNICODE))


def _tokens(value: str) -> list[str]:
    normalized = _normalize(value)
    return normalized.split() if normalized else []
