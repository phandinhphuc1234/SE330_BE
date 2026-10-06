from pathlib import Path

import pytest

from app.evaluation.answer_metrics import answer_point_coverage, token_f1
from app.evaluation.dataset import EvaluationDataset, EvaluationItem, load_evaluation_dataset, select_evaluation_items
from app.evaluation.hallucination_checker import abstention_accuracy, citation_precision
from app.evaluation.retrieval_metrics import ndcg_at_k, recall_at_k, reciprocal_rank
from app.evaluation.run_eval import DEFAULT_DATASET, LLMRequestPacer, evaluate_dataset, write_reports
from app.evaluation.faithfulness_judge import FaithfulnessJudge


def test_metrics_reward_early_relevant_results_and_penalize_missing_evidence():
    relevant = {"a", "b"}
    assert recall_at_k(relevant, ["a", "c"], 2) == .5
    assert reciprocal_rank(relevant, ["c", "b"]) == .5
    assert ndcg_at_k(relevant, ["a", "b"], 2) == 1
    assert ndcg_at_k(relevant, ["c", "b"], 2) < 1
    assert citation_precision(relevant, ["a", "invented"]) == .5
    assert abstention_accuracy(expected_answerable=False, abstained=True) == 1
    assert token_f1("Della sold hair", "Della sold her hair") > .8
    assert answer_point_coverage(["hair", "twenty dollars"], "Della sold hair") == .5


def test_golden_dataset_is_versioned_scoped_and_has_abstention_cases():
    dataset = load_evaluation_dataset(DEFAULT_DATASET)
    assert dataset.version == "1.0.0"
    assert any(not item.answerable for item in dataset.items)
    assert all(item.ebook_id == 10 for item in dataset.items)


def test_dataset_rejects_unlabeled_answerable_and_missing_scope():
    with pytest.raises(ValueError, match="require expectedChunkIds"):
        EvaluationItem(id="bad", question="Question", ebookId=10)
    with pytest.raises(ValueError, match="required"):
        EvaluationItem(id="bad", question="Question", answerable=False)


def test_dataset_subset_preserves_original_and_rejects_unknown_or_duplicate_ids():
    dataset = load_evaluation_dataset(DEFAULT_DATASET)
    selected = select_evaluation_items(dataset, [dataset.items[0].id])
    assert len(selected.items) == 1 and len(dataset.items) == 20
    with pytest.raises(ValueError, match="existing"):
        select_evaluation_items(dataset, ["not-a-real-case"])
    with pytest.raises(ValueError, match="unique"):
        EvaluationDataset(name="bad", version="1", items=[dataset.items[0], dataset.items[0]])


class FakeClient:
    async def search(self, item, *, mode, top_k):
        return {"results": [{"vectorId": "a", "citation": {"ebookId": item.ebook_id}}]}, 5.0

    async def answer(self, item, *, mode, top_k):
        return {"answer": "Hair", "abstained": False,
                "citations": [{"chunkId": "a", "ebookId": item.ebook_id}]}, 10.0


@pytest.mark.asyncio
async def test_runner_aggregates_metrics_and_reports_scope_violations(tmp_path: Path):
    dataset = EvaluationDataset(name="test", version="1", items=[EvaluationItem(
        id="one", question="What?", ebookId=10, expectedChunkIds=["a"], expectedAnswerPoints=["hair"],
    )])
    report = await evaluate_dataset(dataset, client=FakeClient(), modes=["dense", "hybrid"], top_k=1)
    assert report["modes"]["hybrid"]["summary"]["hitRate@1"] == 1
    assert report["modes"]["hybrid"]["summary"]["scopeViolations"] == 0
    json_path, md_path = write_reports(report, tmp_path)
    assert json_path.is_file() and md_path.is_file()
    assert "hybrid" in md_path.read_text(encoding="utf-8")
    markdown = md_path.read_text(encoding="utf-8")
    assert markdown.index("| hybrid |") < markdown.index("## Answer metrics")


@pytest.mark.asyncio
async def test_runner_records_provider_failures_without_leaking_error_message():
    class BrokenClient:
        async def search(self, *args, **kwargs):
            raise RuntimeError("secret-key-in-url")
    dataset = EvaluationDataset(name="test", version="1", items=[EvaluationItem(
        id="one", question="What?", ebookId=10, expectedChunkIds=["a"],
    )])
    report = await evaluate_dataset(dataset, client=BrokenClient(), modes=["dense"], top_k=1)
    assert report["modes"]["dense"]["summary"]["errorCount"] == 1
    assert report["modes"]["dense"]["summary"]["hitRate@1"] == 0
    assert "secret-key-in-url" not in str(report)


@pytest.mark.asyncio
async def test_runner_reports_quota_error_phase_and_only_allowlisted_provider_codes(tmp_path):
    import httpx
    class QuotaClient(FakeClient):
        async def answer(self, *args, **kwargs):
            request = httpx.Request("POST", "http://internal/answers")
            response = httpx.Response(502, request=request, json={
                "error_code": "LLM_RATE_LIMITED", "message": "sensitive-provider-details",
            })
            response.raise_for_status()
    dataset = EvaluationDataset(name="test", version="1", items=[EvaluationItem(
        id="one", question="What?", ebookId=10, expectedChunkIds=["a"],
    )])
    report = await evaluate_dataset(dataset, client=QuotaClient(), modes=["hybrid"], top_k=1)
    row = report["modes"]["hybrid"]["items"][0]
    assert row["errorPhase"] == "answer" and row["errorCode"] == "LLM_RATE_LIMITED"
    assert row["httpStatus"] == 502
    assert "sensitive-provider-details" not in str(report)
    _, markdown = write_reports(report, tmp_path)
    assert "not a passing quality baseline" in markdown.read_text(encoding="utf-8")


@pytest.mark.asyncio
async def test_faithfulness_judge_validates_structured_verdict():
    from types import SimpleNamespace
    from unittest.mock import AsyncMock
    llm = SimpleNamespace(complete_json=AsyncMock(return_value='{"faithfulness":0.5,"unsupportedClaims":["Invented claim"]}'))
    verdict = await FaithfulnessJudge(llm_client=llm).evaluate("Some claims.", {"a": "Source evidence."})
    assert verdict.faithfulness == .5
    assert verdict.unsupported_claims == ["Invented claim"]
    assert '<source id="a">' in llm.complete_json.call_args.kwargs["user_prompt"]


@pytest.mark.asyncio
async def test_evaluation_pacer_shares_budget_without_real_sleep():
    from unittest.mock import AsyncMock
    moments = iter([100.0, 100.0, 102.0, 115.0])
    sleep = AsyncMock()
    pacer = LLMRequestPacer(15, clock=lambda: next(moments), sleep=sleep)
    await pacer.wait()
    await pacer.wait()
    sleep.assert_awaited_once_with(13.0)


@pytest.mark.parametrize("interval", [-1, 61, float("nan"), float("inf")])
def test_evaluation_pacer_rejects_invalid_interval(interval):
    with pytest.raises(ValueError):
        LLMRequestPacer(interval)
