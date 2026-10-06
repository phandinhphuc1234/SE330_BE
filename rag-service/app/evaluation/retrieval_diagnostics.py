"""Read-only ranking diagnostics; explanations never change runtime scores."""

from collections import Counter
from copy import deepcopy
import math

from app.evaluation.chunking_comparison import evidence_metrics
from app.indexing import SearchResult
from app.retrieval.keyword_retriever import bm25_score_candidates, tokenize
from app.retrieval.ranking_trace import RankingTrace


def profile_keyword_corpus(query, candidates, *, k1=1.5, b=.75):
    """Label-free BM25 explanations, including zero-score corpus rows.

    Positive ranks come from the production scorer, not a separate sort.
    The term sum is checked against that scorer for every candidate. A null
    rank means the scorer emitted no result; it is NOT a tied last-place rank.
    """
    tokens = tokenize(query)
    documents = [tokenize(candidate.text) for candidate in candidates]
    average = sum(map(len, documents)) / len(documents) if documents else 0
    frequencies = {term: sum(term in document for document in documents) for term in set(tokens)}
    query_counts = Counter(tokens)
    ranked = bm25_score_candidates(tokens, candidates, k1=k1, b=b)
    scores = {id(candidate): (rank, score) for rank, (score, candidate) in enumerate(ranked, 1)}
    rows = []
    for candidate, document_tokens in zip(candidates, documents, strict=True):
        rank, score = scores.get(id(candidate), (None, 0.))
        counts = Counter(document_tokens)
        terms = []
        for term, query_count in query_counts.items():
            frequency, df = counts[term], frequencies[term]
            idf = math.log(1 + (len(candidates) - df + .5) / (df + .5))
            contribution = (query_count * idf * frequency * (k1 + 1)
                            / (frequency + k1 * (1 - b + b * len(document_tokens) / average))) if frequency else 0.
            terms.append({"term": term, "tf": frequency, "df": df, "idf": idf, "contribution": contribution})
        if not math.isclose(sum(term["contribution"] for term in terms), score, rel_tol=1e-12, abs_tol=1e-12):
            raise ValueError("Diagnostic BM25 explanation drifted from the production scorer.")
        rows.append({"rank": rank, "vectorId": candidate.vector_id, "bm25Score": score,
                     "pageStart": candidate.metadata.get("pageStart"), "pageEnd": candidate.metadata.get("pageEnd"),
                     "documentTokens": len(document_tokens), "terms": terms})
    return {"query": query, "tokens": tokens, "corpusSize": len(candidates), "averageDocumentTokens": average,
            "documentFrequency": frequencies, "results": rows}


def explain_keyword_ranking(query, candidates, anchors, ranges, *, k1=1.5, b=.75, top_k=5):
    """Attach evidence AFTER scoring; preserve the original positive-rank report."""
    profile = profile_keyword_corpus(query, candidates, k1=k1, b=b)
    by_id = {id(candidate): row for candidate, row in zip(candidates, profile["results"], strict=True)}
    ranked = bm25_score_candidates(tokenize(query), candidates, k1=k1, b=b)
    rows, hits, first_relevant = [], [], None
    for rank, (score, candidate) in enumerate(ranked, 1):
        row = deepcopy(by_id[id(candidate)])
        hit = SearchResult(candidate.vector_id, score, candidate.text, dict(candidate.metadata), candidate.vector_id)
        hits.append(hit)
        metrics = evidence_metrics(anchors, [hit], ranges, 1)
        if first_relevant is None and metrics["reciprocalRank"]:
            first_relevant = rank
        row.update(sourceCharacterCoverage=metrics["sourceCharacterCoverage"], evidenceRecall=metrics["evidenceRecall"])
        rows.append(row)
    selected = rows[:top_k]
    if first_relevant and first_relevant > top_k:
        selected.append(rows[first_relevant - 1])
    profile.update(firstRelevantRank=first_relevant, results=selected, metricsAt3=evidence_metrics(anchors, hits, ranges, 3))
    return profile


