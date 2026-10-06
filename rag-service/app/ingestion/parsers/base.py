from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from pathlib import Path

from app.core.exceptions import ValidationError


@dataclass
class ParsedDocument:
    """One parsed unit returned by a parser.

    For PDFs this normally represents one page. For simpler formats such as
    Markdown or HTML it can represent the whole file. The pipeline only depends
    on this small shape, so parser implementations can change independently.
    """

    text: str
    metadata: dict = field(default_factory=dict)


class DocumentParser(ABC):
    """Interface every ingestion parser must implement.

    This keeps the ingestion pipeline closed for modification but open for
    extension: adding Docling, Tika, or another parser should mean adding a new
    class and registering it, not rewriting pipeline orchestration.
    """

    name: str
    supported_extensions: set[str] = set()

    @abstractmethod
    def parse(self, file_path: Path) -> list[ParsedDocument]:
        """Parse a local file into page/document units."""

        raise NotImplementedError

    def supports(self, file_extension: str) -> bool:
        """Return true when this parser supports the given file extension."""

        return file_extension.lower() in self.supported_extensions


class ParserRegistry:
    """Resolve a parser by file extension.

    The registry is intentionally tiny. It lets the pipeline ask for "a parser
    for this file" without knowing whether the implementation is PyMuPDF4LLM,
    a remote Docling service, or another future parser.
    """

    def __init__(self, parsers: list[DocumentParser]) -> None:
        self.parsers = parsers

    def get_parser(self, file_path: Path) -> DocumentParser:
        extension = file_path.suffix.lower()
        for parser in self.parsers:
            if parser.supports(extension):
                return parser
        raise ValidationError(
            f"No parser registered for extension: {extension}",
            error_code="PARSER_NOT_REGISTERED",
        )
