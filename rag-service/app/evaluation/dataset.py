"""Versioned golden-dataset contract for repeatable RAG evaluation."""

from __future__ import annotations

import json
from pathlib import Path

from pydantic import BaseModel, ConfigDict, Field, model_validator


class EvaluationItem(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="forbid")

    id: str = Field(min_length=1)
    question: str = Field(min_length=1)
    book_id: int | None = Field(default=None, alias="bookId", gt=0)
    ebook_id: int | None = Field(default=None, alias="ebookId", gt=0)
    document_id: int | None = Field(default=None, alias="documentId", gt=0)
    expected_chunk_ids: list[str] = Field(default_factory=list, alias="expectedChunkIds")
    expected_pages: list[int] = Field(default_factory=list, alias="expectedPages")
    expected_answer_points: list[str] = Field(default_factory=list, alias="expectedAnswerPoints")
    answerable: bool = True
    tags: list[str] = Field(default_factory=list)

    @model_validator(mode="after")
    def validate_item(self) -> "EvaluationItem":
        if self.book_id is None and self.ebook_id is None and self.document_id is None:
            raise ValueError("bookId, ebookId, or documentId is required.")
        if self.answerable and not self.expected_chunk_ids:
            raise ValueError("answerable items require expectedChunkIds.")
        if not self.answerable and self.expected_chunk_ids:
            raise ValueError("unanswerable items must not declare expectedChunkIds.")
        return self


class EvaluationDataset(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="forbid")

    name: str = Field(min_length=1)
    version: str = Field(min_length=1)
    description: str = ""
    items: list[EvaluationItem] = Field(min_length=1)

    @model_validator(mode="after")
    def require_unique_item_ids(self) -> "EvaluationDataset":
        if len({item.id for item in self.items}) != len(self.items):
            raise ValueError("Evaluation item IDs must be unique.")
        return self


def load_evaluation_dataset(path: str | Path) -> EvaluationDataset:
    dataset_path = Path(path)
    payload = json.loads(dataset_path.read_text(encoding="utf-8"))
    return EvaluationDataset.model_validate(payload)


def select_evaluation_items(dataset: EvaluationDataset, case_ids: list[str] | None) -> EvaluationDataset:
    """Select a transparent subset without modifying the source golden file."""
    if case_ids is None:
        return dataset
    selected = set(case_ids)
    unknown = selected - {item.id for item in dataset.items}
    if not selected or unknown:
        raise ValueError("Choose existing evaluation case IDs.")
    return dataset.model_copy(update={"items": [item for item in dataset.items if item.id in selected]})
