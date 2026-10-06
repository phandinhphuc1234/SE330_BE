"""v1 characterization tests. Passing these does not mean chapter quality is solved."""

from copy import deepcopy
import hashlib
import json

import pytest

from scripts.benchmark_chunking import (
    build_case_result,
    build_report,
    check_snapshot,
    load_cases,
    main,
)


@pytest.fixture(scope="module")
def report() -> dict:
    return build_report()


@pytest.fixture(scope="module")
def cases(report) -> dict:
    return {case["id"]: case for case in report["cases"]}


def test_v1_report_matches_reviewed_snapshot(report) -> None:
    check_snapshot(report)


def test_v1_report_is_repeatable_and_independent_of_runtime_env(report, monkeypatch) -> None:
    monkeypatch.setenv("CHUNK_SIZE", "37")
    monkeypatch.setenv("CHUNK_OVERLAP", "1")
    monkeypatch.setenv("PDF_CLEAN_MAX_BLANK_LINES", "0")
    monkeypatch.setenv("PDF_CLEAN_HEADER_FOOTER_REMOVAL_ENABLED", "true")
    assert build_report() == report
    serialized = json.dumps(report, ensure_ascii=False)
    assert '"node_id"' not in serialized
    assert "object_storage_secret_key" not in serialized


def test_baseline_does_not_mutate_input_case() -> None:
    case = load_cases()[0]
    original = deepcopy(case)
    build_case_result(case)
    assert case == original


def test_baseline_never_enters_ingestion_io(monkeypatch, report) -> None:
    def forbidden(*args, **kwargs):
        raise AssertionError("The chunking baseline must not enter ingestion/provider I/O.")

    for target in (
        "app.ingestion.pipeline.async_session_factory",
        "app.ingestion.pipeline.GeminiEmbeddingProvider",
        "app.ingestion.pipeline.QdrantVectorStore",
        "app.ingestion.artifacts.writer.get_object_storage_adapter",
        "app.ingestion.pipeline.IngestionPipeline.run",
        "app.ingestion.pipeline.IngestionPipeline._embed_chunks",
        "app.ingestion.pipeline.IngestionPipeline._upsert_embedded_chunks",
        "app.ingestion.parsers.base.ParserRegistry.get_parser",
    ):
        monkeypatch.setattr(target, forbidden)
    assert build_report() == report


@pytest.mark.parametrize("case_id", [case["id"] for case in load_cases()])
def test_baseline_chunks_keep_text_hash_scope_and_page_citation(cases, case_id) -> None:
    case = cases[case_id]
    source_pages = {page["metadata"]["page_number"]: page["cleaned"] for page in case["pages"]}
    assert case["qualityReport"]["status"] == "PASS"
    assert case["qualityReport"]["chunk_count"] == len(case["chunks"])
    assert [chunk["metadata"]["chunk_index"] for chunk in case["chunks"]] == list(range(len(case["chunks"])))
    for chunk in case["chunks"]:
        metadata, citation, text = chunk["metadata"], chunk["citation"], chunk["text"]
        page = metadata["page_number"]
        assert text in source_pages[page]
        assert metadata["chunk_hash"] == hashlib.sha256(text.encode("utf-8")).hexdigest()
        assert metadata["chunking_strategy"] == "library_pdf_narrative"
        assert metadata["chunking_strategy_version"] == "v1"
        assert metadata["chunk_size"] == 512
        assert metadata["chunk_overlap"] == 64
        assert metadata["token_count"] > 0
        assert metadata["token_counter"] == "approx_whitespace_char_v1"
        assert citation["pageStart"] == citation["pageEnd"] == page
        assert citation["bookId"] == 101
        assert citation["ebookId"] == 55
        assert citation["documentId"] == "doc_ebook_55"
        assert citation["vectorId"] == metadata["vector_id"]
        assert citation["chunkIndex"] == metadata["chunk_index"]


def test_v1_characterizes_mid_page_heading_applying_to_old_content(cases) -> None:
    case = cases["vi_mid_page_chapter"]
    chunks = [chunk for chunk in case["chunks"] if chunk["citation"]["pageStart"] == 2]
    assert any("Cuối chương cũ" in chunk["text"] for chunk in chunks)
    # Known v1 limitation, NOT the required behavior of future chapter-aware v2.
    assert all(chunk["metadata"]["chapter_title"] == "Chương 2" for chunk in chunks)


def test_v1_characterizes_heading_beyond_scan_window_being_missed(cases) -> None:
    case = cases["en_mid_page_chapter"]
    page = case["pages"][1]
    assert "Chapter 2" in page["cleaned"]
    assert page["metadata"]["chapter_detected_on_page"] is False
    assert page["metadata"]["chapter_title"] == "Chapter 1"
    assert all(chunk["metadata"]["chapter_title"] == "Chapter 1" for chunk in case["chunks"])


