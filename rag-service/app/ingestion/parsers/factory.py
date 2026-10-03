from app.core.config import get_settings
from app.core.exceptions import ValidationError
from app.ingestion.parsers.base import DocumentParser, ParserRegistry
from app.ingestion.parsers.simple_file_parsers import DocxParser, HtmlParser, MarkdownParser
from app.ingestion.parsers.pdf.pymupdf4llm_parser import PyMuPDF4LLMParser

# Hàm này build_default_parser_registry được sử dụng để xây dựng một đối tượng 
# ParserRegistry mặc định từ các cài đặt của ứng dụng. 
# Nó lấy các cài đặt từ get_settings() và tạo ra một danh sách các 
# parser, bao gồm parser PDF được cấu hình, parser Markdown, parser HTML và parser DOCX. 
# Sau đó, nó trả về một đối tượng ParserRegistry chứa các parser này, cho phép hệ thống xác định parser 
# phù hợp dựa trên phần mở rộng tệp.
def build_default_parser_registry() -> ParserRegistry:
    """Build the parser registry from application settings."""

    settings = get_settings()
    return ParserRegistry(
        [
            build_pdf_parser(settings.pdf_parser),
            MarkdownParser(),
            HtmlParser(),
            DocxParser(),
        ]
    )


def build_pdf_parser(parser_name: str) -> DocumentParser:
    """Create the configured PDF parser.

    Future parsers such as a remote Docling parser should be added here. The
    pipeline will not need to change because it only depends on DocumentParser.
    """

    normalized = parser_name.strip().lower().replace("-", "_")
    if normalized in {"pymupdf4llm", "pymupdf_markdown", "default"}:
        return PyMuPDF4LLMParser()

    raise ValidationError(
        f"Unsupported PDF parser: {parser_name}",
        error_code="UNSUPPORTED_PDF_PARSER",
    )
