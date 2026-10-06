"""Source-anchored chunking evaluation, independent of version-specific IDs.

The same reviewed page/quote labels are used for both chunkers. This module
does not download documents, access a database, or change application settings.
"""

from __future__ import annotations

from collections import defaultdict
from contextlib import ExitStack
from dataclasses import dataclass
import hashlib
import json
import math
from pathlib import Path
import re
from types import SimpleNamespace
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, StrictInt, model_validator

from app.core.config import Settings
from app.indexing import LibraryVectorSearchScope, SearchResult, VectorStore
from app.indexing.base import VectorSearchQuery
from app.indexing.search_filters import build_library_vector_filters
from app.ingestion.parsers.base import ParsedDocument, ParserRegistry
from app.retrieval.context_expander import ContextCompressor, ContextExpander, NeighborChunk
from app.retrieval.keyword_retriever import KeywordCandidate, KeywordRetriever
from app.retrieval.library_vector_retrieval import (
    LibraryVectorRetrievalRequest, LibraryVectorRetrievalService, _build_citation,
)
from app.retrieval.retrieval_pipeline import RetrievalPipeline
from app.retrieval.ranking_trace import RankingTrace
from scripts.benchmark_chunking import BASELINE_CONFIG


class LabelModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class EvidenceLabel(LabelModel):
    page: StrictInt = Field(gt=0)
    quote: str = Field(min_length=1)


class QuestionLabel(LabelModel):
    id: str = Field(min_length=1)
    question: str = Field(min_length=1)
    evidence: list[EvidenceLabel] = Field(min_length=1)
    tags: list[str] = Field(default_factory=list)


class ChapterLabel(LabelModel):
    page: StrictInt = Field(gt=0)
    heading: str = Field(min_length=1)
    # Metadata title is reviewed separately from PDF/Markdown markup.
    title: str = Field(min_length=1)


class BookLabel(LabelModel):
    id: str = Field(min_length=1)
    title: str = Field(min_length=1)
    filename: str
    sha256: str = Field(pattern=r"^[a-f0-9]{64}$")
    source_url: str = Field(pattern=r"^https://")
    rights_url: str = Field(pattern=r"^https://")
    questions: list[QuestionLabel] = Field(min_length=1)
    chapters: list[ChapterLabel] = Field(default_factory=list)
    chapter_audit_start_page: StrictInt = Field(default=1, gt=0)

    @model_validator(mode="after")
    def safe_labels(self):
        if (Path(self.filename).name != self.filename or "/" in self.filename
                or "\\" in self.filename or not self.filename.endswith(".pdf")):
            raise ValueError("PDF filename must be a basename, not a path.")
        if len({item.id for item in self.questions}) != len(self.questions):
            raise ValueError("Question IDs must be unique within a book.")
        if any(not item.quote.strip() for q in self.questions for item in q.evidence):
            raise ValueError("Evidence quotes must not be whitespace-only.")
        if [item.page for item in self.chapters] != sorted(item.page for item in self.chapters):
            raise ValueError("Chapter labels must be in source order.")
        return self


class ComparisonDataset(LabelModel):
    schema_version: StrictInt = 1
    name: str
    version: str
    books: list[BookLabel] = Field(min_length=1)
    split: Literal["development", "query_holdout"] = "development"
    query_language: Literal["en", "vi"] = "en"
    source_language: Literal["en", "vi"] = "en"
    review_status: Literal["assistant_source_checked", "human_reviewed"] = "assistant_source_checked"
    baseline_dataset_sha256: str | None = Field(default=None, pattern=r"^[a-f0-9]{64}$")

    @model_validator(mode="after")
    def unique_books(self):
        if self.schema_version != 1 or len({book.id for book in self.books}) != len(self.books):
            raise ValueError("Require schema version 1 and unique book IDs.")
        if self.split == "query_holdout" and not self.baseline_dataset_sha256:
            raise ValueError("Query holdout must pin the development dataset checksum.")
        return self


