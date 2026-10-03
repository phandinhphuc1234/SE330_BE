from pydantic import BaseModel


class DocumentRead(BaseModel):
    id: int
    filename: str
    storage_path: str


class DocumentChunkRead(BaseModel):
    id: int
    document_id: int
    chunk_index: int
    content: str
    metadata_: dict
