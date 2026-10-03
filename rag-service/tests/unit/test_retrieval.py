from app.retrieval.hybrid_retriever import reciprocal_rank_fusion


def test_reciprocal_rank_fusion_scores_results():
    scores = reciprocal_rank_fusion([["a", "b"], ["b"]])
    assert scores["b"] > 0
