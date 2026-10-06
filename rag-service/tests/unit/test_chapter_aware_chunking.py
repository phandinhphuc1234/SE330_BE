"""Synthetic acceptance tests for the opt-in chapter/source-mapping v2."""

from copy import deepcopy
import hashlib
import json

from llama_index.core import Document
from llama_index.core.schema import MetadataMode, NodeRelationship, TextNode
import pytest
from pydantic import ValidationError

from app.core.config import Settings
from app.ingestion.chunking import ChapterAwareChunkingStrategy, LlamaSentenceChunkingStrategy, select_chunking_strategy
from app.ingestion.chunking.chapter_boundaries import find_chapter_boundaries
from app.ingestion.chunking.chapter_aware_strategy import _locate_chunks
from scripts.benchmark_chunking import build_case_result, build_report, load_cases, main


def page(text: str, number: int = 1, **metadata) -> Document:
    return Document(text=text, metadata={
        "document_id": 7, "documentId": "doc_ebook_55", "bookId": 101,
        "ebookId": 55, "page_number": number, **metadata,
    })


def split(documents, *, size=512, overlap=64):
    return ChapterAwareChunkingStrategy().build_nodes(documents, chunk_size=size, chunk_overlap=overlap)


def content(node) -> str:
    return node.get_content(metadata_mode=MetadataMode.NONE)


@pytest.fixture(scope="module")
def report() -> dict:
    return build_report(strategy_version="v2")


@pytest.fixture(scope="module")
def cases(report) -> dict:
    return {case["id"]: case for case in report["cases"]}


@pytest.mark.parametrize("case_id", [case["id"] for case in load_cases()])
def test_v2_preserves_source_coverage_mapping_hash_scope_and_citation(cases, case_id) -> None:
    case = cases[case_id]
    pages = {p["metadata"]["page_number"]: p["cleaned"] for p in case["pages"]}
    covered = {number: set() for number in pages}
    assert case["qualityReport"]["status"] == "PASS"
    assert [c["metadata"]["chunk_index"] for c in case["chunks"]] == list(range(len(case["chunks"])))
    for chunk in case["chunks"]:
        text, metadata, citation = chunk["text"], chunk["metadata"], chunk["citation"]
        assert metadata["chunking_strategy_version"] == "v2"
        assert metadata["source_mapping_version"] == "cleaned_page_chars_v1"
        assert metadata["chunk_hash"] == hashlib.sha256(text.encode("utf-8")).hexdigest()
        assert metadata["section_char_end"] - metadata["section_char_start"] == len(text)
        assert metadata["token_count"] > 0
        assert citation["bookId"] == 101 and citation["ebookId"] == 55
        assert citation["documentId"] == "doc_ebook_55"
        assert citation["vectorId"] == metadata["vector_id"]
        spans = metadata["source_spans"]
        assert citation["pageStart"] == min(s["pageNumber"] for s in spans)
        assert citation["pageEnd"] == max(s["pageNumber"] for s in spans)
        chunk_covered = set()
        previous_end = 0
        for span in spans:
            source = pages[span["pageNumber"]][span["sourceCharStart"]:span["sourceCharEnd"]]
            assert 0 <= span["chunkCharStart"] < span["chunkCharEnd"] <= len(text)
            assert span["chunkCharStart"] >= previous_end
            assert source == text[span["chunkCharStart"]:span["chunkCharEnd"]]
            covered[span["pageNumber"]].update(range(span["sourceCharStart"], span["sourceCharEnd"]))
            chunk_covered.update(range(span["chunkCharStart"], span["chunkCharEnd"]))
            previous_end = span["chunkCharEnd"]
        # Only inserted page separators may be outside mapped source spans.
        assert all(character.isspace() or index in chunk_covered for index, character in enumerate(text))
    for number, text in pages.items():
        assert all(character.isspace() or index in covered[number] for index, character in enumerate(text))


