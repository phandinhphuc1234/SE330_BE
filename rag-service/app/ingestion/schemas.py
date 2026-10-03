from datetime import datetime

from pydantic import BaseModel, ConfigDict


class IngestionJobRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    document_id: int
    status: str
    stage: str | None = None
    task_id: str | None = None
    attempts: int
    error_message: str | None = None
    metadata_: dict
    created_at: datetime
    updated_at: datetime
    completed_at: datetime | None = None


class IngestionUploadResponse(BaseModel):
    document_id: int
    ingestion_job_id: int
    task_id: str | None
    status: str
    filename: str
    storage_path: str
