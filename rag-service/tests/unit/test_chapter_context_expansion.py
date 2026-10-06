"""Chapter-aware, revision-bound, independently citable context expansion."""

from copy import deepcopy
import hashlib
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest
from sqlalchemy.dialects import postgresql

from app.core.config import Settings
from app.generation.citation_builder import build_citations
from app.indexing import SearchResult
from app.indexing.qdrant_store import QdrantVectorStore, VectorChunk, deterministic_qdrant_point_id
from app.ingestion.parsers.base import ParsedDocument, ParserRegistry
from app.ingestion.pipeline import IngestionPipeline
from app.retrieval.context_expander import ContextCompressor, ContextExpander, NeighborChunk, PostgresNeighborChunkSource
from app.retrieval.retrieval_pipeline import _search_result_to_hit


def metadata(index=3, page=2, *, strategy="v2", **changes):
    value = {
        "document_id": 7, "documentId": "doc_ebook_55", "ebookId": 55, "bookId": 101,
        "chunk_index": index, "pageStart": page, "pageEnd": page,
        "embedding_version": "embedding-v1", "source_checksum_sha256": "a" * 64,
        "chunking_strategy": "library_pdf_narrative", "chunking_strategy_version": strategy,
        "chunk_size": 512, "chunk_overlap": 64, "cleaning_version": "clean-v1",
        "chapter_detected": True, "chapter_index": 1, "chapter_title": "Chương 1",
    }
    if strategy == "v2":
        value["section_id"] = "section-current-1"
    value.update(changes)
    return value


def seed(index=3, page=2, *, strategy="v2", key=None, **changes):
    key = key or f"d7-c{index}"
    return SearchResult(f"point-{key}", .8, f"Seed evidence {index}.",
                        metadata(index, page, strategy=strategy, **changes), vector_id=key)


def neighbor(index, page=2, *, strategy="v2", document=7, key=None, text=None, **changes):
    key = key or f"d{document}-c{index}"
    return NeighborChunk(document, index, text or f"Neighbor evidence {index}.", key,
                         metadata(index, page, strategy=strategy, **{"document_id": document, **changes}))


def anchor(item):
    return NeighborChunk(item.metadata["document_id"], item.metadata["chunk_index"], item.text,
                         item.vector_id, deepcopy(item.metadata))


async def expand(item, neighbors, *, window=1):
    source = SimpleNamespace(load=AsyncMock(return_value=[anchor(item), *neighbors]))
    return await ContextExpander(source=source).expand([item], window=window)


@pytest.mark.asyncio
async def test_same_section_is_expanded_across_pages_with_own_id_citation_and_no_input_mutation():
    item = seed()
    previous, following = neighbor(2, page=1), neighbor(4, page=3)
    original = deepcopy((item, previous, following))
    expanded = await expand(item, [following, previous])
    assert [r.vector_id for r in expanded] == [item.vector_id, previous.vector_id, following.vector_id]
    assert item.text == "Seed evidence 3."
    assert expanded[1].text == previous.text and expanded[2].text == following.text
    assert expanded[1].id == deterministic_qdrant_point_id("d7-c2:embedding-v1")
    assert expanded[1].metadata["context_expansion_relation"] == "same_section"
    assert expanded[1].metadata["context_expansion_distance"] == 1
    assert expanded[1].metadata["score_type"] == "seed_cosine" and expanded[1].score == item.score
    hits = [_search_result_to_hit(r) for r in expanded]
    citations = build_citations([r.vector_id for r in expanded], {h.vector_id: h for h in hits})
    assert [c["pageStart"] for c in citations] == [2, 1, 3]
    assert [c["chunkId"] for c in citations] == ["d7-c3", "d7-c2", "d7-c4"]
    assert citations[1]["excerpt"] == previous.text
    assert (item, previous, following) == original


