from celery.schedules import crontab

beat_schedule = {
    "cleanup-orphan-chunks-daily": {
        "task": "app.jobs.cleanup_jobs.cleanup_orphan_chunks",
        "schedule": crontab(hour=2, minute=0),
    },
    "health-check-vector-store": {
        "task": "app.jobs.cleanup_jobs.health_check_vector_store",
        "schedule": crontab(minute="*/5"),
    },
}
