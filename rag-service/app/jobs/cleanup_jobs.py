from app.jobs.celery_app import celery_app


@celery_app.task(name="app.jobs.cleanup_jobs.cleanup_orphan_chunks")
def cleanup_orphan_chunks() -> dict:
    return {"status": "ok"}


@celery_app.task(name="app.jobs.cleanup_jobs.health_check_vector_store")
def health_check_vector_store() -> dict:
    return {"status": "ok"}