def explain_dense_ranking(query_vector, chunks, vectors, anchors, ranges, *, top_k=5):
    from app.evaluation.chunking_comparison import cosine
    if len(chunks) != len(vectors):
        raise ValueError("Each chunk needs a real cached vector.")
    hits = [SearchResult(chunk.metadata["vector_id"], cosine(query_vector, vector), chunk.text,
                         dict(chunk.metadata), chunk.metadata["vector_id"])
            for chunk, vector in zip(chunks, vectors, strict=True)]
    hits.sort(key=lambda hit: (-hit.score, hit.vector_id))
    rows = []
    first_relevant = None
    for rank, hit in enumerate(hits, 1):
        metrics = evidence_metrics(anchors, [hit], ranges, 1)
        if first_relevant is None and metrics["reciprocalRank"]:
            first_relevant = rank
        rows.append({"rank": rank, "vectorId": hit.vector_id, "cosineScore": hit.score,
                     "pageStart": hit.metadata.get("pageStart"), "pageEnd": hit.metadata.get("pageEnd"),
                     "chapterTitle": hit.metadata.get("chapter_title"),
                     "sourceCharacterCoverage": metrics["sourceCharacterCoverage"],
                     "evidenceRecall": metrics["evidenceRecall"]})
    selected = rows[:top_k]
    if first_relevant and first_relevant > top_k:
        selected.append(rows[first_relevant - 1])
    return {"firstRelevantRank": first_relevant, "results": selected,
            "topTwoCosineMargin": hits[0].score - hits[1].score if len(hits) > 1 else None,
            "metricsAt3": evidence_metrics(anchors, hits, ranges, 3)}


def explain_hybrid_trace(trace: RankingTrace, anchors, ranges, *, evidence_k=3):
    """Attach labels AFTER the actual pipeline has finished; never rescore it.

    Stage metrics use source spans, not a hard-coded correct chunk ID. Per-row
    relevance uses the evaluator's >=50% anchor rule; complete recall remains
    separately reported. Partial anchors and multi-chunk unions stay visible.
    """
    report = trace.export()
    weights = report["parameters"].get("rerankerWeights", {"rrf": .40, "cosine": .50, "lexical": .10})
    for stage, rows in report["stages"].items():
        hits = [SearchResult(row["vectorId"], row["score"], "", vector_id=row["vectorId"])
                for row in rows]
        for row, hit in zip(rows, hits, strict=True):
            row["evidence"] = evidence_metrics(anchors, [hit], ranges, 1)
            if stage == "rrf_all":
                components = report["rrfContributions"].get(row["vectorId"], [])
                if not math.isclose(sum(item["contribution"] for item in components), row["rrfRawScore"],
                                    rel_tol=1e-12, abs_tol=1e-12):
                    raise ValueError("RRF trace contributions drifted from production scoring.")
            if "rerankerScore" in row:
                components = {"rrf": weights["rrf"] * row["rrfScore"],
                              "cosine": weights["cosine"] * row["preRerankCosine"],
                              "lexical": weights["lexical"] * row["lexicalCoverage"]}
                if not math.isclose(sum(components.values()), row["rerankerScore"], rel_tol=1e-12, abs_tol=1e-12):
                    raise ValueError("Diagnostic reranker weights drifted from the production scorer.")
                row["rerankerContributions"] = components
        relevant = [row["rank"] for row in rows if row["evidence"]["reciprocalRank"]]
        report.setdefault("stageSummary", {})[stage] = {
            "candidateCount": len(rows), "firstRelevantRank": min(relevant) if relevant else None,
            "metricsAt3": evidence_metrics(anchors, hits, ranges, evidence_k),
            "metricsAtAll": evidence_metrics(anchors, hits, ranges, len(hits)),
        }

    dense = report["stageSummary"].get("dense_candidates", {})
    final = report["stageSummary"].get("final_top_k", {})
    loss_stage = None
    if dense.get("metricsAt3", {}).get("evidenceRecall", 0) > final.get("metricsAt3", {}).get("evidenceRecall", 0):
        previous = dense["metricsAt3"]["evidenceRecall"]
        for stage in ("rrf_all", "rrf_candidates", "reranked_candidates", "threshold_passed", "final_top_k"):
            summary = report["stageSummary"].get(stage)
            if summary is None:
                continue
            recall = summary["metricsAt3"]["evidenceRecall"]
            if recall < previous:
                loss_stage = stage
                break
            previous = recall
    # This is an observed rank/recall loss, not a claim that this one stage is
    # the sole causal bug. A later stage may recover an earlier loss.
    report["firstTop3LossStage"] = loss_stage
    evidence_ids = {row["vectorId"] for rows in report["stages"].values()
                    for row in rows if row["evidence"]["reciprocalRank"]}
    report["evidenceRankPath"] = {
        key: {stage: next((row["rank"] for row in rows if row["vectorId"] == key), None)
              for stage, rows in report["stages"].items()}
        for key in sorted(evidence_ids)
    }
    return deepcopy(report)
