package com.vn.service.impl.ebook;

import com.vn.entity.BookEbook;
import com.vn.enums.EbookIngestionStatus;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.repository.BookEbookRepository;
import com.vn.service.rag.RagIngestionClient;
import com.vn.service.rag.RagIngestionClient.IngestionRequest;
import com.vn.service.rag.RagIngestionClient.IngestionResponse;
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
    private final TransactionTemplate transactionTemplate;

    @Async("ragIngestionExecutor")
    public void requestIngestionAsync(Long ebookId) {
        try {
            IngestionRequest request = transactionTemplate.execute(status -> buildRequest(ebookId));
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

    private IngestionRequest buildRequest(Long ebookId) {
        BookEbook ebook = bookEbookRepository.findById(ebookId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));
        return IngestionRequest.libraryEbook(
                ebook.getBook().getId(),
                ebook.getId(),
                ebook.getBucketName(),
                ebook.getObjectKey(),
                ebook.getOriginalFilename(),
                ebook.getMimeType(),
                ebook.getSizeBytes(),
                ebook.getChecksumSha256()
        );
    }

    private void markIngestionQueued(Long ebookId, IngestionResponse response) {
        BookEbook ebook = bookEbookRepository.findById(ebookId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));
        ebook.setRagDocumentId(response != null ? response.documentId() : null);
        ebook.setRagJobId(response != null ? response.ingestionJobId() : null);
        ebook.setIngestionStatus(normalizeIngestionStatus(response != null ? response.status() : null));
        ebook.setIngestionLastError(null);
        ebook.setIndexingRequestedAt(Instant.now());
        bookEbookRepository.save(ebook);
    }

    private void markIngestionFailed(Long ebookId, String errorCode) {
        BookEbook ebook = bookEbookRepository.findById(ebookId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));
        ebook.setIngestionStatus(EbookIngestionStatus.FAILED);
        ebook.setIngestionLastError(truncate(errorCode, 1000));
        ebook.setIndexingRequestedAt(Instant.now());
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
            return EbookIngestionStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
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
