"""Preregistered adaptive hypotheses, OFFLINE only; no runtime policy selector.

Lexical strength is a descriptive IDF overlap, not calibrated confidence.
Known regression queries motivated this experiment, so it is NOT a blind test.
"""

from dataclasses import asdict, dataclass
from copy import deepcopy
import hashlib
import json
import math

from app.evaluation.hybrid_policy_comparison import POLICIES, HybridPolicy, review_policies
from app.evaluation.retrieval_diagnostics import profile_keyword_corpus
from app.retrieval.query_rewriter import QueryRewriter


@dataclass(frozen=True)
class AdaptiveDecision:
    policy_id: str
    raw_strength: float
    effective_strength: float
    focused_variant_index: int
    matched_distinct_tokens: int
    distinct_query_tokens: int
    vector_weight: float
    keyword_weight: float
    rank_weight: float
    semantic_weight: float
    lexical_weight: float

    def __post_init__(self):
        for strength in (self.raw_strength, self.effective_strength):
            if isinstance(strength, bool) or not isinstance(strength, (int, float)) or not math.isfinite(strength) or not 0 <= strength <= 1:
                raise ValueError("Lexical strength must be finite within [0,1].")
        self.fixed_policy()  # Validate every emitted weight before retrieval.

    def fixed_policy(self):
        return HybridPolicy(self.policy_id, self.vector_weight, self.keyword_weight,
                            self.rank_weight, self.semantic_weight, self.lexical_weight)

    def apply(self, settings):
        return self.fixed_policy().apply(settings)

    def reranker(self):
        return self.fixed_policy().reranker()

    def as_report(self):
        return asdict(self)


@dataclass(frozen=True)
class AdaptivePolicy:
    id: str
    strength_transform: str
    adapt_reranker: bool

    def __post_init__(self):
        if not self.id or self.strength_transform not in {"identity", "sqrt"} or not isinstance(self.adapt_reranker, bool):
            raise ValueError("Require policy ID, registered transform and boolean reranker choice.")

    async def resolve(self, query, candidates, *, max_queries=2):
        variants = await QueryRewriter(max_queries=max_queries).rewrite(query)
        profile = profile_keyword_corpus(variants[-1], candidates)
        top = next((row for row in profile["results"] if row["rank"] == 1), None)
        # No title/name removal or language detection. DF=0 remains in the
        # denominator, and query repeats cannot inflate distinct-token coverage.
        terms = top["terms"] if top else []
        denominator = sum(term["idf"] for term in terms)
        raw = sum(term["idf"] for term in terms if term["tf"] > 0) / denominator if denominator else 0.
        strength = math.sqrt(raw) if self.strength_transform == "sqrt" else raw
        keyword_weight = .30 * strength
        rank_weight, semantic_weight, lexical_weight = (.40 * strength, 1. - .50 * strength, .10 * strength) if self.adapt_reranker else (.40, .50, .10)
        return AdaptiveDecision(self.id, raw, strength, len(variants) - 1,
                                sum(term["tf"] > 0 for term in terms), len(set(profile["tokens"])),
                                1. - keyword_weight, keyword_weight, rank_weight, semantic_weight, lexical_weight)


# Coarse alternatives fixed BEFORE first scoring run. No tuned cutoff/grid,
# case IDs, book metadata, expected answer or language selector is allowed.
ADAPTIVE_POLICIES = (
    POLICIES[0],
    AdaptivePolicy("idf_fusion_linear_v2", "identity", False),
    AdaptivePolicy("idf_fusion_sqrt_v2", "sqrt", False),
    AdaptivePolicy("idf_combined_linear_v2", "identity", True),
    AdaptivePolicy("idf_combined_sqrt_v2", "sqrt", True),
)
REVIEW_RULES_V2 = {
    "segmentMetrics": ["recall3", "mrr5", "contextRetention"],
    "noPerCaseRecall3Loss": True,
    "requiresStrictMacroImprovement": True,
    "ranking": ["macroRecall3", "macroMrr5", "macroContextRetention", "registryOrder"],
    "automaticPromotion": False,
}
REGISTERED_EXPERIMENT_SHA256 = "0b4a4499d97b6c6fac0e2daa150a5139a114652d20a8e0b5c742665934c0faac"


def experiment_manifest_v2():
    return {"version": "hybrid-lexical-strength-v2",
            "policies": [{"kind": "fixed" if isinstance(policy, HybridPolicy) else "adaptive", **asdict(policy)}
                         for policy in ADAPTIVE_POLICIES],
            "strength": {"feature": "focused_bm25_top1_distinct_idf_coverage", "bm25K1": 1.5, "bm25B": .75,
                         "includeDfZeroInDenominator": True, "focusedVariant": "last_actual_rewrite",
                         "emptyPositiveRankingStrength": 0., "usesTitleLanguageIdsOrLabels": False},
            "formula": {"keywordWeight": "0.30*s", "vectorWeight": "1-keywordWeight",
                        "combinedReranker": {"rrf": "0.40*s", "cosine": "1-0.50*s", "lexical": "0.10*s"},
                        "fusionOnlyReranker": {"rrf": .4, "cosine": .5, "lexical": .1}},
            "reviewRules": deepcopy(REVIEW_RULES_V2), "topK": 5, "candidateK": 15, "rrfK": 60,
            "contextSeedK": 3, "contextWindow": 1, "contextBudgetCharacters": 12000}


def experiment_hash_v2():
    return hashlib.sha256(json.dumps(experiment_manifest_v2(), sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def verify_registration():
    if experiment_hash_v2() != REGISTERED_EXPERIMENT_SHA256:
        raise ValueError("Adaptive experiment manifest drifted from its preregistered seal.")


def review_policies_v2(segments):
    # Reuse v1's no-regression logic without modifying its frozen registry/rules.
    decision = review_policies(segments, policies=ADAPTIVE_POLICIES, review_rules=REVIEW_RULES_V2)
    baseline = next(row for row in decision["reviews"] if row["policyId"] == "baseline_v1")
    for row in decision["reviews"]:
        if row["policyId"] == "baseline_v1":
            continue
        improved = any(row["macro"][metric] > baseline["macro"][metric] + 1e-12
                       for metric in REVIEW_RULES_V2["segmentMetrics"])
        row["strictMacroImprovement"] = improved
        if not improved:
            row["eligibleForFreshValidation"] = False
            row["regressions"].append({"kind": "noStrictMacroImprovement"})
    eligible = [row for row in decision["reviews"] if row["policyId"] != "baseline_v1" and row["eligibleForFreshValidation"]]
    eligible.sort(key=lambda row: tuple(-round(row["macro"][metric], 12) for metric in REVIEW_RULES_V2["segmentMetrics"]))
    decision["nomineeForFreshValidation"] = eligible[0]["policyId"] if eligible else None
    decision["recommendation"] = ("Freeze nominee; fresh human-reviewed validation required before runtime adoption."
                                  if eligible else "HOLD baseline; adaptive candidates failed no-regression/improvement gates.")
    return decision