def load_dataset(path: Path) -> ComparisonDataset:
    return ComparisonDataset.model_validate(json.loads(path.read_text(encoding="utf-8")))


def load_holdout_baseline(dataset: ComparisonDataset, path: Path) -> dict[str, BookLabel]:
    """Refuse baseline drift or duplicate questions before PDF/provider work."""
    if dataset.split != "query_holdout":
        return {}
    if hashlib.sha256(path.read_bytes()).hexdigest() != dataset.baseline_dataset_sha256:
        raise ValueError("Development dataset checksum changed; review the holdout split.")
    baseline = load_dataset(path)
    if baseline.split != "development":
        raise ValueError("Holdout must reference a development dataset.")
    if dataset.source_language != baseline.source_language:
        raise ValueError("Query holdout cannot relabel the source language.")
    original_books = {book.id: book for book in baseline.books}
    for book in dataset.books:
        original = original_books.get(book.id)
        if original is None or original.model_dump(exclude={"questions"}) != book.model_dump(exclude={"questions"}):
            raise ValueError("Query holdout must keep the same source checksum/title/chapter labels/metadata.")
        ids = {question.id for question in original.questions}
        queries = {canonical(question.question).casefold() for question in original.questions}
        if any(question.id in ids or canonical(question.question).casefold() in queries for question in book.questions):
            raise ValueError("Holdout question IDs/text must be disjoint from development.")
    return original_books


def validate_holdout_anchors(book: BookLabel, baseline: BookLabel, pages: dict[int, str]) -> None:
    """Reject translated/reworded labels that reuse development evidence."""
    original_positions: dict[int, set[int]] = defaultdict(set)
    for labels in anchor_questions(baseline, pages).values():
        for label in labels:
            original_positions[label.page].update(label.positions)
    for labels in anchor_questions(book, pages).values():
        for label in labels:
            if label.positions & original_positions[label.page]:
                raise ValueError("Query holdout evidence overlaps development source characters.")


def canonical(text: str) -> str:
    """Ignore whitespace only, never punctuation, case or source words."""
    return re.sub(r"\s+", "", text)


def locate_quote(text: str, quote: str) -> tuple[int, int]:
    positions = [index for index, char in enumerate(text) if not char.isspace()]
    source, expected = canonical(text), canonical(quote)
    start = source.find(expected)
    if not expected or start < 0 or source.find(expected, start + 1) >= 0:
        raise ValueError("A reviewed quote/heading must occur exactly once on its labeled page.")
    return positions[start], positions[start + len(expected) - 1] + 1


def locate_heading_start(text: str, heading: str) -> int:
    """Anchor reviewed headings at their whole source line, including markup.

    The reviewer may label "APPENDIX." inside "#### **APPENDIX.**". Starting
    the oracle at the inner word wrongly assigns its Markdown prefix to the old
    chapter. Only allow heading wrappers, not arbitrary prose before/after it;
    this oracle does not call the detector being evaluated.
    """
    start, end = locate_quote(text, heading)
    line_start = text.rfind("\n", 0, start) + 1
    line_end = text.find("\n", end)
    if line_end < 0:
        line_end = len(text)
    if ("\n" in text[start:end]
            or not re.fullmatch(r" {0,3}(?:#{1,6}[ \t]+)?(?:\*\*)?", text[line_start:start])
            or not re.fullmatch(r"(?:\*\*)?(?:[ \t]+#{1,6})?[ \t\r]*", text[end:line_end])):
        raise ValueError("A reviewed chapter heading must occupy a standalone source line.")
    return line_start


@dataclass(frozen=True)
class AnchoredEvidence:
    page: int
    quote: str
    positions: frozenset[int]


def anchor_questions(book: BookLabel, pages: dict[int, str]) -> dict[str, list[AnchoredEvidence]]:
    anchored = {}
    for question in book.questions:
        labels = []
        for label in question.evidence:
            if label.page not in pages:
                raise ValueError(f"{book.id}/{question.id}: evidence page missing.")
            try:
                start, end = locate_quote(pages[label.page], label.quote)
            except ValueError as error:
                raise ValueError(f"{book.id}/{question.id}/page-{label.page}: {error}") from error
            labels.append(AnchoredEvidence(label.page, label.quote, frozenset(
                index for index in range(start, end) if not pages[label.page][index].isspace()
            )))
        anchored[question.id] = labels
    return anchored


