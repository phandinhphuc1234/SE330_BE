"""Fair evidence labels, isolated I/O, real-vector-only modes and safe reports."""

from copy import deepcopy
import hashlib
import json

import pytest

from app.core.config import Settings
from app.evaluation.chunking_comparison import (
    AnchoredEvidence, BookLabel, ComparisonDataset, EvidenceLabel, QuestionLabel,
    ChapterLabel, LocalVectorStore, anchor_questions, build_chunk_result, chapter_audit,
    cosine, evaluate_version, evidence_metrics, locate_heading_start, locate_quote, retained_context, source_ranges,
)
from app.indexing import SearchResult
from app.indexing.base import VectorSearchQuery
from app.ingestion.chunkers.base import Chunk
from app.ingestion.parsers.base import ParsedDocument
from scripts.benchmark_book_chunking import CachedGemini, build_report, main, markdown_report, parse_pdf, provider_error_category


def book(**updates):
    values = dict(id="test", title="Test book", filename="test.pdf", sha256="a" * 64,
                  source_url="https://example.org/test.pdf", rights_url="https://example.org/rights",
                  questions=[QuestionLabel(id="fact", question="Where is the brass key?", evidence=[
                      EvidenceLabel(page=1, quote="The brass key is under the old door.")])])
    return BookLabel(**(values | updates))


@pytest.mark.parametrize("filename", ["../secret.pdf", "nested/test.pdf", "C:\\secret.pdf", "no.txt"])
def test_dataset_rejects_pdf_paths(filename):
    with pytest.raises(ValueError):
        book(filename=filename)


def test_dataset_rejects_duplicate_ids_and_whitespace_labels():
    with pytest.raises(ValueError):
        book(questions=[book().questions[0]] * 2)
    with pytest.raises(ValueError):
        book(questions=[QuestionLabel(id="q", question="q", evidence=[EvidenceLabel(page=1, quote=" ")])])
    with pytest.raises(ValueError):
        ComparisonDataset(name="test", version="1", books=[book(), book()])


def test_quote_locator_ignores_whitespace_but_preserves_words_and_punctuation():
    assert locate_quote("prefix The  brass\nkey. suffix", "The brass key.") == (7, 22)
    for quote in ("the brass key.", "The brass keys.", "The brass key!", ""):
        with pytest.raises(ValueError):
            locate_quote("The brass key.", quote)
    with pytest.raises(ValueError):
        locate_quote("key and key", "key")


def test_evidence_labels_must_match_actual_page():
    with pytest.raises(ValueError, match="page missing"):
        anchor_questions(book(), {2: "The brass key is under the old door."})
    with pytest.raises(ValueError, match="test/fact/page-1"):
        anchor_questions(book(), {1: "Another text."})


def test_recall_uses_shared_source_positions_not_chunk_ids_or_pages_alone():
    evidence = [AnchoredEvidence(1, "abcdef", frozenset(range(6)))]
    results = [SearchResult("a", 1, "abc", vector_id="v2-a"), SearchResult("b", .5, "def", vector_id="v2-b")]
    ranges = {"v2-a": [(1, 0, 3)], "v2-b": [(1, 3, 6)]}
    assert evidence_metrics(evidence, results, ranges, 1)["evidenceRecall"] == 0
    assert evidence_metrics(evidence, results, ranges, 1)["sourceCharacterCoverage"] == .5
    assert evidence_metrics(evidence, results, ranges, 2)["complete"] == 1
    wrong_page = {"v2-a": [(2, 0, 3)], "v2-b": [(2, 3, 6)]}
    assert evidence_metrics(evidence, results, wrong_page, 2)["hit"] == 0
    assert retained_context(evidence, results, ranges) == 0  # Not a single citation.


def test_context_metric_scores_selected_context_not_hidden_original_text():
    evidence = [AnchoredEvidence(1, "brass key", frozenset(range(9)))]
    result = SearchResult("a", 1, "brass key", vector_id="a", context_text="unrelated")
    assert retained_context(evidence, [result], {"a": [(1, 0, 9)]}) == 0
    result.context_text = "brass key"
    assert retained_context(evidence, [result], {"a": [(1, 0, 9)]}) == 1


def test_v2_source_ranges_reject_rewriting_and_unmapped_text():
    chunk = Chunk("abc def", {"chunking_strategy_version": "v2", "pageStart": 1, "pageEnd": 2,
                              "source_spans": [
                                  {"pageNumber": 1, "sourceCharStart": 0, "sourceCharEnd": 3, "chunkCharStart": 0, "chunkCharEnd": 3},
                                  {"pageNumber": 2, "sourceCharStart": 0, "sourceCharEnd": 3, "chunkCharStart": 4, "chunkCharEnd": 7}]})
    assert source_ranges(chunk, {1: "abc", 2: "def"}) == [(1, 0, 3), (2, 0, 3)]
    with pytest.raises(ValueError, match="rewritten"):
        source_ranges(chunk, {1: "abc", 2: "xyz"})
    chunk.metadata["source_spans"].pop()
    with pytest.raises(ValueError, match="cover all"):
        source_ranges(chunk, {1: "abc", 2: "def"})