@pytest.mark.asyncio
@pytest.mark.parametrize("change", [
    {"section_id": "another-section"}, {"section_id": None},
    {"ebookId": 56}, {"bookId": 102}, {"documentId": "other"}, {"document_id": 8},
    {"ebook_id": 56}, {"book_id": 102}, {"external_document_id": "other"},
    {"embedding_version": "old"}, {"embedding_version": None},
    {"chunking_strategy_version": "v1"}, {"chunking_strategy_version": None},
    {"source_checksum_sha256": "b" * 64}, {"source_checksum_sha256": None},
    {"cleaning_version": "old"}, {"chunk_size": 256}, {"chunk_overlap": 32},
    {"document_version_id": 2}, {"artifact_version_key": "new"}, {"source_version": "new"},
    {"active": False}, {"active": "true"}, {"vector_id": "mislabelled"},
    {"chunk_hash": "bad-hash"}, {"pageStart": 0}, {"pageEnd": 1},
    {"chapter_index": 2}, {"chapter_title": "Another chapter"}, {"chapter_detected": False},
    {"chapter_detected": "false"},
])
async def test_neighbor_with_different_unknown_or_contradictory_scope_is_not_added(change):
    item = seed()
    expanded = await expand(item, [neighbor(4, **change)])
    assert expanded == [item]


@pytest.mark.asyncio
@pytest.mark.parametrize("field", ["document_version_id", "artifact_version_key", "source_version"])
async def test_matching_additional_revision_expands_but_changed_revision_does_not(field):
    value = 1 if field.endswith("_id") else "revision-1"
    other = 2 if field.endswith("_id") else "revision-2"
    item = seed(**{field: value})
    assert len(await expand(item, [neighbor(4, **{field: value})])) == 2
    assert await expand(item, [neighbor(4, **{field: other})]) == [item]


@pytest.mark.asyncio
@pytest.mark.parametrize("change", [
    {"chapter_index": 2, "chapter_title": "Chương 2"},
    {"chapter_title": "Chương khác"}, {"chapter_index": None}, {"chapter_title": None},
    {"chapter_detected": False}, {"chapter_source_page": 99},
])
async def test_v1_chapter_boundaries_are_not_crossed(change):
    item = seed(strategy="v1", chapter_source_page=1)
    assert await expand(item, [neighbor(4, page=3, strategy="v1", **change)]) == [item]


@pytest.mark.asyncio
async def test_v1_same_chapter_expands_but_unknown_chapter_is_page_local():
    item = seed(strategy="v1")
    assert len(await expand(item, [neighbor(4, page=3, strategy="v1")])) == 2
    unknown = {"chapter_detected": False, "chapter_index": None, "chapter_title": None}
    item = seed(strategy="v1", **unknown)
    assert len(await expand(item, [neighbor(4, strategy="v1", **unknown)])) == 2
    assert await expand(item, [neighbor(4, page=3, strategy="v1", **unknown)]) == [item]
    assert await expand(item, [neighbor(4, strategy="v1")]) == [item]


@pytest.mark.asyncio
async def test_unknown_v2_section_is_page_local_and_legacy_v1_needs_a_current_anchor():
    unknown = {"chapter_detected": False, "chapter_index": None, "chapter_title": None}
    item = seed(**unknown)
    expanded = await expand(item, [neighbor(4, **unknown)])
    assert len(expanded) == 2
    assert expanded[1].metadata["context_expansion_relation"] == "same_page_fallback"
    assert await expand(item, [neighbor(4, page=3, **unknown)]) == [item]
    # Existing v1 vectors can lack raw-PDF revision metadata. Do not invent it:
    # enforce available policy versions and the exact current DB anchor instead.
    legacy = seed(strategy="v1", source_checksum_sha256=None)
    assert len(await expand(legacy, [neighbor(4, strategy="v1", source_checksum_sha256=None)])) == 2


@pytest.mark.asyncio
async def test_independently_retrieved_seeds_may_span_chapters_but_neighbors_cannot():
    first, second = seed(), seed(6, section_id="section-2", chapter_index=2, chapter_title="Chương 2")
    rows = [anchor(first), anchor(second), neighbor(4, section_id="section-2", chapter_index=2,
                                                  chapter_title="Chương 2")]
    source = SimpleNamespace(load=AsyncMock(return_value=rows))
    assert await ContextExpander(source=source).expand([first, second]) == [first, second]