def build_chunk_result(parsed: list[ParsedDocument], book: BookLabel, *, version: str, document_id: int):
    """Run the production cleaner/profile/chunker with pinned local config."""
    from unittest.mock import patch
    from app.ingestion.pipeline import IngestionPipeline

    if version not in {"v1", "v2"}:
        raise ValueError("version must be v1 or v2.")
    settings = Settings.model_construct(**BASELINE_CONFIG, chunking_strategy_version=version)
    document = SimpleNamespace(
        id=document_id, external_document_id=f"benchmark-{book.id}",
        source_type="LIBRARY_EBOOK", source_id=f"ebook:{document_id}",
        book_id=document_id, ebook_id=document_id, filename=book.filename,
        metadata_={"book_title": book.title},
    )
    with ExitStack() as stack:
        for module in ("app.ingestion.pipeline", "app.ingestion.cleaners.pdf_cleaner",
                       "app.ingestion.llama_index.node_adapter"):
            stack.enter_context(patch(f"{module}.get_settings", return_value=settings))
        stack.enter_context(patch("app.ingestion.pipeline.IngestionArtifactWriter", return_value=None))
        pipeline = IngestionPipeline(parser_registry=ParserRegistry([]))
        result = pipeline._build_chunk_result(parsed, document=document, source_checksum_sha256=book.sha256)
    return result


def source_ranges(chunk, pages: dict[int, str]) -> list[tuple[int, int, int]]:
    """Audit v2 spans; characterize v1 through exact page substring matching."""
    metadata = chunk.metadata
    spans = metadata.get("source_spans")
    if metadata.get("chunking_strategy_version") == "v2":
        if not isinstance(spans, list) or not spans:
            raise ValueError("v2 source spans missing.")
        result, covered = [], set()
        for span in spans:
            page, start, end = span["pageNumber"], span["sourceCharStart"], span["sourceCharEnd"]
            left, right = span["chunkCharStart"], span["chunkCharEnd"]
            if (page not in pages or not 0 <= start < end <= len(pages[page])
                    or not 0 <= left < right <= len(chunk.text)
                    or pages[page][start:end] != chunk.text[left:right]):
                raise ValueError("Chunk source mapping is invalid or text was rewritten.")
            result.append((page, start, end))
            covered.update(range(left, right))
        if any(not char.isspace() and index not in covered for index, char in enumerate(chunk.text)):
            raise ValueError("v2 source spans do not cover all chunk characters.")
        if (metadata.get("pageStart"), metadata.get("pageEnd")) != (
            min(item[0] for item in result), max(item[0] for item in result)
        ):
            raise ValueError("v2 citation page range differs from actual source spans.")
        return result
    page = metadata["pageStart"]
    # v1 splits independent pages; reject ambiguous repeated passages rather
    # than invent offsets that would inflate its coverage score.
    start = pages[page].find(chunk.text)
    if start < 0 or pages[page].find(chunk.text, start + 1) >= 0:
        raise ValueError("v1 chunk must map uniquely to its cleaned source page.")
    return [(page, start, start + len(chunk.text))]