def test_v1_mapping_rejects_ambiguous_repetitions():
    with pytest.raises(ValueError, match="uniquely"):
        source_ranges(Chunk("key", {"pageStart": 1}), {1: "key key"})


def test_chapter_oracle_is_independent_of_chunker_metadata():
    labeled = book(chapters=[ChapterLabel(page=1, heading="Chapter 1", title="Chapter 1")])
    text = "before\nChapter 1\nThe brass key is under the old door."
    chunk = Chunk(text, {"vector_id": "mixed", "chapter_detected": True, "chapter_title": "Chapter 1"})
    audit = chapter_audit(labeled, {1: text}, [chunk], {"mixed": [(1, 0, len(text))]})
    assert audit["mixedChapterChunkCount"] == audit["chapterLabelMismatchCount"] == 1


@pytest.mark.parametrize("heading", ["APPENDIX.", "#### **APPENDIX.**"])
def test_chapter_oracle_includes_markup_prefix_without_changing_reviewed_labels(heading):
    labeled = book(chapters=[ChapterLabel(page=1, heading=heading, title="APPENDIX.")])
    text = "Chapter tail.\n\n#### **APPENDIX.**\n\nThe brass key is under the old door."
    start = text.index("####")
    chunk = Chunk(text[start:], {"vector_id": "appendix", "chapter_detected": True, "chapter_title": "APPENDIX."})
    audit = chapter_audit(labeled, {1: text}, [chunk], {"appendix": [(1, start, len(text))]})
    assert audit["chapterLabelMismatchCount"] == audit["mixedChapterChunkCount"] == 0
    assert locate_heading_start(text, heading) == start


@pytest.mark.parametrize("text", ["See APPENDIX. for sources.", "APPENDIX. additional prose",
                                  "Chapter I.\nand more", "    APPENDIX."])
def test_heading_oracle_rejects_inline_or_indented_labels(text):
    label = "Chapter I.\nand more" if text.startswith("Chapter") else "APPENDIX."
    with pytest.raises(ValueError, match="standalone"):
        locate_heading_start(text, label)


@pytest.mark.parametrize("left,right", [([1], [1, 2]), ([0, 0], [1, 2]), ([float('nan')], [1]), ([], [])])
def test_cosine_rejects_invalid_vectors(left, right):
    with pytest.raises(ValueError):
        cosine(left, right)


@pytest.mark.asyncio
async def test_local_vector_store_enforces_filters_and_cannot_write():
    chunks = [Chunk("allowed", {"vector_id": "a", "ebook_id": 1}), Chunk("denied", {"vector_id": "b", "ebook_id": 2})]
    store = LocalVectorStore(chunks, [[1, 0], [1, 0]])
    results = await store.search(VectorSearchQuery([1, 0], 5, {"ebook_id": 1}))
    assert [item.vector_id for item in results] == ["a"]
    for call in (store.upsert([]), store.delete([])):
        with pytest.raises(AssertionError):
            await call


@pytest.mark.asyncio
async def test_real_chunk_path_and_bm25_do_not_enter_provider_db_or_storage(monkeypatch):
    def forbidden(*args, **kwargs):
        raise AssertionError("No ingestion/provider/runtime I/O is permitted.")
    for target in ("app.ingestion.pipeline.async_session_factory", "app.ingestion.pipeline.IngestionPipeline.run",
                   "app.ingestion.artifacts.writer.get_object_storage_adapter",
                   "app.ingestion.pipeline.IngestionPipeline._index_chunks"):
        monkeypatch.setattr(target, forbidden)
    parsed = [ParsedDocument("Chapter 1\n\nThe brass key is under the old door.", {"page_number": 1})]
    original = deepcopy(parsed)
    hashes = []
    for version in ("v1", "v2"):
        result = build_chunk_result(parsed, book(), version=version, document_id=1)
        report = await evaluate_version(book(), result, document_id=1, modes=("bm25",), settings=Settings.model_construct())
        assert report["nonWhitespaceSourceCoverage"] == 1
        assert report["retrieval"]["bm25"]["metricsAtK"]["1"]["evidenceRecall"] == 1
        hashes.append(report["cleanedPageHashes"])
    assert hashes[0] == hashes[1] and parsed == original


@pytest.mark.asyncio
async def test_dense_mode_rejects_missing_real_vectors():
    result = build_chunk_result([ParsedDocument("The brass key is under the old door.", {"page_number": 1})],
                                book(), version="v1", document_id=1)
    with pytest.raises(ValueError, match="real embeddings"):
        await evaluate_version(book(), result, document_id=1, modes=("dense",), settings=Settings.model_construct())


