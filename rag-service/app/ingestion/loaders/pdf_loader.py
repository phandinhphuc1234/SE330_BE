from pathlib import Path

from app.ingestion.loaders.base import RawDocument
from app.ingestion.parsers.pdf.pymupdf4llm_parser import PyMuPDF4LLMParser


class PdfLoader(PyMuPDF4LLMParser):
    """Backward-compatible wrapper around the new parser implementation.

    New code should use ``app.ingestion.parsers``. This wrapper keeps older
    imports/tests working while avoiding duplicated PDF parsing logic.
    """

    def load(self, file_path: Path) -> list[RawDocument]:
        return self.parse(file_path)
