from dataclasses import dataclass, field
from typing import Any


@dataclass(frozen=True)
class RawChunkingPage:
    page_number: int
    text: str
    metadata: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class RawChunkingDocument:
    document_id: int
    source_type: str
    filename: str
    text: str | None = None
    pages: list[RawChunkingPage] = field(default_factory=list)
    metadata: dict[str, Any] = field(default_factory=dict)

# Context comes from the trusted upstream source, not from a RAG-local user/workspace.
@dataclass(frozen=True)
class ChunkingContext:
    source_type: str | None = None
    source_id: str | None = None
    document_version_id: int | None = None
    metadata: dict[str, Any] = field(default_factory=dict)
