from app.ingestion.cleaners.pdf_cleaner import PdfCleaner
from app.ingestion.parsers.base import ParsedDocument


def test_pdf_cleaner_keeps_page_boundaries_and_metadata() -> None:
    parsed_pages = [
        ParsedDocument(
            text="# Intro\n\nHello page one.",
            metadata={
                "page_number": 1,
                "parser": "pymupdf4llm",
                "bookId": 101,
            },
        ),
        ParsedDocument(
            text="Page two content.",
            metadata={
                "page_number": 2,
                "parser": "pymupdf4llm",
            },
        ),
    ]

    result = PdfCleaner().clean(parsed_pages)

    assert [page.page_number for page in result.pages] == [1, 2]
    assert [page.cleaned_text for page in result.pages] == [
        "# Intro\n\nHello page one.",
        "Page two content.",
    ]
    assert result.pages[0].metadata["parser"] == "pymupdf4llm"
    assert result.pages[0].metadata["bookId"] == 101
    assert result.pages[0].metadata["cleaning_stage"] == "contract"
    assert result.pages[0].metadata["cleaning_version"] == "pdf-clean-v1.0.0"
    assert result.quality_report.cleaning_version == "pdf-clean-v1.0.0"
    assert result.quality_report.quality_status == "GOOD"
    assert result.quality_report.can_chunk is True


def test_pdf_cleaner_builds_minimal_quality_report() -> None:
    parsed_pages = [
        ParsedDocument(text="First page", metadata={"page_number": 7}),
        ParsedDocument(text="   ", metadata={"page_number": 8}),
    ]

    result = PdfCleaner().clean(parsed_pages)

    assert result.quality_report.page_count == 2
    assert result.quality_report.raw_char_count == len("First page") + len("   ")
    assert result.quality_report.cleaned_char_count == len("First page")
    assert result.quality_report.empty_pages == 1
    assert result.quality_report.text_pages_ratio == 0.5
    assert result.quality_report.quality_status == "ACCEPTABLE"
    assert result.quality_report.can_chunk is True
    assert result.pages[1].warnings == ["EMPTY_PAGE_TEXT"]
    assert "page_8:EMPTY_PAGE_TEXT" in result.quality_report.warnings


def test_pdf_cleaner_empty_input_fails_clearly_without_crashing() -> None:
    result = PdfCleaner().clean([])

    assert result.pages == []
    assert result.quality_report.page_count == 0
    assert result.quality_report.quality_status == "FAILED"
    assert result.quality_report.can_chunk is False
    assert "NO_PARSED_PAGES" in result.quality_report.warnings


def test_pdf_cleaner_allows_cleaning_version_override() -> None:
    cleaner = PdfCleaner(cleaning_version="pdf-clean-test-v9")

    result = cleaner.clean([ParsedDocument(text="content", metadata={"page_number": 1})])

    assert result.pages[0].metadata["cleaning_version"] == "pdf-clean-test-v9"
    assert result.quality_report.cleaning_version == "pdf-clean-test-v9"


def test_pdf_cleaner_normalizes_common_pdf_unicode_artifacts() -> None:
    parsed_pages = [
        ParsedDocument(
            text="The efﬁcient workﬂow\u00a0keeps Vietnamese dấu.\u200b\x00",
            metadata={"page_number": 1},
        )
    ]

    result = PdfCleaner().clean(parsed_pages)

    assert result.pages[0].cleaned_text == "The efficient workflow keeps Vietnamese dấu."
    assert result.pages[0].metadata["unicode_normalized"] is True
    assert result.quality_report.raw_char_count == len(parsed_pages[0].text)
    assert result.quality_report.cleaned_char_count == len("The efficient workflow keeps Vietnamese dấu.")


def test_pdf_cleaner_can_disable_unicode_normalization() -> None:
    parsed_pages = [
        ParsedDocument(
            text="The efﬁcient workﬂow\u00a0keeps Vietnamese dấu.",
            metadata={"page_number": 1},
        )
    ]

    result = PdfCleaner(normalize_unicode=False).clean(parsed_pages)

    assert result.pages[0].cleaned_text == parsed_pages[0].text
    assert result.pages[0].metadata["unicode_normalized"] is False


def test_pdf_cleaner_normalizes_whitespace_conservatively() -> None:
    parsed_pages = [
        ParsedDocument(
            text="# Title\r\n\r\n\r\nParagraph   with\tspaces.   \n  - nested    item   \n\n\nNext",
            metadata={"page_number": 1},
        )
    ]

    result = PdfCleaner(max_blank_lines=1).clean(parsed_pages)

    assert result.pages[0].cleaned_text == "# Title\n\nParagraph with spaces.\n  - nested item\n\nNext"
    assert result.pages[0].metadata["spaces_collapsed"] is True
    assert result.pages[0].metadata["max_blank_lines"] == 1


