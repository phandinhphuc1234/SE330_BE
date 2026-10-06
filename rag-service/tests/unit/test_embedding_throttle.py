"""Local token-window admission tests use a virtual clock; no provider calls."""

import json
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from app.evaluation.vietnamese_source_validation import EMBEDDING_IDENTITY
from scripts.benchmark_vietnamese_embeddings import BoundedProvider, TokenPacedCache
from scripts.embedding_throttle import (
    DOCUMENT_BATCH_SIZE, DOCUMENT_BATCH_UNITS, TokenWindowLimiter, document_batches, estimate_units,
)


class Clock:
    def __init__(self):
        self.now = 0.0
        self.delays = []

    def read(self):
        return self.now

    async def sleep(self, delay):
        self.delays.append(delay)
        self.now += delay


@pytest.mark.parametrize("text", ["", "  ", None, 1])
def test_blank_or_nontext_rejected(text):
    with pytest.raises(ValueError):
        estimate_units(text)


def test_estimator_counts_exact_utf8_payload_not_english_chars_div_four():
    text = "task: search result | query: thư viện"
    assert estimate_units(text) == len(text.encode("utf-8")) + 32
    assert estimate_units(text) > len(text)


def test_small_batches_preserve_exact_order_and_both_bounds():
    texts = [str(i) + "x" * 1200 for i in range(20)]
    batches = list(document_batches(texts))
    assert [text for batch in batches for text in batch] == texts
    assert all(len(batch) <= DOCUMENT_BATCH_SIZE and sum(estimate_units(t) for t in batch) <= DOCUMENT_BATCH_UNITS
               for batch in batches)
    with pytest.raises(ValueError):
        list(document_batches(["x" * DOCUMENT_BATCH_UNITS]))


@pytest.mark.asyncio
async def test_rolling_window_waits_on_token_weight_not_only_request_count():
    clock = Clock()
    limiter = TokenWindowLimiter(units=100, requests=60, clock=clock.read, sleep=clock.sleep, log_wait=False)
    await limiter.acquire(70)
    await limiter.acquire(20)
    assert clock.now == 0
    await limiter.acquire(20)
    assert clock.now == 61 and sum(clock.delays) == 61
    assert max(clock.delays) <= 30 and limiter.max_window_units == 90
    assert limiter.report()["totalReservedUnits"] == 110
    assert limiter.report()["exactGeminiTokenCount"] is False


@pytest.mark.asyncio
async def test_rpm_is_also_bounded_when_queries_are_short():
    clock = Clock()
    limiter = TokenWindowLimiter(units=10000, requests=2, clock=clock.read, sleep=clock.sleep, log_wait=False)
    for _ in range(3):
        await limiter.acquire(1)
    assert clock.now == 61 and limiter.max_window_requests == 2


@pytest.mark.asyncio
@pytest.mark.parametrize("cost", [0, -1, True, 101])
async def test_invalid_cost_rejected_before_wait(cost):
    clock = Clock()
    limiter = TokenWindowLimiter(units=100, clock=clock.read, sleep=clock.sleep, log_wait=False)
    with pytest.raises(ValueError):
        await limiter.acquire(cost)
    assert not clock.delays and not limiter.events


@pytest.mark.asyncio
async def test_failed_provider_attempt_keeps_tokens_and_submission_budget():
    clock = Clock()
    limiter = TokenWindowLimiter(clock=clock.read, sleep=clock.sleep, log_wait=False)
    provider = SimpleNamespace(embed=AsyncMock(side_effect=RuntimeError("DO_NOT_EXPORT_KEY")))
    bounded = BoundedProvider(provider, allowed_inputs={("document", "a")}, max_inputs=1, limiter=limiter)
    with pytest.raises(RuntimeError):
        await bounded.embed(["a"])
    assert bounded.counters()["submittedInputCount"] == 1
    assert limiter.report()["totalReservedUnits"] == estimate_units("a")
    assert "DO_NOT_EXPORT" not in json.dumps(limiter.report())


@pytest.mark.asyncio
async def test_unregistered_or_over_budget_input_does_not_even_reserve_tokens():
    limiter = TokenWindowLimiter()
    provider = SimpleNamespace(embed=AsyncMock())
    bounded = BoundedProvider(provider, allowed_inputs={("document", "a")}, max_inputs=0, limiter=limiter)
    for text in ("unregistered", "a"):
        with pytest.raises(ValueError):
            await bounded.embed([text])
    assert limiter.total_units == 0 and not bounded.submitted
    provider.embed.assert_not_awaited()


@pytest.mark.asyncio
async def test_cache_keeps_first_successful_small_batch_if_later_batch_fails(tmp_path):
    texts = [f"unit-{i}" for i in range(16)]
    provider = SimpleNamespace(embed=AsyncMock(side_effect=[[[1, 0] for _ in range(8)], RuntimeError("DO_NOT_EXPORT")]))
    clock = Clock()
    bounded = BoundedProvider(provider, allowed_inputs={("document", t) for t in texts}, max_inputs=16,
                              limiter=TokenWindowLimiter(clock=clock.read, sleep=clock.sleep, log_wait=False))
    cache = TokenPacedCache(bounded, cache_dir=tmp_path, settings=EMBEDDING_IDENTITY.settings().model_copy(
        update={"embedding_dim": 2}), interval=0)
    with pytest.raises(RuntimeError):
        await cache.embed_documents(texts)
    assert all(cache.read("document", text) == [1, 0] for text in texts[:8])
    assert all(cache.read("document", text) is None for text in texts[8:])
    assert bounded.request_count == 2 and len(bounded.submitted) == 16 and bounded.successful_inputs == 8
