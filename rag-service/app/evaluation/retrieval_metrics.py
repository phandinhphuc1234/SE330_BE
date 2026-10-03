def precision_at_k(relevant: set[str], retrieved: list[str], k: int) -> float:
    if k <= 0:
        return 0.0
    return len(relevant.intersection(retrieved[:k])) / k
