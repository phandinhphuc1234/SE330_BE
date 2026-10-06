from app.ingestion.cleaners.rules.line_breaks import fix_paragraph_line_breaks


def test_fix_paragraph_line_breaks_merges_clear_wrapped_paragraph() -> None:
    result = fix_paragraph_line_breaks(
        "The RAG system retrieves\nrelevant chunks from vector\ndatabase."
    )

    assert result.text == "The RAG system retrieves relevant chunks from vector database."
    assert result.fixed_count == 2


def test_fix_paragraph_line_breaks_preserves_blank_boundary_and_sentence_end() -> None:
    text = "This sentence is complete.\nnext sentence starts lower\n\nAnother paragraph\ncontinues here."

    result = fix_paragraph_line_breaks(text)

    assert result.text == "This sentence is complete.\nnext sentence starts lower\n\nAnother paragraph continues here."
    assert result.fixed_count == 1


def test_fix_paragraph_line_breaks_ignores_structure_blocks() -> None:
    text = "\n".join(
        [
            "# Heading",
            "paragraph starts",
            "- list item",
            "continued list text",
            "| Name | Value |",
            "| --- | --- |",
            "```text",
            "some wrapped",
            "code",
            "```",
        ]
    )

    result = fix_paragraph_line_breaks(text)

    assert result.text == text
    assert result.fixed_count == 0


def test_fix_paragraph_line_breaks_does_not_merge_short_label_like_lines() -> None:
    text = "Abstract\nthis paper explains the system."

    result = fix_paragraph_line_breaks(text)

    assert result.text == text
    assert result.fixed_count == 0
