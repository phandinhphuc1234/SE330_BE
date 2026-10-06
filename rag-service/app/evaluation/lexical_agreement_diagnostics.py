"""Descriptive lexical features only: no ranking policy, thresholds or routing.

Title-token overlap names a set intersection, NOT where a token occurred or
whether it is useless. Query/evidence labels never enter the feature formula.
"""

from collections import defaultdict
from copy import deepcopy
import math

from app.evaluation.chunking_comparison import evidence_metrics
from app.evaluation.retrieval_diagnostics import explain_hybrid_trace, profile_keyword_corpus
from app.indexing import SearchResult
from app.retrieval.keyword_retriever import tokenize


def lexical_features(row, title_tokens):
    """Distinct-token and IDF coverage; query-frequency affects BM25 only.

    DF=0 query terms still belong in the denominator. They express that the
    lexical branch cannot match that spelling, not low semantic relevance.
    """
    terms = deepcopy(row["terms"])
    matched = [term["term"] for term in terms if term["tf"] > 0]
    idf_total = sum(term["idf"] for term in terms)
    matched_idf = sum(term["idf"] for term in terms if term["tf"] > 0)
    title_contribution = sum(term["contribution"] for term in terms if term["term"] in title_tokens)
    for term in terms:
        term["inScopeTitle"] = term["term"] in title_tokens
        term["contributionFraction"] = term["contribution"] / row["bm25Score"] if row["bm25Score"] else 0.
    return {"bm25CorpusRank": row["rank"], "bm25Score": row["bm25Score"],
            "queryTokenCoverage": len(matched) / len(terms) if terms else 0.,
            "idfWeightedQueryCoverage": matched_idf / idf_total if idf_total else 0.,
            "matchedTerms": matched, "matchedTitleTerms": [term for term in matched if term in title_tokens],
            "matchedNonTitleTerms": [term for term in matched if term not in title_tokens],
            "titleContributionFraction": title_contribution / row["bm25Score"] if row["bm25Score"] else 0.,
            "terms": terms}