@pytest.mark.asyncio
@pytest.mark.parametrize("field,value", [
    ("ebookId", None), ("ebookId", True), ("ebookId", 55.5),
    ("document_id", 0), ("embedding_version", None), ("chunk_index", True),
    ("chunk_index", -1), ("source_checksum_sha256", None), ("section_id", None),
    ("vector_id", "wrong-vector"),
])
async def test_invalid_or_unversioned_v2_seed_is_kept_without_database_query(field, value):
    item = seed(**{field: value})
    source = SimpleNamespace(load=AsyncMock())
    assert await ContextExpander(source=source).expand([item]) == [item]
    source.load.assert_not_awaited()


@pytest.mark.asyncio
async def test_snake_camel_aliases_are_resolved_without_conflicts():
    item = seed()
    item.metadata["ebook_id"] = item.metadata.pop("ebookId")
    item.metadata["book_id"] = item.metadata.pop("bookId")
    item.metadata["external_document_id"] = item.metadata.pop("documentId")
    row = neighbor(4)
    row.metadata["chunkIndex"] = row.metadata.pop("chunk_index")
    row.metadata["embeddingVersion"] = row.metadata.pop("embedding_version")
    expanded = await expand(item, [row])
    assert len(expanded) == 2
    assert expanded[1].metadata["embedding_version"] == "embedding-v1"
    assert _search_result_to_hit(expanded[1]).citation["ebookId"] == 55


@pytest.mark.asyncio
@pytest.mark.parametrize("problem", ["absent", "text", "vector", "hash", "version", "duplicate"])
async def test_missing_stale_or_ambiguous_database_anchor_does_not_borrow_neighbors(problem):
    item = seed()
    row = anchor(item)
    anchors = [row]
    if problem == "absent":
        anchors = []
    elif problem == "text":
        row = NeighborChunk(row.document_id, row.chunk_index, "Rebuilt text.", row.vector_id, row.metadata)
        anchors = [row]
    elif problem == "vector":
        row = NeighborChunk(row.document_id, row.chunk_index, row.text, "rebuilt-vector", row.metadata)
        anchors = [row]
    elif problem == "hash":
        row.metadata["chunk_hash"] = "bad-hash"
    elif problem == "version":
        row.metadata["embedding_version"] = "old"
    else:
        anchors.append(row)
    source = SimpleNamespace(load=AsyncMock(return_value=[*anchors, neighbor(4)]))
    assert await ContextExpander(source=source).expand([item]) == [item]


@pytest.mark.asyncio
@pytest.mark.parametrize("problem", ["missing", "chapter", "version", "inactive", "duplicate"])
async def test_expansion_stops_at_first_boundary_or_gap_even_if_far_chunk_matches(problem):
    item = seed()
    middle = neighbor(4)
    rows = [anchor(item), middle, neighbor(5)]
    if problem == "missing":
        rows.remove(middle)
    elif problem == "chapter":
        middle.metadata["section_id"] = "other"
    elif problem == "version":
        middle.metadata["source_checksum_sha256"] = "b" * 64
    elif problem == "inactive":
        middle.metadata["active"] = False
    else:
        rows.append(middle)
    source = SimpleNamespace(load=AsyncMock(return_value=rows))
    assert await ContextExpander(source=source).expand([item], window=2) == [item]


@pytest.mark.asyncio
async def test_merge_overlapping_windows_deduplicate_and_prefer_nearest_neighbors():
    first, second = seed(3), seed(5)
    rows = [anchor(first), anchor(second), neighbor(1), neighbor(2), neighbor(4), neighbor(6), neighbor(7)]
    source = SimpleNamespace(load=AsyncMock(return_value=list(reversed(rows))))
    expanded = await ContextExpander(source=source).expand([first, second], window=2)
    source.load.assert_awaited_once_with({7: [(1, 7)]})
    assert [r.vector_id for r in expanded] == ["d7-c3", "d7-c5", "d7-c2", "d7-c4", "d7-c6", "d7-c1", "d7-c7"]
    assert sum(r.vector_id == "d7-c4" for r in expanded) == 1
    assert expanded[0] is first and expanded[1] is second


