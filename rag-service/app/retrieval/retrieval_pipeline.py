"""Production retrieval orchestration for the Library RAG service."""

from __future__ import annotations

import asyncio
from dataclasses import replace

from app.core.config import Settings, get_settings
from app.core.logger import get_logger
from app.indexing import LibraryVectorSearchScope, SearchResult
from app.retrieval.context_expander import ContextCompressor, ContextExpander, PostgresNeighborChunkSource
from app.retrieval.hybrid_retriever import fuse_search_results
from app.retrieval.graph_retriever import GraphRetriever
from app.retrieval.keyword_retriever import KeywordRetriever
from app.retrieval.library_vector_retrieval import (
    MAX_LIBRARY_RETRIEVAL_TOP_K,
    LibraryVectorRetrievalHit,
    LibraryVectorRetrievalRequest,
    LibraryVectorRetrievalResponse,
    LibraryVectorRetrievalService,
    _build_citation,
)
from app.retrieval.query_rewriter import QueryRewriter
from app.retrieval.reranker import Reranker
from app.retrieval.ranking_trace import RankingTrace


logger = get_logger(__name__)


class RetrievalPipeline:
    """Dense baseline plus BM25, weighted RRF, reranking and context control."""

    def __init__(
        self,
        *,
        vector_retriever: LibraryVectorRetrievalService | None = None,
        keyword_retriever: KeywordRetriever | None = None,
        query_rewriter: QueryRewriter | None = None,
        reranker: Reranker | None = None,
        context_expander: ContextExpander | None = None,
        context_compressor: ContextCompressor | None = None,
        graph_retriever: GraphRetriever | None = None,
        settings: Settings | None = None,
    ) -> None:
        self.settings = settings or get_settings()
        self.vector_retriever = vector_retriever or LibraryVectorRetrievalService(settings=self.settings)
        self.keyword_retriever = keyword_retriever or KeywordRetriever(settings=self.settings)
        self.query_rewriter = query_rewriter or QueryRewriter(
            max_queries=self.settings.query_rewrite_max_queries
        )
        self.reranker = reranker or Reranker()
        self.context_expander = context_expander or ContextExpander(
            source=PostgresNeighborChunkSource(settings=self.settings)
        )
        self.context_compressor = context_compressor or ContextCompressor()
        self.graph_retriever = graph_retriever or GraphRetriever(settings=self.settings)

    async def retrieve(self, query: str, **scope) -> LibraryVectorRetrievalResponse:
        """Compatibility wrapper for the former placeholder API."""

        return await self.search(LibraryVectorRetrievalRequest(query=query, **scope))

    async def search(
        self,
        request: LibraryVectorRetrievalRequest,
        *,
        trace: RankingTrace | None = None,
    ) -> LibraryVectorRetrievalResponse:
        mode = (request.retrieval_mode or self.settings.retrieval_mode).strip().lower()
        if mode not in {"dense", "hybrid", "graph"}:
            raise ValueError("retrieval_mode must be 'dense', 'hybrid', or 'graph'.")

        top_k = _resolve_top_k(request.top_k, self.settings.retrieval_top_k)
        if trace is not None:
            trace.begin(mode=mode, topK=top_k, scoreThreshold=request.score_threshold)
            if isinstance(self.reranker, Reranker):
                trace.parameters["rerankerWeights"] = self.reranker.weights
        if mode == "dense":
            dense_response = await self.vector_retriever.search(replace(request, top_k=top_k))
            if trace is not None:
                trace.record("dense_candidates", [_hit_to_search_result(hit) for hit in dense_response.results])
                trace.record("final_top_k", [_hit_to_search_result(hit) for hit in dense_response.results])
                trace.outcome = "dense"
            if not request.expand_context:
                return dense_response
            processed = await self._prepare_answer_context(
                request.query,
                [_hit_to_search_result(hit) for hit in dense_response.results],
                request.max_context_chars,
            )
            return replace(
                dense_response,
                results=[_search_result_to_hit(result) for result in processed],
            )

        candidate_k = min(
            MAX_LIBRARY_RETRIEVAL_TOP_K,
            max(top_k, top_k * self.settings.hybrid_candidate_multiplier),
        )
        rewrites = await self.query_rewriter.rewrite(request.query)
        if trace is not None:
            trace.parameters.update(candidateK=candidate_k, rewriteCount=len(rewrites),
                                    vectorWeight=self.settings.hybrid_vector_weight,
                                    keywordWeight=self.settings.hybrid_keyword_weight,
                                    rrfK=self.settings.hybrid_rrf_k)
        dense_response = await self.vector_retriever.search(
            replace(request, top_k=candidate_k, score_threshold=None, expand_context=False)
        )
        scope = LibraryVectorSearchScope(
            book_id=request.book_id,
            ebook_id=request.ebook_id,
            document_id=request.document_id,
            embedding_version=self.settings.embedding_version,
        )
        vector_results = [_hit_to_search_result(hit) for hit in dense_response.results]
        if trace is not None:
            trace.record("dense_candidates", vector_results)
        graph_results = []
        if mode == "graph":
            try:
                graph_results = await self.graph_retriever.search(request.query, candidate_k, scope=scope, seeds=vector_results)
            except Exception as error:
                if not self.settings.hybrid_fail_open:
                    raise
                logger.warning("graph_retrieval_failed_hybrid_fallback", error_type=type(error).__name__)
            if trace is not None:
                trace.record("graph_candidates", graph_results)

        try:
            keyword_sets = await asyncio.gather(
                *[
                    self.keyword_retriever.search(rewrite, candidate_k, scope=scope)
                    for rewrite in rewrites
                ]
            )
        except Exception as error:  # noqa: BLE001 - database driver failures vary.
            if not self.settings.hybrid_fail_open:
                raise
            logger.warning(
                "keyword_retrieval_failed_dense_fallback",
                error_type=type(error).__name__,
            )
            keyword_sets = []

        if trace is not None:
            for index, results in enumerate(keyword_sets):
                trace.record(f"bm25_{index}", results)

        # A lexical database outage returns the actual dense baseline; do not
        # apply hybrid rescaling/reranking to a single surviving branch.
        if not keyword_sets:
            dense_hits = [
                hit for hit in dense_response.results
                if request.score_threshold is None or hit.score >= request.score_threshold
            ][:top_k]
            response = replace(dense_response, top_k=top_k, results=dense_hits)
            if trace is not None:
                trace.outcome = "dense_fallback"
                trace.record("final_top_k", [_hit_to_search_result(hit) for hit in dense_hits])
            if not request.expand_context:
                return response
            processed = await self._prepare_answer_context(
                request.query, [_hit_to_search_result(hit) for hit in dense_hits], request.max_context_chars,
            )
            return replace(response, results=[_search_result_to_hit(result) for result in processed])

        fused = fuse_search_results(
            vector_results=vector_results,
            keyword_result_sets=keyword_sets,
            vector_weight=self.settings.hybrid_vector_weight,
            keyword_weight=self.settings.hybrid_keyword_weight,
            graph_results=graph_results,
            graph_weight=self.settings.graph_rrf_weight,
            rrf_k=self.settings.hybrid_rrf_k,
            limit=candidate_k,
            trace=trace,
        )
        if trace is not None:
            trace.record("rrf_candidates", fused)
        reranked = await self.reranker.rerank(request.query, fused, candidate_k)
        if trace is not None:
            trace.record("reranked_candidates", reranked)
        if request.score_threshold is not None:
            reranked = [result for result in reranked if result.score >= request.score_threshold]
        if trace is not None:
            trace.record("threshold_passed", reranked)
        reranked = reranked[:top_k]
        if trace is not None:
            trace.record("final_top_k", reranked)
            trace.outcome = mode
        if request.expand_context:
            reranked = await self._prepare_answer_context(
                request.query,
                reranked,
                request.max_context_chars,
            )

        logger.info(
            "library_hybrid_retrieval_completed",
            top_k=top_k,
            dense_count=len(dense_response.results),
            keyword_count=sum(len(results) for results in keyword_sets),
            result_count=len(reranked),
            rewrite_count=len(rewrites),
        )
        return LibraryVectorRetrievalResponse(
            query_text_hash=dense_response.query_text_hash,
            query_text_policy=dense_response.query_text_policy,
            embedding_version=dense_response.embedding_version,
            top_k=top_k,
            applied_filters=dense_response.applied_filters,
            results=[_search_result_to_hit(result, rewrites=rewrites) for result in reranked],
        )

    async def _prepare_answer_context(
        self,
        query: str,
        results: list[SearchResult],
        max_context_chars: int | None,
    ) -> list[SearchResult]:
        try:
            expanded = await self.context_expander.expand(
                results,
                window=self.settings.context_expansion_window,
            )
        except Exception as error:
            if not self.settings.hybrid_fail_open:
                raise
            # Expansion is optional. A lexical DB outage must not turn an
            # otherwise successful dense fallback into a failed answer call.
            logger.warning("context_expansion_failed_seed_fallback", error_type=type(error).__name__)
            expanded = results
        return await self.context_compressor.compress(
            query,
            expanded,
            max_context_chars=max_context_chars or self.settings.answer_max_context_chars,
        )


