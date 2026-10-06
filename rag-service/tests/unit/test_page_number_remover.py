from app.ingestion.cleaners.rules.page_number_remover import remove_page_numbers


def test_remove_page_numbers_removes_common_edge_patterns() -> None:
    text = "\n".join(
        [
            "Page 12 of 200",
            "",
            "3.2 Borrowing Module",
            "The borrowing module allows users to reserve books.",
            "- 12 -",
        ]
    )

    result = remove_page_numbers(text)

    assert result.text == "\n3.2 Borrowing Module\nThe borrowing module allows users to reserve books."
    assert result.removed_count == 2
    assert result.removed_lines == ["Page 12 of 200", "- 12 -"]


def test_remove_page_numbers_keeps_section_numbers() -> None:
    result = remove_page_numbers("3.2 Borrowing Module\nBody\n12")

    assert result.text == "3.2 Borrowing Module\nBody"
    assert result.removed_lines == ["12"]


def test_remove_page_numbers_does_not_delete_page_like_text_in_short_body() -> None:
    result = remove_page_numbers("Intro\nPage 12\nBody")

    assert result.text == "Intro\nPage 12\nBody"
    assert result.removed_count == 0