@pytest.mark.asyncio
async def test_other_document_cannot_supply_an_anchor_or_neighbor():
    item = seed()
    source = SimpleNamespace(load=AsyncMock(return_value=[anchor(item), neighbor(4, document=8)]))
    assert await ContextExpander(source=source).expand([item]) == [item]


@pytest.mark.asyncio
@pytest.mark.parametrize("window", [-1, 4, True, 1.5])
async def test_window_is_bounded(window):
    with pytest.raises(ValueError, match="between 0 and 3"):
        await ContextExpander(source=SimpleNamespace(load=AsyncMock())).expand([seed()], window=window)


@pytest.mark.asyncio
async def test_disabled_expansion_empty_results_and_empty_ranges_do_not_query():
    source = SimpleNamespace(load=AsyncMock())
    expander = ContextExpander(source=source)
    assert await expander.expand([]) == []
    assert await expander.expand([seed()], window=0)
    source.load.assert_not_awaited()
    factory = AsyncMock()
    postgres = PostgresNeighborChunkSource(session_factory=factory, settings=Settings.model_construct())
    assert await postgres.load({}) == []
    assert await postgres.load({7: []}) == []
    factory.assert_not_called()


def database_rows(*, chunk_changes=None, document_changes=None):
    item = seed()
    chunk = SimpleNamespace(document_id=7, chunk_index=3, content=item.text, vector_id=item.vector_id,
                            metadata_=deepcopy(item.metadata))
    document = SimpleNamespace(id=7, ebook_id=55, book_id=101, external_document_id="doc_ebook_55",
                               source_type="LIBRARY_EBOOK", metadata_={
                                   "ingestion_status": "INDEXED", "embedding_version": "embedding-v1",
                                   "source_checksum_sha256": "a" * 64,
                                   "chunking_strategy_version": "v2",
                               })
    chunk.metadata_.update(chunk_changes or {})
    document.metadata_.update(document_changes or {})
    return chunk, document


async def load_postgres_rows(rows):
    session = SimpleNamespace(execute=AsyncMock(return_value=SimpleNamespace(all=lambda: rows)))
    context = AsyncMock()
    context.__aenter__.return_value = session
    source = PostgresNeighborChunkSource(session_factory=lambda: context,
                                         settings=Settings.model_construct(embedding_version="embedding-v1"))
    return await source.load({7: [(2, 4)]}), session


@pytest.mark.asyncio
async def test_postgres_sql_limits_indexed_active_current_chunk_and_document_versions():
    rows, session = await load_postgres_rows([database_rows()])
    assert len(rows) == 1
    sql = str(session.execute.call_args.args[0].compile(
        dialect=postgresql.dialect(), compile_kwargs={"literal_binds": True}))
    for clause in ("document_chunks.document_id = 7", "document_chunks.chunk_index >= 2",
                   "document_chunks.chunk_index <= 4", "LIBRARY_EBOOK", "INDEXED",
                   "document_chunks.metadata", "documents.metadata", "embedding_version", "active"):
        assert clause in sql
    assert sql.count("embedding-v1") == 2


@pytest.mark.asyncio
@pytest.mark.parametrize("change", [
    {"embedding_version": "old"}, {"embedding_version": None}, {"source_checksum_sha256": "b" * 64},
    {"source_checksum_sha256": None}, {"chunking_strategy_version": "v1"},
    {"active": False}, {"ebook_id": 56}, {"document_id": 8}, {"vector_id": "stale"},
])
async def test_postgres_never_overwrites_stale_or_conflicting_chunk_metadata_with_current_document(change):
    chunk, document = database_rows(chunk_changes=change)
    original = deepcopy(chunk.metadata_)
    rows, _ = await load_postgres_rows([(chunk, document)])
    assert rows == [] and chunk.metadata_ == original


