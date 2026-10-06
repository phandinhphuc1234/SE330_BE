from app.ingestion.cleaners.rules.header_footer import detect_repeated_header_footer, remove_repeated_header_footer


def test_detect_repeated_header_footer_reports_repeated_edge_lines_only() -> None:
    result = detect_repeated_header_footer(
        [
            "Library System\nChapter 1\nUnique page one\nFooter Text",
            "Library System\nChapter 1\nUnique page two\nFooter Text",
            "Library System\nChapter 1\nUnique page three\nFooter Text",
        ],
        top_lines=2,
        bottom_lines=1,
        min_repeat_ratio=0.6,
    )

    assert result.min_repeat_count == 2
    assert result.total_pages == 3
    assert [candidate.line for candidate in result.page_candidates[0]] == [
        "Library System",
        "Chapter 1",
        "Footer Text",
    ]
    assert {candidate.occurrence_count for candidate in result.page_candidates[0]} == {3}


def test_detect_repeated_header_footer_ignores_repeated_middle_lines() -> None:
    result = detect_repeated_header_footer(
        [
            "Top A\nShared Body\nBottom A",
            "Top B\nShared Body\nBottom B",
            "Top C\nShared Body\nBottom C",
        ],
        top_lines=1,
        bottom_lines=1,
        min_repeat_ratio=0.6,
    )

    assert result.page_candidates == [[], [], []]
    assert result.repeated_normalized_lines == []


def test_detect_repeated_header_footer_requires_enough_pages() -> None:
    result = detect_repeated_header_footer(
        [
            "Library System\nPage one",
            "Library System\nPage two",
        ],
        top_lines=1,
        bottom_lines=1,
        min_repeat_ratio=0.4,
    )

    assert result.page_candidates == [[], []]


def test_remove_repeated_header_footer_removes_candidates_from_edges_only() -> None:
    detection = detect_repeated_header_footer(
        [
            "Library System\nUnique one\nFooter Text",
            "Library System\nUnique two\nFooter Text",
            "Library System\nUnique three\nFooter Text",
        ],
        top_lines=1,
        bottom_lines=1,
        min_repeat_ratio=0.6,
    )

    result = remove_repeated_header_footer(
        "Library System\nUnique one\nFooter Text",
        detection.page_candidates[0],
        top_lines=1,
        bottom_lines=1,
    )

    assert result.text == "Unique one"
    assert result.removed_count == 2
    assert result.removed_lines == ["Library System", "Footer Text"]


def test_remove_repeated_header_footer_does_not_remove_middle_candidate_text() -> None:
    detection = detect_repeated_header_footer(
        [
            "Library System\nUnique one\nFooter Text",
            "Library System\nUnique two\nFooter Text",
            "Library System\nUnique three\nFooter Text",
        ],
        top_lines=1,
        bottom_lines=1,
        min_repeat_ratio=0.6,
    )

    result = remove_repeated_header_footer(
        "Different top\nLibrary System\nDifferent bottom",
        detection.page_candidates[0],
        top_lines=1,
        bottom_lines=1,
    )

    assert result.text == "Different top\nLibrary System\nDifferent bottom"
    assert result.removed_count == 0
