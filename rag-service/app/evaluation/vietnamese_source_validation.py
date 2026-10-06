"""Sealed, source-language Vietnamese evaluation; never a runtime selector.

The official Vietnamese report is a translation, not a Vietnamese novel or
proof of original Vietnamese authorship. Preserve the assistant-checked label
seal from first scoring; later owner review is a separate receipt, not blind
expert review. Observing results turns this corpus into regression data.
"""

from dataclasses import dataclass
import hashlib
import json
from pathlib import Path

from app.core.config import Settings
from app.evaluation.adaptive_hybrid_experiment import verify_registration as verify_adaptive_seal
from app.evaluation.chunking_comparison import load_dataset
from app.evaluation.hybrid_policy_comparison import experiment_hash


ROOT = Path(__file__).resolve().parents[2]
DATASET_PATH = ROOT / "tests/fixtures/chunking/vietnamese_source_v1.json"
OWNER_REVIEW_PATH = DATASET_PATH.with_name("vietnamese_source_owner_review_v1.json")
DATASET_SHA256 = "b69caf1f7ba2dcd5a83f5e264613e9e2776700c4ab387bf3ee71beeac8758ba6"
SOURCE_SHA256 = "4394395d2399aa986ccfab1c9eafca90b44f21ebf64de5342c9fe6897b6c22d5"
FIXED_POLICY_SHA256 = "c278ba9529bec41bcb829917e2c8c22646bf9600f8920c8f776f4db25b963fe9"
ADAPTIVE_POLICY_SHA256 = "0b4a4499d97b6c6fac0e2daa150a5139a114652d20a8e0b5c742665934c0faac"
CONTEXT_BUDGET = 12000


@dataclass(frozen=True)
class EmbeddingIdentity:
    """Public cache identity, fixed before evaluation; no .env/secret access."""

    model: str = "gemini-embedding-2"
    dimension: int = 3072
    version: str = "gemini-embedding-2-3072-v1"
    text_policy: str = "gemini_search_title_text_v1"

    def settings(self) -> Settings:
        from scripts.benchmark_chunking import BASELINE_CONFIG

        return Settings.model_construct(
            **BASELINE_CONFIG, keyword_candidate_limit=2000,
            hybrid_fail_open=False, context_expansion_window=1,
            embedding_model=self.model, embedding_dim=self.dimension,
            embedding_version=self.version, embedding_text_policy=self.text_policy,
        )

    def report(self) -> dict:
        return {"model": self.model, "dimension": self.dimension,
                "version": self.version, "textPolicy": self.text_policy}


EMBEDDING_IDENTITY = EmbeddingIdentity()


def verify_registration(path: Path = DATASET_PATH):
    """Reject label or previously sealed policy drift BEFORE scoring any case."""
    if hashlib.sha256(path.read_bytes()).hexdigest() != DATASET_SHA256:
        raise ValueError("Vietnamese source labels drifted; create a new dataset version, not an edited baseline.")
    dataset = load_dataset(path)
    if (dataset.query_language != "vi" or dataset.source_language != "vi"
            or dataset.review_status != "assistant_source_checked"
            or dataset.split != "development" or len(dataset.books) != 1
            or dataset.books[0].sha256 != SOURCE_SHA256 or len(dataset.books[0].questions) != 20):
        raise ValueError("Unexpected source-language, scope or review provenance.")
    if experiment_hash() != FIXED_POLICY_SHA256:
        raise ValueError("Previously sealed fixed policies drifted.")
    verify_adaptive_seal()
    return dataset


def registration_manifest() -> dict:
    return {
        "version": "vietnamese-source-first-evaluation-v1",
        "datasetFileSha256": DATASET_SHA256,
        "sourceSha256": SOURCE_SHA256,
        "sourceLanguage": "vi", "queryLanguage": "vi",
        "sourceType": "official-translated-nonfiction-report",
        "originalVietnameseAuthorshipClaim": False,
        "license": "CC BY 3.0 IGO", "licensePhysicalPage": 3,
        "reviewStatus": "assistant_source_checked", "humanReview": "pending",
        "split": "development", "usage": "fresh source at first scoring; regression after observation",
        "labelsFrozenBeforeScoring": True,
        "questionCount": 20, "evidenceAnchorCount": 23, "positiveQuestionsOnly": True,
        "referencePolicies": {"fixedV1Sha256": FIXED_POLICY_SHA256,
                              "adaptiveV2Sha256": ADAPTIVE_POLICY_SHA256},
        "policiesEvaluated": ["bm25-control"],
        "chunkers": ["v1", "v2"], "chunkSizeTokens": 512, "chunkOverlapTokens": 64,
        "topK": [1, 3, 5], "contextSeedK": 3, "contextWindow": 1,
        "contextBudgetCharacters": CONTEXT_BUDGET,
        "embedding": EMBEDDING_IDENTITY.report(),
        "providerCallsAllowed": False, "runtimeWrites": False, "automaticPromotion": False,
    }


