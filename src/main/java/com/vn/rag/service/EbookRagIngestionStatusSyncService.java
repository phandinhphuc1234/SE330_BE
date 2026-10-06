package com.vn.rag.service;

import com.vn.ebook.entity.BookEbook;
import com.vn.ebook.enums.EbookIngestionStatus;
import com.vn.ebook.repository.BookEbookRepository;
import com.vn.rag.client.RagIngestionClient;
import com.vn.rag.client.RagIngestionClient.IngestionStatusResponse;
import com.vn.rag.config.RagIngestionSyncProperties;
import com.vn.rag.config.RagServiceProperties;
import com.vn.shared.exception.AppException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class EbookRagIngestionStatusSyncService {

    private static final int MAX_BATCH_SIZE = 100;
    private static final String LIBRARY_EBOOK_DOCUMENT_ID_PREFIX = "doc_ebook_";
    private static final EnumSet<EbookIngestionStatus> PENDING_STATUSES = EnumSet.of(
            EbookIngestionStatus.QUEUED,
            EbookIngestionStatus.PROCESSING,
            EbookIngestionStatus.PARSED,
            EbookIngestionStatus.CHUNKED,
            EbookIngestionStatus.EMBEDDING,
            EbookIngestionStatus.EMBEDDED,
            EbookIngestionStatus.INDEXING
    );

    private final BookEbookRepository bookEbookRepository;
    private final RagIngestionClient ragIngestionClient;
    private final RagServiceProperties ragServiceProperties;
    private final RagIngestionSyncProperties syncProperties;
    private final TransactionTemplate transactionTemplate;
    private final Clock applicationClock;

    public SyncSummary syncBatch(int requestedBatchSize) {
        if (!ragServiceProperties.enabled()) {
            return SyncSummary.empty();
        }

        int batchSize = Math.min(Math.max(requestedBatchSize, 1), MAX_BATCH_SIZE);
        Instant batchStartedAt = applicationClock.instant();
        List<Long> candidateIds = bookEbookRepository.findIngestionSyncCandidateIds(
                PENDING_STATUSES,
                batchStartedAt,
                PageRequest.of(0, batchSize)
        );

        int updated = 0;
        int skipped = 0;
        int pollFailures = 0;
        int permanentlyFailed = 0;
        for (Long ebookId : candidateIds) {
            IngestionCandidate candidate = transactionTemplate.execute(status -> loadCandidate(ebookId));
            if (candidate == null) {
                skipped++;
                continue;
            }

            IngestionStatusResponse response;
            try {
                response = ragIngestionClient.getIngestionStatus(candidate.ragJobId());
            } catch (AppException exception) {
                pollFailures++;
                PollFailureResult result = transactionTemplate.execute(status ->
                        recordPollFailure(candidate, exception.getCode())
                );
                if (result != null && result.terminal()) {
                    permanentlyFailed++;
                }
                logPollFailure(candidate, result, exception.getCode());
                continue;
            } catch (RuntimeException exception) {
                pollFailures++;
                PollFailureResult result = transactionTemplate.execute(status ->
                        recordPollFailure(candidate, exception.getClass().getSimpleName())
                );
                if (result != null && result.terminal()) {
                    permanentlyFailed++;
                }
                logPollFailure(candidate, result, exception.getClass().getSimpleName());
                continue;
            }

            Boolean changed = transactionTemplate.execute(status -> applyStatus(candidate, response));
            if (Boolean.TRUE.equals(changed)) {
                updated++;
            } else {
                skipped++;
            }
        }

        return new SyncSummary(candidateIds.size(), updated, skipped, pollFailures, permanentlyFailed);
    }

    private IngestionCandidate loadCandidate(Long ebookId) {
        return bookEbookRepository.findById(ebookId)
                .filter(ebook -> ebook.getRagJobId() != null)
                .filter(ebook -> PENDING_STATUSES.contains(ebook.getIngestionStatus()))
                .map(ebook -> new IngestionCandidate(
                        ebook.getId(),
                        ebook.getRagJobId(),
                        LIBRARY_EBOOK_DOCUMENT_ID_PREFIX + ebook.getId()
                ))
                .orElse(null);
    }

    private boolean applyStatus(IngestionCandidate candidate, IngestionStatusResponse response) {
        if (response == null || response.ingestionJobId() == null
                || !candidate.ragJobId().equals(response.ingestionJobId())) {
            log.warn(
                    "Ignoring mismatched RAG status response for ebookId={} expectedJobId={} actualJobId={}",
                    candidate.ebookId(),
                    candidate.ragJobId(),
                    response != null ? response.ingestionJobId() : null
            );
            return false;
        }

        if (!candidate.expectedDocumentId().equals(response.documentId())) {
            log.warn(
                    "Rejecting RAG status identity mismatch for ebookId={} ragJobId={} expectedDocumentId={} actualDocumentId={}",
                    candidate.ebookId(), candidate.ragJobId(), candidate.expectedDocumentId(), response.documentId()
            );
            return markIdentityMismatch(candidate, response.documentId());
        }

        Optional<EbookIngestionStatus> parsedStatus = parseStatus(response.status());
        if (parsedStatus.isEmpty() || parsedStatus.get() == EbookIngestionStatus.NOT_REQUESTED) {
            log.warn(
                    "Ignoring unknown RAG ingestion status for ebookId={} ragJobId={} status={}",
                    candidate.ebookId(), candidate.ragJobId(), response.status()
            );
            return false;
        }

        BookEbook ebook = bookEbookRepository.findById(candidate.ebookId()).orElse(null);
        if (ebook == null || !candidate.ragJobId().equals(ebook.getRagJobId())) {
            return false;
        }
        if (!PENDING_STATUSES.contains(ebook.getIngestionStatus())) {
            return false;
        }

        EbookIngestionStatus nextStatus = parsedStatus.get();
        if (nextStatus != EbookIngestionStatus.INDEX_FAILED
                && nextStatus.ordinal() < ebook.getIngestionStatus().ordinal()) {
            log.debug(
                    "Ignoring stale RAG status for ebookId={} ragJobId={} current={} received={}",
                    ebook.getId(), candidate.ragJobId(), ebook.getIngestionStatus(), nextStatus
            );
            return false;
        }

        Instant now = applicationClock.instant();
        ebook.setIngestionStatus(nextStatus);
        ebook.setIngestionStage(truncate(response.stage(), 100));
        ebook.setIngestionLastCheckedAt(now);
        ebook.setIngestionPollFailureCount(0);
        ebook.setIngestionNextCheckAt(null);
        if (StringUtils.hasText(response.documentId())) {
            ebook.setRagDocumentId(response.documentId().strip());
        }

        if (nextStatus == EbookIngestionStatus.INDEX_FAILED) {
            ebook.setIngestionLastError(buildFailureMessage(response));
            ebook.setIndexingCompletedAt(null);
        } else {
            ebook.setIngestionLastError(null);
            if (nextStatus == EbookIngestionStatus.INDEXED) {
                ebook.setIndexingCompletedAt(now);
            }
        }

        bookEbookRepository.save(ebook);
        return true;
    }

    private boolean markIdentityMismatch(IngestionCandidate candidate, String actualDocumentId) {
        BookEbook ebook = bookEbookRepository.findById(candidate.ebookId()).orElse(null);
        if (ebook == null
                || !candidate.ragJobId().equals(ebook.getRagJobId())
                || !PENDING_STATUSES.contains(ebook.getIngestionStatus())) {
            return false;
        }

        ebook.setIngestionStatus(EbookIngestionStatus.INDEX_FAILED);
        ebook.setIngestionStage("status_identity_mismatch");
        ebook.setIngestionLastCheckedAt(applicationClock.instant());
        ebook.setIngestionPollFailureCount(0);
        ebook.setIngestionNextCheckAt(null);
        ebook.setIndexingCompletedAt(null);
        ebook.setIngestionLastError(truncate(
                "RAG_DOCUMENT_MISMATCH: expected=" + candidate.expectedDocumentId()
                        + ", actual=" + (StringUtils.hasText(actualDocumentId) ? actualDocumentId.strip() : "null"),
                1000
        ));
        bookEbookRepository.save(ebook);
        return true;
    }

    private Optional<EbookIngestionStatus> parseStatus(String value) {
        if (!StringUtils.hasText(value)) {
            return Optional.empty();
        }
        try {
            String normalized = value.strip().toUpperCase(Locale.ROOT);
            if ("FAILED".equals(normalized)) {
                return Optional.of(EbookIngestionStatus.INDEX_FAILED);
            }
            return Optional.of(EbookIngestionStatus.valueOf(normalized));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private PollFailureResult recordPollFailure(IngestionCandidate candidate, String failureCode) {
        BookEbook ebook = bookEbookRepository.findById(candidate.ebookId()).orElse(null);
        if (ebook == null
                || !candidate.ragJobId().equals(ebook.getRagJobId())
                || !PENDING_STATUSES.contains(ebook.getIngestionStatus())) {
            return null;
        }

        Instant now = applicationClock.instant();
        int failedAttempts = Math.max(ebook.getIngestionPollFailureCount() == null
                ? 0
                : ebook.getIngestionPollFailureCount(), 0) + 1;
        ebook.setIngestionPollFailureCount(failedAttempts);
        ebook.setIngestionLastCheckedAt(now);
        ebook.setIngestionLastError(truncate("STATUS_POLL_FAILED: " + safeFailureCode(failureCode), 1000));

        if (failedAttempts >= syncProperties.maxPollFailures()) {
            ebook.setIngestionStatus(EbookIngestionStatus.INDEX_FAILED);
            ebook.setIngestionStage("status_poll_failed");
            ebook.setIngestionNextCheckAt(null);
            ebook.setIndexingCompletedAt(null);
            bookEbookRepository.save(ebook);
            return new PollFailureResult(failedAttempts, null, true);
        }

        Instant nextCheckAt = now.plus(resolveRetryDelay(failedAttempts));
        ebook.setIngestionNextCheckAt(nextCheckAt);
        bookEbookRepository.save(ebook);
        return new PollFailureResult(failedAttempts, nextCheckAt, false);
    }

    private Duration resolveRetryDelay(int failedAttempts) {
        int exponent = (int) Math.clamp((long) failedAttempts - 1L, 0L, 30L);
        long multiplier = 1L << exponent;
        Duration calculated;
        try {
            calculated = syncProperties.initialRetryDelay().multipliedBy(multiplier);
        } catch (ArithmeticException exception) {
            calculated = syncProperties.maxRetryDelay();
        }
        return calculated.compareTo(syncProperties.maxRetryDelay()) > 0
                ? syncProperties.maxRetryDelay()
                : calculated;
    }

    private void logPollFailure(
            IngestionCandidate candidate,
            PollFailureResult result,
            String failureCode
    ) {
        if (result == null) {
            log.debug(
                    "Skipped stale RAG poll failure for ebookId={} ragJobId={}",
                    candidate.ebookId(), candidate.ragJobId()
            );
            return;
        }
        if (result.terminal()) {
            log.warn(
                    "RAG status polling exhausted for ebookId={} ragJobId={} attempts={} error={}",
                    candidate.ebookId(), candidate.ragJobId(), result.failedAttempts(), safeFailureCode(failureCode)
            );
            return;
        }
        log.warn(
                "RAG status poll failed for ebookId={} ragJobId={} attempt={}/{} nextCheckAt={} error={}",
                candidate.ebookId(), candidate.ragJobId(), result.failedAttempts(),
                syncProperties.maxPollFailures(), result.nextCheckAt(), safeFailureCode(failureCode)
        );
    }

    private String safeFailureCode(String failureCode) {
        return StringUtils.hasText(failureCode) ? failureCode.strip() : "UNKNOWN_RAG_STATUS_POLL_ERROR";
    }

    private String buildFailureMessage(IngestionStatusResponse response) {
        String errorCode = StringUtils.hasText(response.errorCode()) ? response.errorCode().strip() : null;
        String errorMessage = StringUtils.hasText(response.errorMessage()) ? response.errorMessage().strip() : null;
        if (errorCode == null) {
            return truncate(errorMessage, 1000);
        }
        if (errorMessage == null) {
            return truncate(errorCode, 1000);
        }
        return truncate(errorCode + ": " + errorMessage, 1000);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private record IngestionCandidate(Long ebookId, Long ragJobId, String expectedDocumentId) {
    }

    private record PollFailureResult(int failedAttempts, Instant nextCheckAt, boolean terminal) {
    }

    public record SyncSummary(int polled, int updated, int skipped, int pollFailures, int permanentlyFailed) {
        public static SyncSummary empty() {
            return new SyncSummary(0, 0, 0, 0, 0);
        }
    }
}
