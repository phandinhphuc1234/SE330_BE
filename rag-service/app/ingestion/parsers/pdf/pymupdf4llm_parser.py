from pathlib import Path
from typing import Any

import pymupdf4llm

from app.ingestion.parsers.base import DocumentParser, ParsedDocument


class PyMuPDF4LLMParser(DocumentParser):
    """Default text-based PDF parser.

    It converts a PDF into Markdown-like page chunks. The parser does not own
    validation, cleaning, chunking, or persistence; those remain separate stages
    in the ingestion pipeline.
    """

    name = "pymupdf4llm"
    supported_extensions = {".pdf"}

    def parse(self, file_path: Path) -> list[ParsedDocument]:
        page_chunks = pymupdf4llm.to_markdown(
            str(file_path),
            page_chunks=True,
            ignore_images=True,
            page_separators=False,
            show_progress=False,
        )
        if isinstance(page_chunks, str):
            return [
                ParsedDocument(
                    text=page_chunks,
                    metadata={
                        "source": str(file_path),
                        "parser": self.name,
                    },
                )
            ]

        documents: list[ParsedDocument] = []
        for index, page_chunk in enumerate(page_chunks, start=1):
            documents.append(self._to_parsed_document(file_path, page_chunk, index))
        return documents

    def _to_parsed_document(self, file_path: Path, page_chunk: Any, page_number: int) -> ParsedDocument:
        if not isinstance(page_chunk, dict):
            return ParsedDocument(
                text=str(page_chunk or ""),
                metadata={
                    "source": str(file_path),
                    "parser": self.name,
                    "page_number": page_number,
                },
            )

        metadata = {
            "source": str(file_path),
            "parser": self.name,
            "page_number": page_number,
        }
        pymupdf_metadata = page_chunk.get("metadata")
        if isinstance(pymupdf_metadata, dict):
            title = pymupdf_metadata.get("title")
            if title:
                metadata["title"] = title
            page_count = pymupdf_metadata.get("page_count") or pymupdf_metadata.get("pageCount")
            if isinstance(page_count, int):
                metadata["page_count"] = page_count

        toc_items = page_chunk.get("toc_items")
        if isinstance(toc_items, list) and toc_items:
            metadata["toc_items"] = toc_items

        tables = page_chunk.get("tables")
        if isinstance(tables, list):
            metadata["table_count"] = len(tables)

        images = page_chunk.get("images")
        if isinstance(images, list):
            metadata["image_count"] = len(images)

        graphics = page_chunk.get("graphics")
        if isinstance(graphics, list):
            metadata["graphics_count"] = len(graphics)

        return ParsedDocument(
            text=str(page_chunk.get("text") or ""),
            metadata=metadata,
        )