def chapter_audit(book: BookLabel, pages: dict[int, str], chunks, ranges: dict[str, list]) -> dict:
    boundaries = [
        (label.page, locate_heading_start(pages[label.page], label.heading), label.title)
        for label in book.chapters
    ]
    if len({(page, offset) for page, offset, _ in boundaries}) != len(boundaries):
        raise ValueError("Chapter oracle contains duplicate boundaries.")
    if boundaries != sorted(boundaries, key=lambda item: item[:2]):
        raise ValueError("Chapter oracle must be ordered by page and character offset.")
    errors, mixed, audited = [], [], 0
    for chunk in chunks:
        identities = set()
        for page, start, end in ranges[chunk.metadata["vector_id"]]:
            if page < book.chapter_audit_start_page:
                continue  # Explicitly unlabelled front matter is not an oracle.
            cuts = [start, *(offset for number, offset, _ in boundaries
                             if number == page and start < offset < end), end]
            for left, right in zip(cuts, cuts[1:]):
                if not pages[page][left:right].strip():
                    continue
                expected = next((title for number, offset, title in reversed(boundaries)
                                 if (number, offset) <= (page, left)), None)
                identities.add(expected)
        detected = chunk.metadata.get("chapter_title") if chunk.metadata.get("chapter_detected") else None
        if not identities:
            continue
        audited += 1
        if len(identities) > 1:
            mixed.append(chunk.metadata["vector_id"])
        if identities != {detected}:
            errors.append({"chunkId": chunk.metadata["vector_id"], "actual": detected,
                           "expected": sorted(identities, key=lambda value: value or "")})
    return {"reviewedBoundaryCount": len(boundaries), "auditStartPage": book.chapter_audit_start_page,
            "auditedChunkCount": audited, "chapterLabelMismatchRate": len(errors) / audited if audited else 0.0,
            "mixedChapterChunkCount": len(mixed),
            "chapterLabelMismatchCount": len(errors), "mismatches": errors}


def evidence_metrics(evidence: list[AnchoredEvidence], results: list[SearchResult], ranges: dict[str, list], k: int) -> dict:
    covered: dict[int, set[int]] = defaultdict(set)
    first_rank = 0
    for rank, result in enumerate(results[:k], start=1):
        local: dict[int, set[int]] = defaultdict(set)
        for page, start, end in ranges[result.vector_id]:
            covered[page].update(range(start, end))
            local[page].update(range(start, end))
        # MRR uses binary relevance >=50% of at least one reviewed anchor.
        if not first_rank and any(len(label.positions & local[label.page]) / len(label.positions) >= .5 for label in evidence):
            first_rank = rank
    recovered = [label.positions <= covered[label.page] for label in evidence]
    fractions = [len(label.positions & covered[label.page]) / len(label.positions) for label in evidence]
    return {"evidenceRecall": sum(recovered) / len(evidence), "sourceCharacterCoverage": sum(fractions) / len(evidence),
            "hit": float(any(recovered)), "complete": float(all(recovered)),
            "reciprocalRank": 1 / first_rank if first_rank else 0.0}


def retained_context(evidence: list[AnchoredEvidence], results: list[SearchResult], ranges: dict[str, list]) -> float:
    retained = 0
    for label in evidence:
        for result in results:
            page_positions = set()
            for page, start, end in ranges[result.vector_id]:
                if page == label.page:
                    page_positions.update(range(start, end))
            if (label.positions <= page_positions
                    and canonical(label.quote) in canonical(result.context_text or result.text)):
                retained += 1
                break
    # Strict single-citation retention, NOT union-of-chunks source recall.
    return retained / len(evidence)


class LocalCandidateSource:
    def __init__(self, chunks):
        self.chunks = chunks

    async def load(self, scope: LibraryVectorSearchScope, *, limit: int, query: str):
        return [KeywordCandidate(index + 1, chunk.metadata["vector_id"], chunk.text, dict(chunk.metadata))
                for index, chunk in enumerate(self.chunks)
                if _matches(chunk.metadata, build_library_vector_filters(scope))][:limit]


class LocalNeighborSource:
    def __init__(self, chunks):
        self.chunks = chunks

    async def load(self, ranges):
        return [NeighborChunk(chunk.metadata["document_id"], chunk.metadata["chunk_index"], chunk.text,
                              chunk.metadata["vector_id"], dict(chunk.metadata))
                for chunk in self.chunks
                if any(start <= chunk.metadata["chunk_index"] <= end
                       for start, end in ranges.get(chunk.metadata["document_id"], []))]


def _matches(metadata: dict, filters: dict) -> bool:
    return all(metadata.get(key) == value for key, value in filters.items())


