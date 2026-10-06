package com.vn.ebook.service.impl;

import com.vn.ebook.dto.request.EbookSemanticSearchRequest;
import com.vn.ebook.dto.response.EbookSemanticSearchCitationResponse;
import com.vn.ebook.dto.response.EbookSemanticSearchHitResponse;
import com.vn.ebook.dto.response.EbookSemanticSearchResponse;
import com.vn.ebook.enums.EbookAiOperation;
import com.vn.ebook.enums.EbookIngestionStatus;
import com.vn.ebook.service.EbookAiMetrics;
import com.vn.ebook.service.EbookAiRateLimitService;
import com.vn.ebook.service.EbookReaderSessionService;
import com.vn.ebook.service.EbookReadingAccess;
import com.vn.ebook.service.EbookSemanticSearchService;
import com.vn.rag.client.RagRetrievalClient;
import com.vn.rag.client.RagRetrievalClient.RetrievalCitation;
import com.vn.rag.client.RagRetrievalClient.RetrievalHit;
import com.vn.rag.client.RagRetrievalClient.RetrievalResponse;
import com.vn.shared.exception.AppException;
import com.vn.shared.exception.ErrorCode;
import com.vn.shared.logging.LogEvent;
import com.vn.shared.logging.LogResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.time.Duration;

@Service
@RequiredArgsConstructor
@Slf4j
public class EbookSemanticSearchServiceImpl implements EbookSemanticSearchService {

    private final EbookReaderSessionService ebookReaderSessionService;
    private final RagRetrievalClient ragRetrievalClient;
    private final EbookAiRateLimitService rateLimitService;
    private final EbookAiMetrics metrics;

    @Override
    public EbookSemanticSearchResponse search(Long memberId,
                                              Long bookId,
                                              String rawReadingSessionToken,
                                              EbookSemanticSearchRequest request) {
        EbookReadingAccess access = ebookReaderSessionService.authorizeAccess(
                memberId,
                bookId,
                rawReadingSessionToken
        );
        if (access.ingestionStatus() != EbookIngestionStatus.INDEXED) {
            throw new AppException(ErrorCode.EBOOK_AI_NOT_READY);
        }

        rateLimitService.check(memberId, bookId, EbookAiOperation.SEARCH);
        long startedAt = System.nanoTime();
        try {
            RetrievalResponse retrieval = ragRetrievalClient.search(RagRetrievalClient.RetrievalRequest.forEbook(
                    request.query().strip(),
                    access.ebookId(),
                    request.topK(),
                    request.scoreThreshold()
            ));
            List<EbookSemanticSearchHitResponse> results = mapVerifiedResults(retrieval, access);
            Duration duration = Duration.ofNanos(System.nanoTime() - startedAt);
            metrics.record(EbookAiOperation.SEARCH, "success", duration);
            log.info("eventType={} result={} memberId={} bookId={} ebookId={} resultCount={} durationMs={}",
                    LogEvent.SEARCH_EBOOK, LogResult.SUCCESS, memberId, bookId, access.ebookId(),
                    results.size(), duration.toMillis());
            return new EbookSemanticSearchResponse(access.bookId(), access.ebookId(), results.size(), results);
        } catch (RuntimeException exception) {
            Duration duration = Duration.ofNanos(System.nanoTime() - startedAt);
            metrics.record(EbookAiOperation.SEARCH, "error", duration);
            log.warn("eventType={} result={} memberId={} bookId={} ebookId={} reason={} durationMs={}",
                    LogEvent.SEARCH_EBOOK, LogResult.FAILED, memberId, bookId, access.ebookId(),
                    errorReason(exception), duration.toMillis());
            throw exception;
        }
    }

    private List<EbookSemanticSearchHitResponse> mapVerifiedResults(RetrievalResponse retrieval,
                                                                    EbookReadingAccess access) {
        if (retrieval == null || retrieval.results() == null) {
            throw new AppException(ErrorCode.RAG_SERVICE_ERROR);
        }
        return retrieval.results().stream()
                .map(hit -> mapVerifiedHit(hit, access))
                .toList();
    }

    private EbookSemanticSearchHitResponse mapVerifiedHit(RetrievalHit hit, EbookReadingAccess access) {
        RetrievalCitation citation = hit != null ? hit.citation() : null;
        if (hit == null
                || citation == null
                || !Objects.equals(citation.ebookId(), access.ebookId())
                || (citation.bookId() != null && !Objects.equals(citation.bookId(), access.bookId()))) {
            log.warn("Rejected out-of-scope RAG retrieval result for bookId={} ebookId={}",
                    access.bookId(), access.ebookId());
            throw new AppException(ErrorCode.RAG_SERVICE_ERROR);
        }

        String chunkId = hit.vectorId() != null ? hit.vectorId() : hit.pointId();
        return new EbookSemanticSearchHitResponse(
                chunkId,
                hit.score() != null ? hit.score() : 0.0,
                hit.text(),
                new EbookSemanticSearchCitationResponse(
                        citation.documentId(),
                        access.bookId(),
                        access.ebookId(),
                        citation.chapterTitle(),
                        citation.chapterIndex(),
                        citation.pageStart(),
                        citation.pageEnd(),
                        citation.chunkIndex()
                )
        );
    }

    private String errorReason(RuntimeException exception) {
        return exception instanceof AppException appException
                ? appException.getCode()
                : exception.getClass().getSimpleName();
    }
}
