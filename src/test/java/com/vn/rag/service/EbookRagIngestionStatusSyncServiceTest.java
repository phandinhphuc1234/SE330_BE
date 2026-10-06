package com.vn.rag.service;

import com.vn.ebook.entity.BookEbook;
import com.vn.ebook.enums.EbookIngestionStatus;
import com.vn.ebook.repository.BookEbookRepository;
import com.vn.rag.client.RagIngestionClient;
import com.vn.rag.client.RagIngestionClient.IngestionStatusResponse;
import com.vn.rag.config.RagIngestionSyncProperties;
import com.vn.rag.config.RagServiceProperties;
import com.vn.shared.exception.AppException;
import com.vn.shared.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EbookRagIngestionStatusSyncServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-05T02:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    private BookEbookRepository bookEbookRepository;

    @Mock
    private RagIngestionClient ragIngestionClient;

    @Mock
    private TransactionTemplate transactionTemplate;

    private EbookRagIngestionStatusSyncService service;

    @BeforeEach
    void setUp() {
        service = new EbookRagIngestionStatusSyncService(
                bookEbookRepository,
                ragIngestionClient,
                new RagServiceProperties(true, "http://rag-service:8000", "internal-test-key",
                        Duration.ofSeconds(3), Duration.ofSeconds(30)),
                syncProperties(),
                transactionTemplate,
                CLOCK
        );
    }

    @Test
    void syncBatch_shouldUpdateEbookWhenRagFinishesIndexing() {
        BookEbook ebook = pendingEbook(EbookIngestionStatus.CHUNKED);
        stubTransactions();
        stubSingleCandidate(ebook);
        when(ragIngestionClient.getIngestionStatus(300L)).thenReturn(new IngestionStatusResponse(
                "doc_ebook_200", 300L, "INDEXED", "indexed", null, null
        ));

        EbookRagIngestionStatusSyncService.SyncSummary summary = service.syncBatch(25);

        assertThat(summary.polled()).isEqualTo(1);
        assertThat(summary.updated()).isEqualTo(1);
        assertThat(summary.pollFailures()).isZero();
        assertThat(ebook.getIngestionStatus()).isEqualTo(EbookIngestionStatus.INDEXED);
        assertThat(ebook.getIngestionStage()).isEqualTo("indexed");
        assertThat(ebook.getRagDocumentId()).isEqualTo("doc_ebook_200");
        assertThat(ebook.getIngestionLastCheckedAt()).isNotNull();
        assertThat(ebook.getIngestionPollFailureCount()).isZero();
        assertThat(ebook.getIngestionNextCheckAt()).isNull();
        assertThat(ebook.getIndexingCompletedAt()).isNotNull();
        assertThat(ebook.getIngestionLastError()).isNull();
        verify(bookEbookRepository).save(ebook);
    }

    @Test
    void syncBatch_shouldStoreRagFailureDetails() {
        BookEbook ebook = pendingEbook(EbookIngestionStatus.PROCESSING);
        stubTransactions();
        stubSingleCandidate(ebook);
        when(ragIngestionClient.getIngestionStatus(300L)).thenReturn(new IngestionStatusResponse(
                "doc_ebook_200", 300L, "FAILED", "failed", "INGESTION_FAILED", "PDF has no text"
        ));

        EbookRagIngestionStatusSyncService.SyncSummary summary = service.syncBatch(25);

        assertThat(summary.updated()).isEqualTo(1);
        assertThat(ebook.getIngestionStatus()).isEqualTo(EbookIngestionStatus.INDEX_FAILED);
        assertThat(ebook.getIngestionLastError()).isEqualTo("INGESTION_FAILED: PDF has no text");
        assertThat(ebook.getIndexingCompletedAt()).isNull();
        verify(bookEbookRepository).save(ebook);
    }

    @Test
    void syncBatch_shouldScheduleBackoffWhenRagIsTemporarilyUnavailable() {
        BookEbook ebook = pendingEbook(EbookIngestionStatus.PROCESSING);
        stubTransactions();
        stubSingleCandidate(ebook);
        when(ragIngestionClient.getIngestionStatus(300L))
                .thenThrow(new AppException(ErrorCode.RAG_SERVICE_ERROR));

        EbookRagIngestionStatusSyncService.SyncSummary summary = service.syncBatch(25);

        assertThat(summary.pollFailures()).isEqualTo(1);
        assertThat(summary.updated()).isZero();
        assertThat(ebook.getIngestionStatus()).isEqualTo(EbookIngestionStatus.PROCESSING);
        assertThat(ebook.getIngestionPollFailureCount()).isEqualTo(1);
        assertThat(ebook.getIngestionNextCheckAt()).isEqualTo(NOW.plusSeconds(10));
        assertThat(ebook.getIngestionLastError()).isEqualTo("STATUS_POLL_FAILED: RAG_SERVICE_ERROR");
        verify(bookEbookRepository).save(ebook);
    }

    @Test
    void syncBatch_shouldMarkIndexFailedAfterPollFailuresAreExhausted() {
        BookEbook ebook = pendingEbook(EbookIngestionStatus.PROCESSING);
        ebook.setIngestionPollFailureCount(2);
        stubTransactions();
        stubSingleCandidate(ebook);
        when(ragIngestionClient.getIngestionStatus(300L))
                .thenThrow(new AppException(ErrorCode.RAG_SERVICE_ERROR));

        EbookRagIngestionStatusSyncService.SyncSummary summary = service.syncBatch(25);

        assertThat(summary.pollFailures()).isEqualTo(1);
        assertThat(summary.permanentlyFailed()).isEqualTo(1);
        assertThat(ebook.getIngestionStatus()).isEqualTo(EbookIngestionStatus.INDEX_FAILED);
        assertThat(ebook.getIngestionStage()).isEqualTo("status_poll_failed");
        assertThat(ebook.getIngestionPollFailureCount()).isEqualTo(3);
        assertThat(ebook.getIngestionNextCheckAt()).isNull();
        verify(bookEbookRepository).save(ebook);
    }

    @Test
    void syncBatch_shouldIgnoreAStaleStatusInsteadOfMovingBackward() {
        BookEbook ebook = pendingEbook(EbookIngestionStatus.CHUNKED);
        stubTransactions();
        stubSingleCandidate(ebook);
        when(ragIngestionClient.getIngestionStatus(300L)).thenReturn(new IngestionStatusResponse(
                "doc_ebook_200", 300L, "PROCESSING", "parsing_pdf", null, null
        ));

        EbookRagIngestionStatusSyncService.SyncSummary summary = service.syncBatch(25);

        assertThat(summary.skipped()).isEqualTo(1);
        assertThat(ebook.getIngestionStatus()).isEqualTo(EbookIngestionStatus.CHUNKED);
        verify(bookEbookRepository, never()).save(any());
    }

    @Test
    void syncBatch_shouldFailClosedWhenJobBelongsToAnotherEbook() {
        BookEbook ebook = pendingEbook(EbookIngestionStatus.PROCESSING);
        stubTransactions();
        stubSingleCandidate(ebook);
        when(ragIngestionClient.getIngestionStatus(300L)).thenReturn(new IngestionStatusResponse(
                "doc_ebook_999", 300L, "INDEXED", "indexed", null, null
        ));

        EbookRagIngestionStatusSyncService.SyncSummary summary = service.syncBatch(25);

        assertThat(summary.updated()).isEqualTo(1);
        assertThat(ebook.getIngestionStatus()).isEqualTo(EbookIngestionStatus.INDEX_FAILED);
        assertThat(ebook.getIngestionStage()).isEqualTo("status_identity_mismatch");
        assertThat(ebook.getIngestionLastError())
                .isEqualTo("RAG_DOCUMENT_MISMATCH: expected=doc_ebook_200, actual=doc_ebook_999");
        assertThat(ebook.getIndexingCompletedAt()).isNull();
        verify(bookEbookRepository).save(ebook);
    }

    @Test
    void syncBatch_shouldDoNothingWhenRagIsDisabled() {
        service = new EbookRagIngestionStatusSyncService(
                bookEbookRepository,
                ragIngestionClient,
                new RagServiceProperties(false, "http://rag-service:8000", "",
                        Duration.ofSeconds(3), Duration.ofSeconds(30)),
                syncProperties(),
                transactionTemplate,
                CLOCK
        );

        EbookRagIngestionStatusSyncService.SyncSummary summary = service.syncBatch(25);

        assertThat(summary).isEqualTo(EbookRagIngestionStatusSyncService.SyncSummary.empty());
        verifyNoInteractions(bookEbookRepository, ragIngestionClient);
    }

    private void stubSingleCandidate(BookEbook ebook) {
        when(bookEbookRepository.findIngestionSyncCandidateIds(any(), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(ebook.getId()));
        when(bookEbookRepository.findById(anyLong())).thenReturn(Optional.of(ebook));
    }

    private void stubTransactions() {
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
    }

    private BookEbook pendingEbook(EbookIngestionStatus status) {
        BookEbook ebook = new BookEbook();
        ebook.setId(200L);
        ebook.setRagJobId(300L);
        ebook.setIngestionStatus(status);
        return ebook;
    }

    private RagIngestionSyncProperties syncProperties() {
        return new RagIngestionSyncProperties(25, 3, Duration.ofSeconds(10), Duration.ofMinutes(1));
    }
}