@pytest.mark.parametrize("case_id, first, second", [
    ("vi_mid_page_chapter", "Chương 1", "Chương 2"),
    ("en_mid_page_chapter", "Chapter 1", "Chapter 2"),
    ("vi_two_chapters_one_page", "Chương 1", "Chương 2"),
    ("en_two_chapters_one_page", "Chapter 1", "Chapter 2"),
])
def test_v2_splits_at_mid_page_and_late_headings(cases, case_id, first, second) -> None:
    chunks = cases[case_id]["chunks"]
    assert {c["metadata"]["chapter_title"] for c in chunks} == {first, second}
    for chunk in chunks:
        assert not (first in chunk["text"] and second in chunk["text"])
        if second in chunk["text"]:
            assert chunk["metadata"]["chapter_title"] == second
    if case_id == "vi_mid_page_chapter":
        old_tail = next(c for c in chunks if "Cuối chương cũ" in c["text"])
        assert old_tail["metadata"]["chapter_title"] == first
        assert old_tail["citation"]["pageEnd"] == 2


@pytest.mark.parametrize("case_id, sentence", [
    ("vi_cross_page_sentence", "vẫn nằm ở dưới bậc cửa của phòng đọc cũ"),
    ("en_cross_page_sentence", "was hidden beneath the old reading room doorstep"),
])
def test_v2_reconstructs_a_sentence_and_cites_both_pages(cases, case_id, sentence) -> None:
    chunks = cases[case_id]["chunks"]
    assert len(chunks) == 1
    assert sentence in chunks[0]["text"]
    assert chunks[0]["citation"]["pageStart"] == 1
    assert chunks[0]["citation"]["pageEnd"] == 2
    assert len(chunks[0]["metadata"]["source_spans"]) == 2


@pytest.mark.parametrize("case_id", ["vi_no_chapter_heading", "en_no_chapter_heading"])
def test_unknown_chapter_stays_page_local_without_inventing_a_title(cases, case_id) -> None:
    chunks = cases[case_id]["chunks"]
    assert len(chunks) == 2
    for chunk in chunks:
        assert chunk["metadata"]["chapter_detected"] is False
        assert "chapter_title" not in chunk["metadata"]
        assert "chapterTitle" not in chunk["citation"]
        assert chunk["citation"]["pageStart"] == chunk["citation"]["pageEnd"]
    if case_id.startswith("vi"):
        assert "“Đừng làm mất chiếc chìa khóa này.”" in chunks[0]["text"]


def test_v2_long_sections_have_real_overlap(cases) -> None:
    chunks = cases["en_long_page_overlap"]["chunks"]
    assert len(chunks) >= 2
    for left, right in zip(chunks, chunks[1:]):
        lm, rm = left["metadata"], right["metadata"]
        assert lm["section_id"] == rm["section_id"]
        assert lm["section_char_start"] < rm["section_char_start"] < lm["section_char_end"]
        assert rm["section_char_end"] > lm["section_char_end"]


@pytest.mark.parametrize("heading, number", [
    ("Chương 2", "2"), ("Chapter IV", "IV"), ("Chapter One: The Arrival", "One"),
    ("## Chapter 2 The Arrival", "2"), ("**Chương 3 — Thư viện**", "3"),
    ("Epilogue", "Epilogue"), ("Prologue: Before the rain", "Prologue"),
])
def test_conservative_heading_detector_accepts_supported_formats(heading, number) -> None:
    boundaries, fence = find_chapter_boundaries("Some old content.\n\n" + heading + "\n\nNew content.")
    assert fence is None
    assert len(boundaries) == 1
    assert boundaries[0].number == number
    assert boundaries[0].offset == len("Some old content.\n\n")


@pytest.mark.parametrize("line", [
    "Chapter 2 explains the problem.", "Chapter IVory", "Chapter 1st", "Introduction to Java",
    "Chapter 2 .... 14", "Chapter 2…14", "| Chapter 2 | 14 |", "> Chapter 2", "- Chapter 2",
    "    Chapter 2", '"Chapter 2"', "“Chương 2”", "Chapter 2?", "chapterhouse",
])
def test_ambiguous_body_toc_table_quote_and_code_do_not_create_chapters(line) -> None:
    assert find_chapter_boundaries(line)[0] == []


