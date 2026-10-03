from pathlib import Path

from app.ingestion.parsers.pdf.pymupdf4llm_parser import PyMuPDF4LLMParser


def test_pymupdf4llm_parser_uses_page_chunks(monkeypatch, tmp_path) -> None:
    pdf_path = tmp_path / "book.pdf"
    pdf_path.write_bytes(b"%PDF-1.7\n")

    captured: dict = {}

    def fake_to_markdown(path: str, **kwargs):
        captured["path"] = path
        captured["kwargs"] = kwargs
        return [
            {
                "metadata": {"title": "Clean Architecture", "page_count": 2},
                "toc_items": [[1, "Intro", 1]],
                "tables": [{"bbox": [0, 0, 10, 10]}],
                "images": [],
                "graphics": [{}, {}],
                "text": "# Intro\n\nHello from page one.",
            },
            {
                "metadata": {"title": "Clean Architecture", "page_count": 2},
                "toc_items": [],
                "tables": [],
                "images": [],
                "graphics": [],
                "text": "Page two content.",
            },
        ]

    monkeypatch.setattr(
        "app.ingestion.parsers.pdf.pymupdf4llm_parser.pymupdf4llm.to_markdown",
        fake_to_markdown,
    )

    documents = PyMuPDF4LLMParser().parse(pdf_path)

    assert captured["path"] == str(pdf_path)
    assert captured["kwargs"]["page_chunks"] is True
    assert captured["kwargs"]["ignore_images"] is True

    assert [document.text for document in documents] == [
        "# Intro\n\nHello from page one.",
        "Page two content.",
    ]
    assert documents[0].metadata == {
        "source": str(pdf_path),
        "parser": "pymupdf4llm",
        "page_number": 1,
        "title": "Clean Architecture",
        "page_count": 2,
        "toc_items": [[1, "Intro", 1]],
        "table_count": 1,
        "image_count": 0,
        "graphics_count": 2,
    }
    assert documents[1].metadata["page_number"] == 2
    assert documents[1].metadata["parser"] == "pymupdf4llm"


def test_pymupdf4llm_parser_handles_single_markdown_string(monkeypatch, tmp_path) -> None:
    pdf_path = tmp_path / "book.pdf"
    pdf_path.write_bytes(b"%PDF-1.7\n")
    monkeypatch.setattr(
        "app.ingestion.parsers.pdf.pymupdf4llm_parser.pymupdf4llm.to_markdown",
        lambda *_args, **_kwargs: "whole document markdown",
    )

    documents = PyMuPDF4LLMParser().parse(Path(pdf_path))

    assert len(documents) == 1
    assert documents[0].text == "whole document markdown"
    assert documents[0].metadata == {
        "source": str(pdf_path),
        "parser": "pymupdf4llm",
    }