def test_pdf_cleaner_can_disable_space_collapsing_but_still_caps_blank_lines() -> None:
    parsed_pages = [
        ParsedDocument(
            text="A   B\tC\n\n\nD",
            metadata={"page_number": 1},
        )
    ]

    result = PdfCleaner(collapse_spaces=False, max_blank_lines=1).clean(parsed_pages)

    assert result.pages[0].cleaned_text == "A   B C\n\nD"
    assert result.pages[0].metadata["spaces_collapsed"] is False


def test_pdf_cleaner_preserves_spacing_inside_code_and_markdown_tables() -> None:
    parsed_pages = [
        ParsedDocument(
            text=(
                "```json\n"
                '{  "enabled":  true }\n'
                "```\n\n"
                "| Name | Value |\n"
                "| ---  | ---   |\n"
                "| A    | 1     |\n\n"
                "Paragraph   text"
            ),
            metadata={"page_number": 1},
        )
    ]

    result = PdfCleaner(max_blank_lines=1).clean(parsed_pages)

    assert result.pages[0].cleaned_text == (
        "```json\n"
        '{  "enabled":  true }\n'
        "```\n\n"
        "| Name | Value |\n"
        "| ---  | ---   |\n"
        "| A    | 1     |\n\n"
        "Paragraph text"
    )


def test_pdf_cleaner_adds_line_type_metadata_for_later_chunking() -> None:
    parsed_pages = [
        ParsedDocument(
            text="# Intro\n\n- Validate PDF\n| Name | Value |\n| --- | --- |\nParagraph text",
            metadata={"page_number": 1},
        )
    ]

    result = PdfCleaner(max_blank_lines=1).clean(parsed_pages)

    metadata = result.pages[0].metadata
    assert metadata["line_count"] == 6
    assert metadata["has_structural_blocks"] is True
    assert metadata["line_type_counts"] == {
        "blank": 1,
        "heading": 1,
        "list": 1,
        "table": 2,
        "code": 0,
        "caption": 0,
        "paragraph": 1,
    }


def test_pdf_cleaner_removes_page_numbers_and_records_metadata() -> None:
    parsed_pages = [
        ParsedDocument(
            text="Page 12\n\n3.2 Borrowing Module\nThe module handles loans.\n- 12 -",
            metadata={"page_number": 12},
        )
    ]

    result = PdfCleaner(max_blank_lines=1).clean(parsed_pages)

    assert result.pages[0].cleaned_text == "3.2 Borrowing Module\nThe module handles loans."
    assert result.pages[0].metadata["removed_page_number_count"] == 2
    assert result.pages[0].metadata["removed_page_number_lines"] == ["Page 12", "- 12 -"]
    assert result.pages[0].metadata["line_type_counts"]["heading"] == 1
    assert result.pages[0].metadata["line_type_counts"]["paragraph"] == 1


def test_pdf_cleaner_can_disable_page_number_removal() -> None:
    parsed_pages = [
        ParsedDocument(
            text="Page 12\nBody\n12",
            metadata={"page_number": 12},
        )
    ]

    result = PdfCleaner(remove_page_numbers_enabled=False).clean(parsed_pages)

    assert result.pages[0].cleaned_text == "Page 12\nBody\n12"
    assert result.pages[0].metadata["page_number_removal_enabled"] is False
    assert result.pages[0].metadata["removed_page_number_count"] == 0


def test_pdf_cleaner_fixes_safe_line_end_hyphenation() -> None:
    parsed_pages = [
        ParsedDocument(
            text="This improves informa-\ntion retrieval.\n\n- state-\nof-the-art",
            metadata={"page_number": 1},
        )
    ]

    result = PdfCleaner(max_blank_lines=1).clean(parsed_pages)

    assert result.pages[0].cleaned_text == "This improves information retrieval.\n\n- state-\nof-the-art"
    assert result.pages[0].metadata["hyphenation_fix_enabled"] is True
    assert result.pages[0].metadata["fixed_hyphenation_count"] == 1


def test_pdf_cleaner_can_disable_hyphenation_fix() -> None:
    parsed_pages = [
        ParsedDocument(
            text="This improves informa-\ntion retrieval.",
            metadata={"page_number": 1},
        )
    ]

    result = PdfCleaner(fix_hyphenation_enabled=False).clean(parsed_pages)

    assert result.pages[0].cleaned_text == "This improves informa-\ntion retrieval."
    assert result.pages[0].metadata["hyphenation_fix_enabled"] is False
    assert result.pages[0].metadata["fixed_hyphenation_count"] == 0


def test_pdf_cleaner_fixes_safe_paragraph_line_breaks() -> None:
    parsed_pages = [
        ParsedDocument(
            text="The RAG system retrieves\nrelevant chunks from vector\ndatabase.\n\n- List item\ncontinued list text",
            metadata={"page_number": 1},
        )
    ]

    result = PdfCleaner(max_blank_lines=1).clean(parsed_pages)

    assert result.pages[0].cleaned_text == (
        "The RAG system retrieves relevant chunks from vector database.\n\n"
        "- List item\n"
        "continued list text"
    )
    assert result.pages[0].metadata["line_break_fix_enabled"] is True
    assert result.pages[0].metadata["fixed_line_break_count"] == 2


