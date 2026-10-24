"""Low-cardinality Prometheus metrics for the private RAG runtime."""

from __future__ import annotations

from contextlib import contextmanager
from time import perf_counter
from typing import Iterator

from prometheus_client import CONTENT_TYPE_LATEST, CollectorRegistry, Counter, Gauge, Histogram, generate_latest


HTTP_REQUESTS = Counter(
    "library_rag_http_requests_total",
    "RAG HTTP requests grouped by normalized route and status.",
    ("method", "route", "status"),
)
HTTP_DURATION = Histogram(
    "library_rag_http_request_duration_seconds",
    "RAG HTTP request latency grouped by normalized route.",
    ("method", "route"),
    buckets=(0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30, 90),
)
STAGE_DURATION = Histogram(
    "library_rag_stage_duration_seconds",
    "Latency of bounded RAG pipeline stages.",
    ("stage", "outcome"),
    buckets=(0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30, 90),
)
RETRIEVAL_OUTCOMES = Counter(
    "library_rag_retrieval_outcomes_total",
    "Retrieval calls grouped by mode and empty/non-empty outcome.",
    ("mode", "outcome"),
)
ANSWER_OUTCOMES = Counter(
    "library_rag_answer_outcomes_total",
    "Grounded answer outcomes and stable reason codes.",
    ("outcome", "reason"),
)
PROVIDER_ERRORS = Counter(
    "library_rag_provider_errors_total",
    "Sanitized LLM provider failures grouped by provider and stable code.",
    ("provider", "error_code"),
)


def record_http_request(*, method: str, route: str, status: str, duration_seconds: float) -> None:
    HTTP_REQUESTS.labels(method=method, route=route, status=status).inc()
    HTTP_DURATION.labels(method=method, route=route).observe(duration_seconds)


@contextmanager
def observe_stage(stage: str) -> Iterator[None]:
    """Measure one named stage and retain failures without logging payloads."""

    started_at = perf_counter()
    outcome = "success"
    try:
        yield
    except Exception:
        outcome = "error"
        raise
    finally:
        STAGE_DURATION.labels(stage=stage, outcome=outcome).observe(perf_counter() - started_at)


def record_retrieval(*, mode: str, result_count: int) -> None:
    RETRIEVAL_OUTCOMES.labels(
        mode=mode,
        outcome="empty" if result_count == 0 else "non_empty",
    ).inc()


def record_answer(*, outcome: str, reason: str | None = None) -> None:
    ANSWER_OUTCOMES.labels(outcome=outcome, reason=reason or "none").inc()


def record_provider_error(*, provider: str, error_code: str) -> None:
    PROVIDER_ERRORS.labels(provider=provider or "unknown", error_code=error_code).inc()


def render_metrics(*, ingestion_rows: list[dict] | None = None) -> tuple[bytes, str]:
    # Celery processes do not share the API registry. Persisted job gauges give
    # one accurate view across workers and survive API/worker restarts.
    registry = CollectorRegistry()
    database_up = Gauge(
        "library_rag_ingestion_metrics_up",
        "Whether the persisted ingestion job snapshot was loaded successfully.",
        registry=registry,
    )
    jobs = Gauge(
        "library_rag_ingestion_jobs",
        "Persisted ingestion jobs by current status, including worker jobs.",
        ("status",), registry=registry,
    )
    duration = Gauge(
        "library_rag_ingestion_job_duration_seconds_mean",
        "Mean created-to-completed duration of persisted jobs, including queue/retries.",
        ("status",), registry=registry,
    )
    database_up.set(int(ingestion_rows is not None))
    for row in ingestion_rows or []:
        jobs.labels(status=row["status"]).set(row["job_count"])
        if row["mean_duration_seconds"] is not None:
            duration.labels(status=row["status"]).set(row["mean_duration_seconds"])
    return generate_latest() + generate_latest(registry), CONTENT_TYPE_LATEST