def test_fenced_code_across_pages_is_not_a_chapter_or_sentence_join() -> None:
    nodes = split([
        page("Chapter 1\n\nA passage before code.\n\n```text\nleft_code", 1),
        page("right_code\nChapter 2\n```\n\nA passage after code.", 2),
        page("Chapter 3\n\nThe actual new chapter.", 3),
    ])
    assert {n.metadata["chapter_title"] for n in nodes} == {"Chapter 1", "Chapter 3"}
    assert "left_code\n\nright_code" in content(nodes[0])
    assert "Chapter 2" in content(nodes[0])


@pytest.mark.parametrize("change", [
    {"ebookId": 56}, {"bookId": 102}, {"documentId": "another_document"},
    {"document_id": 8}, {"source_version": "changed"}, {"document_version": "changed"},
    {"source_checksum_sha256": "changed"},
    {"document_version_id": 2}, {"documentVersionId": 2}, {"sourceType": "changed"},
])
def test_chapters_and_chunks_cannot_cross_source_identity_or_version(change) -> None:
    nodes = split([page("Chapter 1\n\nAn unfinished sentence", 1), page("continues here.", 2, **change)])
    assert len(nodes) == 2
    assert nodes[0].metadata["chapter_title"] == "Chapter 1"
    assert nodes[1].metadata["chapter_detected"] is False
    assert all(n.metadata["pageStart"] == n.metadata["pageEnd"] for n in nodes)


@pytest.mark.parametrize("second_page", [1, 3])
def test_noncontiguous_or_repeated_page_numbers_break_chapter_carry(second_page) -> None:
    nodes = split([page("Chapter 1\n\nAn unfinished sentence", 1), page("continues here.", second_page)])
    assert len(nodes) == 2
    assert nodes[1].metadata["chapter_detected"] is False


def test_empty_page_breaks_continuity_and_missing_identity_does_not_join() -> None:
    nodes = split([page("Chapter 1\n\nAn unfinished sentence", 1), page("", 2), page("continues here.", 3)])
    assert len(nodes) == 2 and nodes[1].metadata["chapter_detected"] is False
    nodes = split([
        Document(text="Chapter 1\n\nAn unfinished sentence", metadata={"page_number": 1}),
        Document(text="continues here.", metadata={"page_number": 2}),
    ])
    assert len(nodes) == 2 and nodes[1].metadata["chapter_detected"] is False


def test_prefix_before_first_heading_keeps_unknown_chapter_and_source_text() -> None:
    nodes = split([page("Prefatory text that is not a detected chapter.\n\nChapter 1\n\nThe story begins.")])
    assert len(nodes) == 2
    assert nodes[0].metadata["chapter_detected"] is False
    assert content(nodes[0]) == "Prefatory text that is not a detected chapter."
    assert nodes[1].metadata["chapter_title"] == "Chapter 1"


def test_old_page_wide_chapter_labels_are_not_trusted_and_inputs_not_mutated() -> None:
    document = page("Chapter 1\n\nOld chapter text.\n\nChapter 2\n\nNew chapter text.",
                    chapter_title="wrong", chapter_index=99, chapter_detected=True,
                    section_path=["wrong"], chapter_source_page=99)
    original = deepcopy(document.model_dump())
    nodes = split([document])
    assert [n.metadata["chapter_title"] for n in nodes] == ["Chapter 1", "Chapter 2"]
    assert document.model_dump() == original


def test_zero_overlap_maps_repeated_passages_to_later_occurrences() -> None:
    text = "Same sentence. Same sentence. Same sentence."
    ranges = _locate_chunks(text, [TextNode(text="Same sentence.")] * 3, chunk_overlap=0)
    assert ranges == [(0, 14), (15, 29), (30, 44)]


@pytest.mark.parametrize("overlap", [0, 8])
def test_real_splitter_maps_repeated_text_across_pages_and_only_cites_touched_pages(overlap) -> None:
    documents = [
        page("Chapter 1\n\n" + "The same repeated sentence stays on this page. " * 15, 1),
        page("The same repeated sentence stays on this page. " * 15, 2),
    ]
    nodes = split(documents, size=48, overlap=overlap)
    assert any(n.metadata["pageStart"] == n.metadata["pageEnd"] == 1 for n in nodes)
    assert any(n.metadata["pageStart"] == n.metadata["pageEnd"] == 2 for n in nodes)
    covered = {1: set(), 2: set()}
    for node in nodes:
        for span in node.metadata["source_spans"]:
            original = documents[span["pageNumber"] - 1].text
            assert original[span["sourceCharStart"]:span["sourceCharEnd"]] == content(node)[
                span["chunkCharStart"]:span["chunkCharEnd"]]
            covered[span["pageNumber"]].update(range(span["sourceCharStart"], span["sourceCharEnd"]))
    for document in documents:
        assert all(c.isspace() or i in covered[document.metadata["page_number"]]
                   for i, c in enumerate(document.text))


