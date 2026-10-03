def reciprocal_rank_fusion(result_sets: list[list[str]], k: int = 60) -> dict[str, float]:
    scores: dict[str, float] = {}
    for results in result_sets:
        for rank, item_id in enumerate(results, start=1):
            scores[item_id] = scores.get(item_id, 0.0) + 1.0 / (k + rank)
    return scores