@pytest.mark.asyncio
async def test_embedding_cache_reuses_inputs_and_separates_tasks(tmp_path):
    class Provider:
        calls = 0
        async def embed(self, texts):
            self.calls += 1
            return [[1., 0.] for _ in texts]
        async def embed_query(self, text):
            self.calls += 1
            return [0., 1.]
    provider = Provider()
    cache = CachedGemini(provider, cache_dir=tmp_path, settings=Settings.model_construct(embedding_dim=2), interval=0)
    assert await cache.embed_documents(["one", "one"]) == [[1, 0], [1, 0]]
    assert await cache.embed_query("one") == [0, 1]
    assert await cache.embed_documents(["one"]) == [[1, 0]]
    assert provider.calls == 2
    assert cache.key("query", "one") != cache.key("document", "one")
    alternate = CachedGemini(provider, cache_dir=tmp_path, settings=Settings.model_construct(embedding_dim=3), interval=0)
    assert alternate.read("document", "one") is None
    # Only opaque input hashes and vectors, never API key/Settings/text.
    assert all(set(json.loads(path.read_text())) == {"key", "vector"} for path in tmp_path.glob("*.json"))


def test_cli_prevents_overwrite_before_any_pdf_or_provider_read(tmp_path, monkeypatch):
    target = tmp_path / "keep.json"
    target.write_text("user-owned")
    monkeypatch.setattr("scripts.benchmark_book_chunking.load_dataset", lambda _: pytest.fail("No input read"))
    assert main(["--output", str(target), "--gemini"]) == 1
    assert target.read_text() == "user-owned"


def test_checksum_gate_precedes_pdf_parser_or_cache(tmp_path, monkeypatch):
    target = tmp_path / "test.pdf"
    target.write_bytes(b"different source")
    monkeypatch.setattr("scripts.benchmark_book_chunking.PyMuPDF4LLMParser.parse", lambda *_: pytest.fail("No parser call"))
    with pytest.raises(ValueError, match="checksum"):
        parse_pdf(target, "a" * 64, cache_dir=tmp_path)


def test_report_does_not_claim_default_promotion_or_llm_quality():
    report = {"generatedAt": "test", "errorCount": 1, "books": [],
              "decision": {"recommendation": "HOLD"}, "limitations": ["No LLM quality claim"],
              "embedding": {"status": "failed"}}
    text = markdown_report(report)
    assert "HOLD" in text and "FAILED" in text and "LLM generation: false" in text


@pytest.mark.asyncio
@pytest.mark.parametrize("failure", ["budget", "provider"])
async def test_failed_dense_attempt_preserves_bm25_but_is_not_passing_baseline(tmp_path, monkeypatch, failure):
    monkeypatch.setattr("scripts.benchmark_book_chunking.parse_pdf", lambda *args, **kwargs: [
        ParsedDocument("The brass key is under the old door.", {"page_number": 1})])
    monkeypatch.setattr("scripts.benchmark_book_chunking.get_settings", lambda: Settings.model_construct(gemini_api_key="DO_NOT_SERIALIZE"))
    def provider(*args, **kwargs):
        if failure == "budget":
            pytest.fail("Input budget must fail before provider creation")
        raise RuntimeError("DO_NOT_SERIALIZE raw provider failure")
    monkeypatch.setattr("app.indexing.providers.GeminiEmbeddingProvider", provider)
    dataset = ComparisonDataset(name="test", version="1", books=[book()])
    report = await build_report(dataset, tmp_path, gemini=True, max_embedding_texts=1 if failure == "budget" else 400)
    assert report["errorCount"] == 1 and report["embedding"]["status"] == "failed"
    assert report["books"][0]["versions"]["v1"]["retrieval"]["bm25"]["caseCount"] == 1
    assert not report["decision"]["gates"]["denseHybridCompleted"]
    assert report["decision"]["defaultStrategy"] == "v1"
    assert "DO_NOT_SERIALIZE" not in json.dumps(report)


def test_reviewed_real_book_dataset_is_separate_from_runtime_chunk_id_golden_set():
    from scripts.benchmark_book_chunking import DEFAULT_DATASET
    from app.evaluation.chunking_comparison import load_dataset
    dataset = load_dataset(DEFAULT_DATASET)
    assert len(dataset.books) == 2
    assert sum(len(item.questions) for item in dataset.books) == 20
    assert all(len(item.sha256) == 64 for item in dataset.books)
    assert all("expectedChunkIds" not in q.model_dump() for item in dataset.books for q in item.questions)


@pytest.mark.parametrize("message,category", [
    ("429 quota secret-key", "rate_or_quota"), ("403 permission secret-key", "authentication_or_permission"),
    ("503 unavailable secret-key", "network_or_provider_unavailable"),
    ("input budget exceeded", "input_budget"), ("400 invalid_argument", "invalid_input_or_response"),
    ("unexpected secret-key", "unclassified"),
])
def test_provider_error_diagnostics_never_echo_secret_messages(message, category):
    assert provider_error_category(RuntimeError(message)) == category