def test_overlap_and_node_relationships_do_not_cross_chapter_boundary() -> None:
    nodes = split([page(
        "Chapter 1\n\n" + "Mira follows the first old library passage. " * 15
        + "\n\nChapter 2\n\n" + "Tomas follows a different harbor passage. " * 15,
    )], size=64, overlap=16)
    by_id = {node.id_: node for node in nodes}
    assert len(nodes) > 2
    for node in nodes:
        title = node.metadata["chapter_title"]
        assert not ("first old library" in content(node) and "different harbor" in content(node))
        for direction in (NodeRelationship.PREVIOUS, NodeRelationship.NEXT):
            related = node.relationships.get(direction)
            if related is not None:
                assert by_id[related.node_id].metadata["chapter_title"] == title


def test_source_mapping_rejects_skipped_or_altered_splitter_text() -> None:
    for original, chunks in [
        ("Original text.", ["Changed text."]),
        ("Lost text. Kept text.", ["Kept text."]),
        ("Kept text. Lost text.", ["Kept text."]),
    ]:
        with pytest.raises(ValueError, match="source content"):
            _locate_chunks(original, [TextNode(text=c) for c in chunks], chunk_overlap=0)


def test_settings_and_selector_keep_v1_default_and_require_explicit_v2(monkeypatch) -> None:
    monkeypatch.delenv("CHUNKING_STRATEGY_VERSION", raising=False)
    assert Settings(_env_file=None).chunking_strategy_version == "v1"
    assert isinstance(select_chunking_strategy("novel_narrative"), LlamaSentenceChunkingStrategy)
    monkeypatch.setenv("CHUNKING_STRATEGY_VERSION", "v2")
    assert Settings(_env_file=None).chunking_strategy_version == "v2"
    assert isinstance(select_chunking_strategy("novel_narrative", strategy_version="v2"), ChapterAwareChunkingStrategy)
    with pytest.raises(ValueError, match="Unsupported"):
        select_chunking_strategy("novel_narrative", strategy_version="v3")
    monkeypatch.setenv("CHUNKING_STRATEGY_VERSION", "v3")
    with pytest.raises(ValidationError):
        Settings(_env_file=None)


def test_v2_report_repeatability_and_config_isolation(report, monkeypatch) -> None:
    monkeypatch.setenv("CHUNKING_STRATEGY_VERSION", "v1")
    monkeypatch.setenv("CHUNK_SIZE", "37")
    assert build_report(strategy_version="v2") == report
    case = load_cases()[0]
    original = deepcopy(case)
    build_case_result(case, strategy_version="v2")
    assert case == original


def test_v2_cli_json_and_no_false_snapshot_claim(report, capsys) -> None:
    assert main(["--strategy", "v2", "--json"]) == 0
    assert json.loads(capsys.readouterr().out) == report
    assert main(["--strategy", "v2", "--check"]) == 1
    assert "no reviewed snapshot" in capsys.readouterr().out


def test_v2_benchmark_never_enters_ingestion_io(report, monkeypatch) -> None:
    def forbidden(*args, **kwargs):
        raise AssertionError("Synthetic v2 benchmark must not enter ingestion I/O.")
    for target in (
        "app.ingestion.pipeline.async_session_factory", "app.ingestion.pipeline.GeminiEmbeddingProvider",
        "app.ingestion.pipeline.QdrantVectorStore", "app.ingestion.pipeline.IngestionPipeline.run",
        "app.ingestion.artifacts.writer.get_object_storage_adapter",
        "app.ingestion.parsers.base.ParserRegistry.get_parser",
    ):
        monkeypatch.setattr(target, forbidden)
    assert build_report(strategy_version="v2") == report
