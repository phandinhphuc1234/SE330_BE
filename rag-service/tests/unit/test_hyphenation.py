from app.ingestion.cleaners.rules.hyphenation import fix_line_end_hyphenation


def test_fix_line_end_hyphenation_joins_clear_paragraph_wrap() -> None:
    result = fix_line_end_hyphenation("This improves informa-\ntion retrieval.")

    assert result.text == "This improves information retrieval."
    assert result.fixed_count == 1


def test_fix_line_end_hyphenation_ignores_lists_and_headings() -> None:
    text = "# RAG-\nsystem\n- state-\nof-the-art\nParagraph stays."

    result = fix_line_end_hyphenation(text)

    assert result.text == text
    assert result.fixed_count == 0


def test_fix_line_end_hyphenation_does_not_join_when_next_line_starts_uppercase() -> None:
    text = "The module supports-\nLibrary Operations"

    result = fix_line_end_hyphenation(text)

    assert result.text == text
    assert result.fixed_count == 0


def test_fix_line_end_hyphenation_ignores_code_and_tables() -> None:
    text = "\n".join(
        [
            "```text",
            "some-variable-",
            "name",
            "```",
            "| key | state- |",
            "| --- | --- |",
        ]
    )

    result = fix_line_end_hyphenation(text)

    assert result.text == text
    assert result.fixed_count == 0
