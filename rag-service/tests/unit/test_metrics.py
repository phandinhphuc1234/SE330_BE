from fastapi.testclient import TestClient

from app.core.ingestion_metrics import get_ingestion_metrics
from app.main import create_app


def test_metrics_endpoint_exposes_normalized_http_metrics_and_request_id() -> None:
    app = create_app()
    app.dependency_overrides[get_ingestion_metrics] = lambda: [
        {"status": "INDEXED", "job_count": 3, "mean_duration_seconds": 70.5},
    ]
    client = TestClient(app)

    health = client.get("/api/v1/health", headers={"X-Request-ID": "metrics-smoke-request"})
    metrics = client.get("/metrics")

    assert health.status_code == 200
    assert health.headers["X-Request-ID"] == "metrics-smoke-request"
    assert metrics.status_code == 200
    assert metrics.headers["content-type"].startswith("text/plain")
    assert "library_rag_http_requests_total" in metrics.text
    assert 'route="/api/v1/health"' in metrics.text
    assert 'status="200"' in metrics.text
    assert "library_rag_http_request_duration_seconds" in metrics.text
    assert "library_rag_stage_duration_seconds" in metrics.text
    assert "library_rag_retrieval_outcomes_total" in metrics.text
    assert "library_rag_answer_outcomes_total" in metrics.text
    assert "library_rag_provider_errors_total" in metrics.text
    assert 'library_rag_ingestion_jobs{status="INDEXED"} 3.0' in metrics.text
    assert 'library_rag_ingestion_job_duration_seconds_mean{status="INDEXED"} 70.5' in metrics.text
    assert 'library_rag_ingestion_metrics_up 1.0' in metrics.text


def test_db_outage_preserves_api_metrics_and_marks_ingestion_snapshot_down() -> None:
    app = create_app()
    app.dependency_overrides[get_ingestion_metrics] = lambda: None
    response = TestClient(app).get("/metrics")

    assert response.status_code == 200
    assert "library_rag_http_requests_total" in response.text
    assert "library_rag_ingestion_metrics_up 0.0" in response.text


def test_dynamic_and_unknown_paths_do_not_create_per_id_metric_labels() -> None:
    from fastapi import APIRouter

    app = create_app()
    router = APIRouter()

    @router.get("/jobs/{job_id}")
    async def job(job_id: int):
        return {"jobId": job_id}

    app.include_router(router, prefix="/metric-test")
    app.dependency_overrides[get_ingestion_metrics] = lambda: []
    client = TestClient(app)
    client.get("/metric-test/jobs/123456789")
    client.get("/metric-test/jobs/987654321")
    client.get("/unknown-private-id-123456789")
    metrics = client.get("/metrics").text

    assert 'route="/metric-test/jobs/{job_id}"' in metrics
    assert 'route="unmatched"' in metrics
    assert 'route="/metric-test/jobs/123456789"' not in metrics
    assert 'route="/unknown-private-id-123456789"' not in metrics


def test_production_rag_api_has_no_published_host_port() -> None:
    from pathlib import Path
    import yaml

    production_compose = Path(__file__).resolve().parents[3] / "deploy" / "compose.rag.production.yaml"
    config = yaml.safe_load(production_compose.read_text(encoding="utf-8"))
    assert not config["services"]["rag-api"].get("ports")
    assert not config["services"]["rag-worker"].get("ports")