def verify_owner_review(dataset, path: Path = OWNER_REVIEW_PATH) -> dict:
    """Check a separate, hash-bound approval without rewriting first scoring.

    Review authorizes these labels, NOT provider costs or automatic promotion.
    Return public provenance only, and check this snapshot again after scoring.
    """
    from datetime import datetime, timezone

    raw = path.read_bytes()
    review = json.loads(raw)
    book = dataset.books[0]
    expected = {
        "schema_version": 1, "decision": "approved", "reviewer_role": "project_owner",
        "review_method": "explicit_user_confirmation_in_project_chat",
        "dataset_file": DATASET_PATH.name, "dataset_file_sha256": DATASET_SHA256,
        "source_pdf_sha256": SOURCE_SHA256,
        "approved_question_ids": [q.id for q in book.questions],
        "approved_evidence_anchor_count": sum(len(q.evidence) for q in book.questions),
        "approved_section_pages": [section.page for section in book.chapters],
        "review_timing": "after_first_bm25_evaluation",
    }
    if any(review.get(key) != value for key, value in expected.items()):
        raise ValueError("Owner review does not cover the sealed source, questions and sections.")
    flags = ("independent_blind_expert_review_claim", "label_changes", "historical_report_changes",
             "automatic_promotion_authorized", "provider_calls_authorized_by_this_receipt")
    if any(review.get(flag) is not False for flag in flags):
        raise ValueError("Unexpected owner-review provenance or authority.")
    recorded = datetime.fromisoformat(review["recorded_at_utc"].replace("Z", "+00:00"))
    if recorded.utcoffset() != timezone.utc.utcoffset(recorded):
        raise ValueError("Require a UTC review timestamp.")
    return {"id": review["review_id"], "receiptSha256": hashlib.sha256(raw).hexdigest(),
            "decision": "approved", "reviewerRole": "project_owner",
            "recordedAtUtc": review["recorded_at_utc"], "timing": review["review_timing"],
            "questionCount": len(book.questions), "evidenceAnchorCount": expected["approved_evidence_anchor_count"],
            "sectionCount": len(book.chapters), "independentBlindExpertReview": False,
            "providerCostsAuthorizedByReview": False, "automaticPromotionAuthorized": False}


def inspect_cache(inputs, cache) -> dict:
    """Inspect each distinct real cache input, with no embed/network fallback.

    Inputs are (task, exact embedding text). Only numeric counts are returned;
    neither source/query text nor any Settings object is serialized.
    """
    unique = set(inputs)
    if any(task not in {"document", "query"} or not isinstance(text, str) or not text
           for task, text in unique):
        raise ValueError("Require nonblank document/query embedding inputs.")
    counts = {task: {"uniqueInputs": 0, "cacheHits": 0, "missingInputs": 0}
              for task in ("document", "query")}
    for task, text in sorted(unique):
        counts[task]["uniqueInputs"] += 1
        if cache.read(task, text) is None:
            counts[task]["missingInputs"] += 1
        else:
            counts[task]["cacheHits"] += 1
    missing = sum(row["missingInputs"] for row in counts.values())
    return {"cacheOnly": True, "status": "ready" if missing == 0 else "blocked_missing_real_cache",
            "uniqueInputCount": len(unique), "missingInputCount": missing, "byTask": counts,
            "providerCalls": 0,
            "denseHybridScored": False,
            "note": "Readiness only; no dense/hybrid quality score, fake vectors or paid provider calls."}


# Flow: check immutable source/query seal and old policy seals -> use public,
# pinned embedding identity -> inspect distinct real cache entries -> counts.
# Purpose: preserve first-scoring provenance and verify later owner review
# without editing labels, granting provider costs or promoting runtime policy.