def test_pdf_cleaner_can_disable_line_break_fix() -> None:
    parsed_pages = [
        ParsedDocument(
            text="The RAG system retrieves\nrelevant chunks from vector\ndatabase.",
            metadata={"page_number": 1},
        )
    ]

    result = PdfCleaner(fix_line_breaks_enabled=False).clean(parsed_pages)

    assert result.pages[0].cleaned_text == "The RAG system retrieves\nrelevant chunks from vector\ndatabase."
    assert result.pages[0].metadata["line_break_fix_enabled"] is False
    assert result.pages[0].metadata["fixed_line_break_count"] == 0


def test_pdf_cleaner_detects_repeated_header_footer_candidates_without_removing() -> None:
    parsed_pages = [
        ParsedDocument(text="Library System\nChapter 1\nUnique one\nFooter Text", metadata={"page_number": 1}),
        ParsedDocument(text="Library System\nChapter 1\nUnique two\nFooter Text", metadata={"page_number": 2}),
        ParsedDocument(text="Library System\nChapter 1\nUnique three\nFooter Text", metadata={"page_number": 3}),
    ]

    result = PdfCleaner(
        header_footer_top_lines=2,
        header_footer_bottom_lines=1,
        header_footer_min_repeat_ratio=0.6,
        fix_line_breaks_enabled=False,
    ).clean(parsed_pages)

    assert result.pages[0].cleaned_text == "Library System\nChapter 1\nUnique one\nFooter Text"
    assert result.pages[0].metadata["header_footer_detection_enabled"] is True
    assert result.pages[0].metadata["repeated_header_footer_candidate_count"] == 3
    assert result.pages[0].metadata["repeated_header_footer_candidate_lines"] == [
        "Library System",
        "Chapter 1",
        "Footer Text",
    ]
    assert result.pages[0].metadata["repeated_header_footer_candidates"][0] == {
        "line": "Library System",
        "position": "top",
        "occurrence_count": 3,
    }
    assert result.pages[0].metadata["header_footer_removal_enabled"] is False
    assert result.pages[0].metadata["removed_header_footer_count"] == 0


def test_pdf_cleaner_can_disable_header_footer_detection() -> None:
    parsed_pages = [
        ParsedDocument(text="Library System\nUnique one", metadata={"page_number": 1}),
        ParsedDocument(text="Library System\nUnique two", metadata={"page_number": 2}),
        ParsedDocument(text="Library System\nUnique three", metadata={"page_number": 3}),
    ]

    result = PdfCleaner(header_footer_detection_enabled=False).clean(parsed_pages)

    assert result.pages[0].metadata["header_footer_detection_enabled"] is False
    assert result.pages[0].metadata["repeated_header_footer_candidate_count"] == 0
    assert result.pages[0].metadata["repeated_header_footer_candidate_lines"] == []


def test_pdf_cleaner_removes_repeated_header_footer_when_enabled() -> None:
    parsed_pages = [
        ParsedDocument(text="Library System\nChapter 1\nUnique one\nFooter Text", metadata={"page_number": 1}),
        ParsedDocument(text="Library System\nChapter 1\nUnique two\nFooter Text", metadata={"page_number": 2}),
        ParsedDocument(text="Library System\nChapter 1\nUnique three\nFooter Text", metadata={"page_number": 3}),
    ]

    result = PdfCleaner(
        header_footer_top_lines=2,
        header_footer_bottom_lines=1,
        header_footer_min_repeat_ratio=0.6,
        header_footer_removal_enabled=True,
        fix_line_breaks_enabled=False,
    ).clean(parsed_pages)

    assert result.pages[0].cleaned_text == "Unique one"
    assert result.pages[0].metadata["repeated_header_footer_candidate_count"] == 3
    assert result.pages[0].metadata["header_footer_removal_enabled"] is True
    assert result.pages[0].metadata["removed_header_footer_count"] == 3
    assert result.pages[0].metadata["removed_header_footer_lines"] == [
        "Library System",
        "Chapter 1",
        "Footer Text",
    ]


def test_pdf_cleaner_removes_header_footer_before_line_break_fix_runs() -> None:
    parsed_pages = [
        ParsedDocument(
            text="Library System\nThe RAG system retrieves\nrelevant chunks from vector\nFooter Text",
            metadata={"page_number": 1},
        ),
        ParsedDocument(
            text="Library System\nThe RAG system retrieves\nrelevant chunks from keyword\nFooter Text",
            metadata={"page_number": 2},
        ),
        ParsedDocument(
            text="Library System\nThe RAG system retrieves\nrelevant chunks from graph\nFooter Text",
            metadata={"page_number": 3},
        ),
    ]

    result = PdfCleaner(
        header_footer_top_lines=1,
        header_footer_bottom_lines=1,
        header_footer_min_repeat_ratio=0.6,
        header_footer_removal_enabled=True,
    ).clean(parsed_pages)

    assert result.pages[0].cleaned_text == "The RAG system retrieves relevant chunks from vector"
    assert result.pages[0].metadata["removed_header_footer_count"] == 2
    assert result.pages[0].metadata["fixed_line_break_count"] == 1
