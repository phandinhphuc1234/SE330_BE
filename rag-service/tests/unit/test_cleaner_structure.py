from app.ingestion.cleaners.rules.structure import LineType, classify_pdf_lines, count_pdf_line_types, has_structural_lines


def test_classify_pdf_lines_detects_basic_structure() -> None:
    text = "\n".join(
        [
            "# Intro",
            "3.2 Borrowing Module",
            "- Validate PDF",
            "| Name | Value |",
            "| --- | --- |",
            "Figure 2.1: System architecture",
            "    SELECT * FROM books",
            "Normal paragraph text.",
            "",
        ]
    )

    classifications = classify_pdf_lines(text)

    assert [item.line_type for item in classifications] == [
        LineType.HEADING,
        LineType.HEADING,
        LineType.LIST,
        LineType.TABLE,
        LineType.TABLE,
        LineType.CAPTION,
        LineType.CODE,
        LineType.PARAGRAPH,
        LineType.BLANK,
    ]


def test_classify_pdf_lines_tracks_fenced_code_blocks() -> None:
    text = "\n".join(
        [
            "```json",
            '{  "enabled":  true }',
            "```",
            "After code.",
        ]
    )

    classifications = classify_pdf_lines(text)

    assert [item.line_type for item in classifications] == [
        LineType.CODE,
        LineType.CODE,
        LineType.CODE,
        LineType.PARAGRAPH,
    ]


def test_classify_pdf_lines_does_not_treat_plain_page_number_as_heading() -> None:
    classifications = classify_pdf_lines("12\nPage body")

    assert classifications[0].line_type == LineType.PARAGRAPH
    assert classifications[1].line_type == LineType.PARAGRAPH


def test_count_pdf_line_types_returns_stable_metadata_shape() -> None:
    counts = count_pdf_line_types("# Intro\n\n- Validate PDF\nPlain text")

    assert set(counts) == {line_type.value for line_type in LineType}
    assert counts["heading"] == 1
    assert counts["blank"] == 1
    assert counts["list"] == 1
    assert counts["paragraph"] == 1
    assert has_structural_lines(counts) is True


def test_count_pdf_line_types_handles_empty_text_as_zero_lines() -> None:
    counts = count_pdf_line_types("")

    assert sum(counts.values()) == 0
    assert has_structural_lines(counts) is False
