"""Predeclared policies and source-label-only review; no runtime auto-promotion."""

from __future__ import annotations

from dataclasses import asdict, dataclass
from copy import deepcopy
import hashlib
import json
import math

from app.retrieval.reranker import Reranker


@dataclass(frozen=True)
class HybridPolicy:
    id: str
    vector_weight: float
    keyword_weight: float
    rank_weight: float
    semantic_weight: float
    lexical_weight: float

    def __post_init__(self):
        if not self.id or any(isinstance(value, bool) or not isinstance(value, (int, float))
                              or not math.isfinite(value) or value < 0
                              for value in (self.vector_weight, self.keyword_weight)):
            raise ValueError("Policy ID and finite non-negative fusion weights required.")
        if not math.isclose(self.vector_weight + self.keyword_weight, 1., rel_tol=0., abs_tol=1e-12):
            raise ValueError("Policy fusion weights must sum to one.")
        self.reranker()  # Validate reranker weights before any experiment I/O.

    def reranker(self):
        return Reranker(rank_weight=self.rank_weight, semantic_weight=self.semantic_weight,
                        lexical_weight=self.lexical_weight)

    def apply(self, settings):
        return settings.model_copy(update={"hybrid_vector_weight": self.vector_weight,
                                           "hybrid_keyword_weight": self.keyword_weight})


# Fixed coarse hypotheses, registered before the first run. A changed registry
# requires a new experiment/version, not quiet tuning after viewing these data.
POLICIES = (
    HybridPolicy("baseline_v1", .70, .30, .40, .50, .10),
    HybridPolicy("fusion_dense_85_v1", .85, .15, .40, .50, .10),
    HybridPolicy("rerank_semantic_80_v1", .70, .30, .15, .80, .05),
    HybridPolicy("combined_semantic_80_v1", .85, .15, .15, .80, .05),
    HybridPolicy("cosine_only_ablation_v1", .70, .30, 0., 1., 0.),
)
EXPERIMENT_VERSION = "hybrid-policy-comparison-v1"
REVIEW_RULES = {
    "segmentMetrics": ["recall3", "mrr5", "contextRetention"],
    "noPerCaseRecall3Loss": True,
    "ranking": ["macroRecall3", "macroMrr5", "macroContextRetention", "registryOrder"],
    "automaticPromotion": False,
}


def experiment_manifest():
    return {"version": EXPERIMENT_VERSION, "policies": [asdict(policy) for policy in POLICIES],
            "reviewRules": deepcopy(REVIEW_RULES), "topK": 5, "candidateK": 15, "rrfK": 60,
            "contextSeedK": 3, "contextWindow": 1, "contextBudgetCharacters": 12000}


def experiment_hash():
    return hashlib.sha256(json.dumps(experiment_manifest(), sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def metric_summary(evaluation):
    return {"recall3": evaluation["metricsAtK"]["3"]["evidenceRecall"],
            "mrr5": evaluation["metricsAtK"]["5"]["reciprocalRank"],
            "contextRetention": evaluation["contextRetainedAnchorRate"]}


def review_policies(segments, *, policies=POLICIES, review_rules=REVIEW_RULES):
    """Review all segments/cases; NEVER feed labels back to ranking or weights."""
    if not segments:
        raise ValueError("Policy review needs non-empty segments.")
    reviews = []
    for policy in policies:
        group_metrics, regressions, case_changes = [], [], []
        for segment in segments:
            key = {name: segment[name] for name in ("datasetId", "bookId", "chunker")}
            base = segment["policies"]["baseline_v1"]
            measured = segment["policies"][policy.id]
            baseline_metrics, metrics = metric_summary(base), metric_summary(measured)
            group_metrics.append(metrics)
            losses = [name for name in review_rules["segmentMetrics"]
                      if metrics[name] < baseline_metrics[name] - 1e-12]
            if losses:
                regressions.append(key | {"kind": "segment", "metrics": losses})
            base_cases = {case["id"]: case for case in base["cases"]}
            if (len(base_cases) != len(base["cases"]) or len(measured["cases"]) != len(base_cases)
                    or {case["id"] for case in measured["cases"]} != set(base_cases)):
                raise ValueError("Policy cases must exactly match the baseline questions.")
            for case in measured["cases"]:
                previous = base_cases[case["id"]]
                delta = {"recall3": case["metricsAtK"]["3"]["evidenceRecall"] - previous["metricsAtK"]["3"]["evidenceRecall"],
                         "mrr5": case["metricsAtK"]["5"]["reciprocalRank"] - previous["metricsAtK"]["5"]["reciprocalRank"],
                         "contextRetention": case["contextRetainedAnchorRate"] - previous["contextRetainedAnchorRate"]}
                if any(abs(value) > 1e-12 for value in delta.values()):
                    case_changes.append(key | {"id": case["id"], "delta": delta})
                if delta["recall3"] < -1e-12:
                    regressions.append(key | {"kind": "caseRecall3", "id": case["id"]})
        macro = {name: sum(row[name] for row in group_metrics) / len(group_metrics)
                 for name in review_rules["segmentMetrics"]}
        reviews.append({"policyId": policy.id, "eligibleForFreshValidation": not regressions,
                        "macro": macro, "regressions": regressions, "caseChanges": case_changes})
    eligible = [review for review in reviews if review["policyId"] != "baseline_v1"
                and review["eligibleForFreshValidation"]]
    # Stable sort preserves the preregistered simple-policy order for ties.
    eligible.sort(key=lambda row: tuple(-round(row["macro"][name], 12)
                                       for name in review_rules["segmentMetrics"]))
    nominee = eligible[0]["policyId"] if eligible else None
    return {"nomineeForFreshValidation": nominee, "automaticPromotion": False,
            "runtimePolicy": "baseline_v1", "defaultChunker": "v1", "reviews": reviews,
            "recommendation": "Freeze nominee; validate on fresh human-reviewed data before runtime adoption."
                              if nominee else "HOLD baseline; no candidate passed preregistered no-regression gates."}