@pytest.mark.asyncio
async def test_postgres_current_document_revision_must_match_and_content_type_is_preserved():
    chunk, document = database_rows(document_changes={"chunking_strategy_version": "v1"})
    assert (await load_postgres_rows([(chunk, document)]))[0] == []
    chunk, document = database_rows(chunk_changes={"source_type": "pdf"})
    rows, _ = await load_postgres_rows([(chunk, document)])
    assert rows[0].metadata["source_type"] == "pdf"
    assert rows[0].metadata["sourceType"] == "LIBRARY_EBOOK"
    assert rows[0].metadata["embedding_version"] == chunk.metadata_["embedding_version"]


@pytest.mark.asyncio
async def test_document_artifact_manifest_label_is_not_invented_for_chunk_revision():
    chunk, document = database_rows(document_changes={"artifact_version": "sha256-aaaaaaaaaaaa"})
    rows, _ = await load_postgres_rows([(chunk, document)])
    assert len(rows) == 1 and "artifact_version" not in rows[0].metadata
    chunk.metadata_["artifact_version"] = "sha256-other"
    assert (await load_postgres_rows([(chunk, document)]))[0] == []
    chunk, document = database_rows(document_changes={"source_checksum_sha256": "b" * 64})
    assert (await load_postgres_rows([(chunk, document)]))[0] == []


def test_qdrant_keeps_compact_section_revision_metadata_but_excludes_debug_source_spans():
    store = QdrantVectorStore(settings=Settings.model_construct(embedding_dim=3), client=SimpleNamespace())
    value = metadata(source_spans=[{"pageNumber": 1}], chunk_quality_report={"debug": True})
    payload = store._build_payload(VectorChunk("vector", "Evidence", [.1, .2, .3], value),
                                   point_id="point", vector_key="key")
    assert payload["section_id"] == value["section_id"]
    assert payload["source_checksum_sha256"] == "a" * 64
    assert "source_spans" not in payload and "chunk_quality_report" not in payload


@pytest.mark.parametrize("version", ["v1", "v2"])
def test_computed_pdf_checksum_flows_through_real_chunk_path_without_provider_io(version, monkeypatch):
    settings = Settings.model_construct(chunking_strategy_version=version)
    for target in ("app.ingestion.pipeline.get_settings", "app.ingestion.cleaners.pdf_cleaner.get_settings",
                   "app.ingestion.llama_index.node_adapter.get_settings"):
        monkeypatch.setattr(target, lambda: settings)
    monkeypatch.setattr("app.ingestion.pipeline.IngestionArtifactWriter", lambda: None)
    document = SimpleNamespace(id=7, external_document_id="doc_ebook_55", source_type="LIBRARY_EBOOK",
                               source_id="ebook:55", book_id=101, ebook_id=55, filename="original.pdf")
    parsed = [ParsedDocument("Chapter 1\n\nMira found a silver key in the library. It opened the old map cabinet.",
                             {"page_number": 1})]
    result = IngestionPipeline(parser_registry=ParserRegistry([]))._build_chunk_result(
        parsed, document=document, source_checksum_sha256="a" * 64,
    )
    assert all(c.metadata["source_checksum_sha256"] == "a" * 64 for c in result.chunks)
    assert all(c.metadata["chunking_strategy_version"] == version for c in result.chunks)


@pytest.mark.asyncio
async def test_compression_retains_neighbor_source_citation_under_global_budget():
    item = seed()
    expanded = await expand(item, [neighbor(4, page=3)])
    compressed = await ContextCompressor().compress("Neighbor evidence", expanded, max_context_chars=45)
    assert sum(len(r.context_text) for r in compressed) <= 45
    hits = [_search_result_to_hit(r) for r in compressed]
    citations = build_citations([h.vector_id for h in hits], {h.vector_id: h for h in hits})
    assert citations[1]["chunkId"] == "d7-c4" and citations[1]["pageStart"] == 3
    assert "Neighbor evidence" in citations[1]["excerpt"]
