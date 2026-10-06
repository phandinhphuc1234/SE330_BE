package com.vn.ebook.service.impl;

import com.vn.ebook.dto.request.EbookAskRequest;
import com.vn.ebook.dto.response.EbookAnswerCitationResponse;
import com.vn.ebook.dto.response.EbookAnswerResponse;
import com.vn.ebook.enums.EbookAiOperation;
import com.vn.ebook.enums.EbookIngestionStatus;
import com.vn.ebook.service.EbookAiMetrics;
import com.vn.ebook.service.EbookAiRateLimitService;
import com.vn.ebook.service.EbookQuestionAnswerService;
import com.vn.ebook.service.EbookReaderSessionService;
import com.vn.ebook.service.EbookReadingAccess;
import com.vn.rag.client.RagAnswerClient;
import com.vn.rag.client.RagAnswerClient.AnswerCitation;
import com.vn.rag.client.RagAnswerClient.AnswerResponse;
import com.vn.shared.exception.AppException;
import com.vn.shared.exception.ErrorCode;
import com.vn.shared.logging.LogEvent;
import com.vn.shared.logging.LogResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
public class EbookQuestionAnswerServiceImpl implements EbookQuestionAnswerService {

    private final EbookReaderSessionService ebookReaderSessionService;
    private final RagAnswerClient ragAnswerClient;
    private final EbookAiRateLimitService rateLimitService;
    private final EbookAiMetrics metrics;

    @Override
    public EbookAnswerResponse answer(Long memberId,
                                      Long bookId,
                                      String rawReadingSessionToken,
                                      EbookAskRequest request) {
        EbookReadingAccess access = ebookReaderSessionService.authorizeAccess(
                memberId,
                bookId,
                rawReadingSessionToken
        );
        if (access.ingestionStatus() != EbookIngestionStatus.INDEXED) {
            throw new AppException(ErrorCode.EBOOK_AI_NOT_READY);
        }

        rateLimitService.check(memberId, bookId, EbookAiOperation.ASK);
        long startedAt = System.nanoTime();
        try {
            AnswerResponse ragResponse = ragAnswerClient.answer(RagAnswerClient.AnswerRequest.forEbook(
                    request.question().strip(),
                    access.ebookId(),
                    request.topK(),
                    request.scoreThreshold()
            ));
            EbookAnswerResponse response = mapVerifiedResponse(ragResponse, access);
            String outcome = response.abstained() ? "abstained" : "grounded";
            Duration duration = Duration.ofNanos(System.nanoTime() - startedAt);
            metrics.record(EbookAiOperation.ASK, outcome, duration);
            log.info("eventType={} result={} memberId={} bookId={} ebookId={} outcome={} citationCount={} durationMs={}",
                    LogEvent.ASK_EBOOK, LogResult.SUCCESS, memberId, bookId, access.ebookId(), outcome,
                    response.citations().size(), duration.toMillis());
            return response;
        } catch (RuntimeException exception) {
            Duration duration = Duration.ofNanos(System.nanoTime() - startedAt);
            metrics.record(EbookAiOperation.ASK, "error", duration);
            log.warn("eventType={} result={} memberId={} bookId={} ebookId={} reason={} durationMs={}",
                    LogEvent.ASK_EBOOK, LogResult.FAILED, memberId, bookId, access.ebookId(),
                    errorReason(exception), duration.toMillis());
            throw exception;
        }
    }

    private EbookAnswerResponse mapVerifiedResponse(AnswerResponse response, EbookReadingAccess access) {
        if (response == null || response.citations() == null) {
            throw new AppException(ErrorCode.RAG_SERVICE_ERROR);
        }
        List<EbookAnswerCitationResponse> citations = response.citations().stream()
                .map(citation -> mapVerifiedCitation(citation, access))
                .toList();

        boolean validAbstention = response.abstained()
                && !response.grounded()
                && StringUtils.hasText(response.answer())
                && StringUtils.hasText(response.promptVersion())
                && citations.isEmpty();
        boolean validGroundedAnswer = !response.abstained()
                && response.grounded()
                && StringUtils.hasText(response.answer())
                && StringUtils.hasText(response.promptVersion())
                && !citations.isEmpty();
        if (!validAbstention && !validGroundedAnswer) {
            log.warn("Rejected inconsistent RAG answer for bookId={} ebookId={}",
                    access.bookId(), access.ebookId());
            throw new AppException(ErrorCode.RAG_SERVICE_ERROR);
        }

        return new EbookAnswerResponse(
                access.bookId(),
                access.ebookId(),
                response.answer(),
                response.grounded(),
                response.abstained(),
                response.reason(),
                citations,
                response.promptVersion()
        );
    }

    private EbookAnswerCitationResponse mapVerifiedCitation(AnswerCitation citation, EbookReadingAccess access) {
        if (citation == null
                || !Objects.equals(citation.ebookId(), access.ebookId())
                || (citation.bookId() != null && !Objects.equals(citation.bookId(), access.bookId()))
                || !StringUtils.hasText(citation.chunkId())
                || citation.pageStart() == null
                || citation.pageStart() <= 0) {
            log.warn("Rejected out-of-scope RAG answer citation for bookId={} ebookId={}",
                    access.bookId(), access.ebookId());
            throw new AppException(ErrorCode.RAG_SERVICE_ERROR);
        }
        return new EbookAnswerCitationResponse(
                citation.documentId(),
                access.bookId(),
                access.ebookId(),
                citation.chapterTitle(),
                citation.chapterIndex(),
                citation.pageStart(),
                citation.pageEnd(),
                citation.chunkIndex(),
                citation.chunkId(),
                citation.excerpt(),
                citation.score() != null ? citation.score() : 0.0
        );
    }

    private String errorReason(RuntimeException exception) {
        return exception instanceof AppException appException
                ? appException.getCode()
                : exception.getClass().getSimpleName();
    }
}