def cosine(left: list[float], right: list[float]) -> float:
    if len(left) != len(right) or not left or any(not math.isfinite(value) for value in (*left, *right)):
        raise ValueError("Vectors must have matching dimensions and finite values.")
    denominator = math.sqrt(sum(value * value for value in left) * sum(value * value for value in right))
    if denominator == 0:
        raise ValueError("Cannot search zero vectors.")
    return max(-1.0, min(1.0, sum(a * b for a, b in zip(left, right, strict=True)) / denominator))


class LocalVectorStore(VectorStore):
    """Exact cosine search in process; never touches a remote Qdrant collection."""
    def __init__(self, chunks, vectors):
        if len(chunks) != len(vectors):
            raise ValueError("Every chunk requires one real vector.")
        self.chunks, self.vectors = chunks, vectors

    async def search(self, query: VectorSearchQuery):
        results = []
        for chunk, vector in zip(self.chunks, self.vectors, strict=True):
            if not _matches(chunk.metadata, query.filters):
                continue
            score = cosine(query.query_vector, vector)
            if query.score_threshold is None or score >= query.score_threshold:
                results.append(SearchResult(chunk.metadata["vector_id"], score, chunk.text,
                                            dict(chunk.metadata), chunk.metadata["vector_id"]))
        return sorted(results, key=lambda result: (-result.score, result.vector_id))[:query.top_k]

    async def upsert(self, chunks):
        raise AssertionError("A comparison must not upsert runtime vectors.")

    async def delete(self, chunk_ids):
        raise AssertionError("A comparison must not delete runtime vectors.")