def describe_lexical_agreement(variants, candidates, scope_title, trace, dense_scores, anchors, ranges):
    """Cross-check actual branch ranks, then annotate and select private rows.

    Keep the full RRF union and every source-overlapping candidate (including
    BM25 score=0). Labels only affect evidence annotation/display selection;
    they cannot change branch ranks, scores, token features or trace snapshots.
    """
    ids = [candidate.vector_id for candidate in candidates]
    if any(not key for key in ids) or len(set(ids)) != len(ids) or set(dense_scores) != set(ids):
        raise ValueError("Diagnostic requires unique vector IDs and a cosine for every scoped candidate.")
    if any(not math.isfinite(score) or not -1 <= score <= 1 for score in dense_scores.values()):
        raise ValueError("Diagnostic cosine scores must be finite and bounded.")
    snapshot = trace.export()
    if not variants or snapshot["parameters"].get("rewriteCount") != len(variants):
        raise ValueError("Diagnostic variants differ from the actual pipeline.")
    title_tokens = set(tokenize(scope_title))
    # All lexical computations are completed BEFORE reading any anchor label.
    profiles = [profile_keyword_corpus(variant, candidates) for variant in variants]
    features = [{row["vectorId"]: lexical_features(row, title_tokens) for row in profile["results"]}
                for profile in profiles]
    ranks = {stage: {row["vectorId"]: row["rank"] for row in rows}
             for stage, rows in snapshot["stages"].items()}
    for index, profile in enumerate(profiles):
        expected = sorted((row for row in profile["results"] if row["rank"] is not None), key=lambda row: row["rank"])
        actual = snapshot["stages"].get(f"bm25_{index}", [])
        expected = expected[:snapshot["parameters"]["candidateK"]]
        if [row["vectorId"] for row in expected] != [row["vectorId"] for row in actual] or any(
            not math.isclose(row["bm25Score"], observed["bm25RawScore"], rel_tol=1e-12, abs_tol=1e-12)
            for row, observed in zip(expected, actual, strict=True)
        ):
            raise ValueError("BM25 profile drifted from actual scoped branch ranks/scores.")
    dense_order = sorted(dense_scores, key=lambda key: (-dense_scores[key], key))
    actual_dense = snapshot["stages"].get("dense_candidates", [])
    if dense_order[:snapshot["parameters"]["candidateK"]] != [row["vectorId"] for row in actual_dense] or any(
        not math.isclose(dense_scores[row["vectorId"]], row["score"], rel_tol=1e-12, abs_tol=1e-12)
        for row in actual_dense
    ):
        raise ValueError("Dense corpus profile drifted from actual scoped branch ranks/scores.")
    dense_rank = {key: rank for rank, key in enumerate(dense_order, 1)}
    corpus_rows = []
    for candidate in candidates:
        key = candidate.vector_id
        corpus_rows.append({"vectorId": key, "pageStart": candidate.metadata.get("pageStart"),
                            "pageEnd": candidate.metadata.get("pageEnd"), "denseCorpusRank": dense_rank[key],
                            "cosineScore": dense_scores[key],
                            "stageRanks": {stage: stage_ranks.get(key) for stage, stage_ranks in ranks.items()},
                            "lexical": {f"bm25_{index}": branch[key] for index, branch in enumerate(features)}})
    annotated_trace = explain_hybrid_trace(trace, anchors, ranges)
    selected = []
    for row in corpus_rows:
        hit = SearchResult(row["vectorId"], row["cosineScore"], "", vector_id=row["vectorId"])
        metrics = evidence_metrics(anchors, [hit], ranges, 1)
        if row["stageRanks"].get("rrf_all") is not None or metrics["sourceCharacterCoverage"] > 0:
            row["evidence"] = metrics
            selected.append(row)
    selected.sort(key=lambda row: (row["stageRanks"].get("rrf_all") or math.inf, row["vectorId"]))
    query_profiles = []
    for index, profile in enumerate(profiles):
        distinct = list(dict.fromkeys(profile["tokens"]))
        corpus_size = profile["corpusSize"]
        query_profiles.append({"branch": f"bm25_{index}", "tokens": profile["tokens"],
                               "averageDocumentTokens": profile["averageDocumentTokens"],
                               "corpusMatchableTokenFraction": sum(profile["documentFrequency"][term] > 0 for term in distinct)
                               / len(distinct) if distinct else 0.,
                               "terms": [{"term": term, "df": profile["documentFrequency"][term],
                                          "documentFraction": profile["documentFrequency"][term] / corpus_size if corpus_size else 0.,
                                          "inScopeTitle": term in title_tokens} for term in distinct]})
    return {"corpusSize": len(candidates), "focusedBranch": f"bm25_{len(variants) - 1}",
            "queryProfiles": query_profiles, "candidates": selected, "trace": annotated_trace}


def summarize_cases(cases):
    """Describe ALL cases by segment; no learned cutoff or policy recommendation."""
    segments = defaultdict(list)
    for case in cases:
        segments[(case["datasetId"], case["bookId"], case["chunker"])].append(case)
    summaries = []
    for (dataset_id, book_id, chunker), group in sorted(segments.items()):
        gains = losses = ties = title_only = no_keyword = 0
        for case in group:
            stages = case["diagnostic"]["trace"]["stageSummary"]
            delta = stages["final_top_k"]["metricsAt3"]["evidenceRecall"] - stages["dense_candidates"]["metricsAt3"]["evidenceRecall"]
            gains += delta > 0
            losses += delta < 0
            ties += delta == 0
            branch = case["diagnostic"]["focusedBranch"]
            top = next((row["lexical"][branch] for row in case["diagnostic"]["candidates"]
                        if row["stageRanks"].get(branch) == 1), None)
            no_keyword += top is None
            title_only += bool(top and top["matchedTitleTerms"] and not top["matchedNonTitleTerms"])
        summaries.append({"datasetId": dataset_id, "bookId": book_id, "chunker": chunker, "caseCount": len(group),
                          "hybridVsDenseRecall3": {"increased": gains, "decreased": losses, "equal": ties},
                          "focusedBm25Top1TitleTokenOnlyCount": title_only, "noFocusedBm25ResultCount": no_keyword})
    return summaries
