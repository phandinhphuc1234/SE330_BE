import pytest

from app.core.exceptions import ValidationError
from app.ingestion.parsers.base import DocumentParser, ParsedDocument, ParserRegistry
from app.ingestion.parsers.factory import build_pdf_parser
from app.ingestion.parsers.pdf.pymupdf4llm_parser import PyMuPDF4LLMParser


class FakeParser(DocumentParser):
    name = "fake"
    supported_extensions = {".fake"}

    def parse(self, file_path):
        return [ParsedDocument(text="fake", metadata={"source": str(file_path), "parser": self.name})]


def test_parser_registry_resolves_parser_by_file_extension(tmp_path) -> None:
    file_path = tmp_path / "sample.fake"
    registry = ParserRegistry([FakeParser()])

    parser = registry.get_parser(file_path)

    assert parser.name == "fake"


def test_parser_registry_rejects_unregistered_extension(tmp_path) -> None:
    registry = ParserRegistry([FakeParser()])

    with pytest.raises(ValidationError) as exc_info:
        registry.get_parser(tmp_path / "sample.pdf")

    assert exc_info.value.error_code == "PARSER_NOT_REGISTERED"


def test_build_pdf_parser_returns_default_pymupdf4llm_parser() -> None:
    parser = build_pdf_parser("pymupdf4llm")

    assert isinstance(parser, PyMuPDF4LLMParser)
    assert parser.name == "pymupdf4llm"


def test_build_pdf_parser_rejects_unsupported_parser() -> None:
    with pytest.raises(ValidationError) as exc_info:
        build_pdf_parser("unknown-parser")

    assert exc_info.value.error_code == "UNSUPPORTED_PDF_PARSER"