async def evaluate_version(book, result, *, document_id, modes, settings, vectors=None, query_provider=None,
                           ks=(1, 3, 5), context_budget=12000, reranker=None, collect_ranking_diagnostics=False,
                           per_query_policy=None):
    # This explicit hook is OFFLINE evaluation only. No runtime factory, API or
    # Settings flag imports/selects experimental policies.
    if per_query_policy is not None and (reranker is not None or set(modes) != {"hybrid"}):
        raise ValueError("Per-query experiment requires hybrid-only mode and no fixed reranker override.")
    pages = {page.metadata["page_number"]: page.text for page in result.cleaned_documents}
    anchored = anchor_questions(book, pages)
    chunks = result.chunks
    ranges = {chunk.metadata["vector_id"]: source_ranges(chunk, pages) for chunk in chunks}
    for chunk in chunks:
        chunk.metadata.update(active=True, embedding_version=settings.embedding_version)
    keyword = KeywordRetriever(candidate_source=LocalCandidateSource(chunks), settings=settings)
    policy_candidates = None
    if per_query_policy is not None:
        if len(chunks) > settings.keyword_candidate_limit:
            raise ValueError("Adaptive experiment requires the complete bounded keyword corpus.")
        policy_candidates = [KeywordCandidate(index + 1, chunk.metadata["vector_id"], chunk.text)
                             for index, chunk in enumerate(chunks)]
    expander = ContextExpander(source=LocalNeighborSource(chunks))
    compressor = ContextCompressor()
    pipeline = None
    if set(modes) & {"dense", "hybrid"}:
        if vectors is None or query_provider is None:
            raise ValueError("Dense/hybrid must use real embeddings, never a lexical stand-in.")
        dense = LibraryVectorRetrievalService(embedding_provider=query_provider,
                                             vector_store=LocalVectorStore(chunks, vectors), settings=settings)
        pipeline = RetrievalPipeline(vector_retriever=dense, keyword_retriever=keyword,
                                     context_expander=expander, context_compressor=compressor, settings=settings,
                                     reranker=reranker)
    evaluations = {}
    for mode in modes:
        cases = []
        for question in book.questions:
            decision = None
            request_pipeline = pipeline
            if per_query_policy is not None:
                # Query + corpus text only. Do not pass book/title/language,
                # expected answers, evidence labels or prior case metrics.
                decision = await per_query_policy.resolve(question.question, policy_candidates,
                                                          max_queries=settings.query_rewrite_max_queries)
                request_pipeline = RetrievalPipeline(
                    vector_retriever=dense, keyword_retriever=keyword, context_expander=expander,
                    context_compressor=compressor, settings=decision.apply(settings), reranker=decision.reranker())
            trace = RankingTrace() if collect_ranking_diagnostics and mode != "bm25" else None
            if mode == "bm25":
                seeds = await keyword.search(question.question, max(ks), scope=LibraryVectorSearchScope(
                    document_id=document_id, ebook_id=document_id, embedding_version=settings.embedding_version,
                ))
            else:
                response = await request_pipeline.search(LibraryVectorRetrievalRequest(
                    question.question, document_id=document_id, ebook_id=document_id,
                    top_k=max(ks), retrieval_mode=mode, expand_context=False,
                ), trace=trace)
                seeds = [SearchResult(hit.point_id, hit.score, hit.text, hit.metadata, hit.vector_id) for hit in response.results]
            # Context metrics use top-3 seeds with the SAME budget/window in both versions.
            context = await expander.expand(seeds[:3], window=settings.context_expansion_window)
            context = await compressor.compress(question.question, context, max_context_chars=context_budget)
            cases.append({"id": question.id, "tags": question.tags,
                          "metricsAtK": {str(k): evidence_metrics(anchored[question.id], seeds, ranges, k) for k in ks},
                          "contextRetainedAnchorRate": retained_context(anchored[question.id], context, ranges),
                          "contextCharacters": sum(len(hit.context_text or hit.text) for hit in context),
                          "seedCitations": [_build_citation(hit.metadata) for hit in seeds],
                          "contextChunkIds": [hit.vector_id for hit in context]})
            if decision is not None:
                # Report only numeric decisions/IDs; never query/corpus text.
                cases[-1]["rankingPolicyDecision"] = decision.as_report()
            if trace is not None:
                # Labels are read only after the real pipeline has completed.
                summaries = {}
                for stage, rows in trace.stages.items():
                    hits = [SearchResult(row["vectorId"], row["score"], "", vector_id=row["vectorId"])
                            for row in rows]
                    summaries[stage] = {"count": len(hits),
                                        "at3": evidence_metrics(anchored[question.id], hits, ranges, 3),
                                        "atAll": evidence_metrics(anchored[question.id], hits, ranges, len(hits))}
                cases[-1]["rankingDiagnostics"] = summaries
                if decision is not None:
                    cases[-1]["rankingPolicyTraceParameters"] = dict(trace.parameters)
        evaluations[mode] = {"caseCount": len(cases), "cases": cases,
                             "metricsAtK": {str(k): {name: sum(case["metricsAtK"][str(k)][name] for case in cases) / len(cases)
                                                      for name in cases[0]["metricsAtK"][str(k)]} for k in ks},
                             "contextRetainedAnchorRate": sum(case["contextRetainedAnchorRate"] for case in cases) / len(cases)}
    covered: dict[int, set[int]] = defaultdict(set)
    for spans in ranges.values():
        for page, start, end in spans:
            covered[page].update(index for index in range(start, end) if not pages[page][index].isspace())
    total_chars = sum(len(canonical(text)) for text in pages.values())
    return {"chunkCount": len(chunks), "qualityStatus": result.chunk_quality_report.status,
            "cleanedPageHashes": {str(page): hashlib.sha256(text.encode()).hexdigest() for page, text in pages.items()},
            "nonWhitespaceSourceCoverage": sum(len(chars) for chars in covered.values()) / total_chars,
            "multiPageChunkCount": sum(chunk.metadata["pageStart"] != chunk.metadata["pageEnd"] for chunk in chunks),
            "chapterAudit": chapter_audit(book, pages, chunks, ranges), "retrieval": evaluations}

# Flow: validate source labels -> real clean/chunk path for each version ->
# audit exact source spans -> run BM25 or real-vector retrieval -> compare
# common evidence anchors and budgeted context. Purpose: measure chunking
# independently of unstable chunk IDs, without mutating runtime state.
