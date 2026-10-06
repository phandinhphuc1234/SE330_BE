package com.vn.rag.service;

import com.vn.rag.config.RagServiceProperties;
import com.vn.ebook.entity.BookEbook;
import com.vn.ebook.enums.EbookIngestionStatus;
import com.vn.shared.exception.AppException;
import com.vn.shared.exception.ErrorCode;
import com.vn.ebook.repository.BookEbookRepository;
import com.vn.rag.client.RagIngestionClient;
import com.vn.rag.client.RagIngestionClient.IngestionRequest;
import com.vn.rag.client.RagIngestionClient.IngestionResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Slf4j
public class EbookRagIngestionAsyncProcessor {

    private final BookEbookRepository bookEbookRepository;
    private final RagIngestionClient ragIngestionClient;
    private final RagServiceProperties ragServiceProperties;
    private final TransactionTemplate transactionTemplate;

    @Async("ragIngestionExecutor")
    public void requestIngestionAsync(Long ebookId) {
        requestIngestion(ebookId, false);
    }

    @Async("ragIngestionExecutor")
    public void requestReindexAsync(Long ebookId) {
        requestIngestion(ebookId, true);
    }

    private void requestIngestion(Long ebookId, boolean forceReindex) {
        if (!ragServiceProperties.enabled()) {
            log.debug("Skipping RAG ingestion for ebookId={} because RAG is disabled", ebookId);
            return;
        }

        try {
            IngestionRequest request = transactionTemplate.execute(status -> buildRequest(ebookId, forceReindex));
            if (request == null) {
                return;
            }
            IngestionResponse response = ragIngestionClient.ingestLibraryEbook(request);
            transactionTemplate.execute(status -> {
                markIngestionQueued(ebookId, response);
                return null;
            });
        } catch (AppException exception) {
            log.warn("Could not trigger RAG ingestion for ebookId={}: {}", ebookId, exception.getCode());
            recordFailure(ebookId, exception.getCode());
        } catch (RuntimeException exception) {
            log.warn("Could not trigger RAG ingestion for ebookId={}", ebookId, exception);
            recordFailure(ebookId, ErrorCode.INTERNAL_SERVER_ERROR.getCode());
        }
    }

    private IngestionRequest buildRequest(Long ebookId, boolean forceReindex) {
        BookEbook ebook = bookEbookRepository.findById(ebookId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));
        if (forceReindex) {
            return IngestionRequest.libraryEbookReindex(
                    ebook.getBook().getId(), ebook.getId(), ebook.getBucketName(), ebook.getObjectKey(),
                    ebook.getOriginalFilename(), ebook.getMimeType(), ebook.getSizeBytes(), ebook.getChecksumSha256()
            );
        }
        return IngestionRequest.libraryEbook(
                ebook.getBook().getId(), ebook.getId(), ebook.getBucketName(), ebook.getObjectKey(),
                ebook.getOriginalFilename(), ebook.getMimeType(), ebook.getSizeBytes(), ebook.getChecksumSha256()
        );
    }

    private void markIngestionQueued(Long ebookId, IngestionResponse response) {
        BookEbook ebook = bookEbookRepository.findById(ebookId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));
        Instant now = Instant.now();
        EbookIngestionStatus ingestionStatus = normalizeIngestionStatus(response != null ? response.status() : null);
        ebook.setRagDocumentId(response != null ? response.documentId() : null);
        ebook.setRagJobId(response != null ? response.ingestionJobId() : null);
        ebook.setIngestionStatus(ingestionStatus);
        ebook.setIngestionStage(ingestionStatus == EbookIngestionStatus.INDEXED ? "indexed" : "queued");
        ebook.setIngestionLastError(null);
        ebook.setIndexingRequestedAt(now);
        ebook.setIngestionLastCheckedAt(now);
        ebook.setIngestionPollFailureCount(0);
        ebook.setIngestionNextCheckAt(null);
        ebook.setIndexingCompletedAt(ingestionStatus == EbookIngestionStatus.INDEXED ? now : null);
        bookEbookRepository.save(ebook);
    }

    private void markIngestionFailed(Long ebookId, String errorCode) {
        BookEbook ebook = bookEbookRepository.findById(ebookId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));
        ebook.setIngestionStatus(EbookIngestionStatus.INDEX_FAILED);
        ebook.setIngestionStage("request_failed");
        ebook.setIngestionLastError(truncate(errorCode, 1000));
        ebook.setIndexingRequestedAt(Instant.now());
        ebook.setIngestionLastCheckedAt(Instant.now());
        ebook.setIngestionPollFailureCount(0);
        ebook.setIngestionNextCheckAt(null);
        ebook.setIndexingCompletedAt(null);
        bookEbookRepository.save(ebook);
    }

    private void recordFailure(Long ebookId, String errorCode) {
        try {
            transactionTemplate.execute(status -> {
                markIngestionFailed(ebookId, errorCode);
                return null;
            });
        } catch (AppException exception) {
            log.warn("Could not mark RAG ingestion failed for ebookId={}: {}", ebookId, exception.getCode());
        }
    }

    private EbookIngestionStatus normalizeIngestionStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return EbookIngestionStatus.QUEUED;
        }
        try {
            String normalized = status.trim().toUpperCase(Locale.ROOT);
            if ("FAILED".equals(normalized)) {
                return EbookIngestionStatus.INDEX_FAILED;
            }
            return EbookIngestionStatus.valueOf(normalized);
        } catch (IllegalArgumentException exception) {
            return EbookIngestionStatus.QUEUED;
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }

        return value.substring(0, maxLength);
    }
}