def _hit_to_search_result(hit: LibraryVectorRetrievalHit) -> SearchResult:
    return SearchResult(
        id=hit.point_id,
        vector_id=hit.vector_id,
        score=hit.score,
        text=hit.text,
        metadata=dict(hit.metadata or {}),
        retrieval_source=str((hit.metadata or {}).get("retrieval_source") or "vector"),
        context_text=hit.context_text,
    )


def _search_result_to_hit(
    result: SearchResult,
    *,
    rewrites: list[str] | None = None,
) -> LibraryVectorRetrievalHit:
    metadata = dict(result.metadata or {})
    metadata["retrieval_source"] = result.retrieval_source
    if rewrites:
        metadata["query_rewrites"] = list(rewrites)
    return LibraryVectorRetrievalHit(
        point_id=result.id,
        vector_id=result.vector_id,
        score=result.score,
        text=result.text,
        citation=_build_citation(metadata),
        metadata=metadata,
        context_text=result.context_text,
    )


def _resolve_top_k(requested: int | None, default: int) -> int:
    top_k = default if requested is None else requested
    if isinstance(top_k, bool) or int(top_k) <= 0:
        raise ValueError("top_k must be a positive integer.")
    if int(top_k) > MAX_LIBRARY_RETRIEVAL_TOP_K:
        raise ValueError(f"top_k must be <= {MAX_LIBRARY_RETRIEVAL_TOP_K}.")
    return int(top_k)