@pytest.mark.parametrize("case_id, first, second", [
    ("vi_two_chapters_one_page", "Chương 1", "Chương 2"),
    ("en_two_chapters_one_page", "Chapter 1", "Chapter 2"),
])
def test_v1_characterizes_two_chapters_sharing_one_chunk(cases, case_id, first, second) -> None:
    chunks = cases[case_id]["chunks"]
    assert len(chunks) == 1
    assert first in chunks[0]["text"] and second in chunks[0]["text"]
    assert chunks[0]["metadata"]["chapter_title"] == first


@pytest.mark.parametrize("case_id, sentence", [
    ("vi_cross_page_sentence", "vẫn nằm ở dưới bậc cửa của phòng đọc cũ"),
    ("en_cross_page_sentence", "was hidden beneath the old reading room doorstep"),
])
def test_v1_characterizes_sentence_staying_split_across_pages(cases, case_id, sentence) -> None:
    case = cases[case_id]
    assert len(case["chunks"]) == 2
    assert all(sentence not in chunk["text"] for chunk in case["chunks"])
    assert sentence in " ".join(page["cleaned"] for page in case["pages"])
    assert [chunk["citation"]["pageStart"] for chunk in case["chunks"]] == [1, 2]


@pytest.mark.parametrize("case_id", ["vi_no_chapter_heading", "en_no_chapter_heading"])
def test_no_heading_does_not_invent_a_chapter(cases, case_id) -> None:
    for chunk in cases[case_id]["chunks"]:
        assert chunk["metadata"]["chapter_detected"] is False
        assert "chapter_title" not in chunk["metadata"]
        assert "chapterTitle" not in chunk["citation"]
    if case_id.startswith("vi"):
        assert "“Đừng làm mất chiếc chìa khóa này.”" in cases[case_id]["chunks"][0]["text"]


@pytest.mark.parametrize("case_id, title", [
    ("vi_chapter_carry_forward", "Chương 3"),
    ("en_chapter_carry_forward", "Chapter 3"),
])
def test_page_top_heading_is_carried_forward_without_changing_page(cases, case_id, title) -> None:
    case = cases[case_id]
    assert case["pages"][0]["metadata"]["chapter_detected_on_page"] is True
    assert case["pages"][1]["metadata"]["chapter_detected_on_page"] is False
    assert all(chunk["citation"]["chapterTitle"] == title for chunk in case["chunks"])
    assert [chunk["citation"]["pageStart"] for chunk in case["chunks"]] == [1, 2]


def test_long_page_has_real_splits_and_within_page_overlap(cases) -> None:
    chunks = cases["en_long_page_overlap"]["chunks"]
    assert len(chunks) >= 2
    assert all(chunk["citation"]["pageStart"] == chunk["citation"]["pageEnd"] == 1 for chunk in chunks)
    for left, right in zip(chunks, chunks[1:]):
        left_words, right_words = left["text"].split(), right["text"].split()
        # Check actual shared text, not an assumed characters-to-tokens ratio.
        assert any(
            left_words[-size:] == right_words[:size]
            for size in range(3, min(len(left_words), len(right_words)) + 1)
        )


def test_fixture_loader_rejects_duplicate_page_numbers(tmp_path) -> None:
    payload = {"schemaVersion": 1, "cases": [{
        "id": "invalid", "language": "en", "purpose": "invalid page numbering",
        "pages": [
            {"pageNumber": 1, "paragraphs": ["One page."]},
            {"pageNumber": 1, "paragraphs": ["Another page."]},
        ],
    }]}
    path = tmp_path / "invalid.json"
    path.write_text(json.dumps(payload), encoding="utf-8")
    with pytest.raises(ValueError, match="positive and increasing"):
        load_cases(path)


def test_snapshot_comparison_rejects_drift(report) -> None:
    changed = deepcopy(report)
    changed["cases"][0]["chunks"][0]["text"] += " unintended drift"
    with pytest.raises(ValueError, match="baseline changed"):
        check_snapshot(changed)


def test_cli_check_succeeds_without_writing_files(capsys) -> None:
    assert main(["--check"]) == 0
    assert "snapshot matches" in capsys.readouterr().out


def test_cli_json_stdout_is_parseable(capsys, report) -> None:
    assert main(["--json"]) == 0
    assert json.loads(capsys.readouterr().out) == report


def test_cli_never_overwrites_existing_output(tmp_path, capsys) -> None:
    target = tmp_path / "keep.json"
    target.write_text("user-owned content", encoding="utf-8")
    assert main(["--output", str(target)]) == 1
    assert target.read_text(encoding="utf-8") == "user-owned content"
    assert "failed" in capsys.readouterr().out
