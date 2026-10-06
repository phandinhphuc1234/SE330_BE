package com.vn.ebook.service;

import com.vn.ebook.dto.request.EbookSemanticSearchRequest;
import com.vn.ebook.dto.response.EbookSemanticSearchResponse;
import com.vn.ebook.enums.EbookAiOperation;
import com.vn.ebook.enums.EbookIngestionStatus;
import com.vn.ebook.service.impl.EbookSemanticSearchServiceImpl;
import com.vn.rag.client.RagRetrievalClient;
import com.vn.rag.client.RagRetrievalClient.RetrievalCitation;
import com.vn.rag.client.RagRetrievalClient.RetrievalHit;
import com.vn.rag.client.RagRetrievalClient.RetrievalResponse;
import com.vn.shared.exception.AppException;
import com.vn.shared.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EbookSemanticSearchServiceImplTest {

    @Mock
    private EbookReaderSessionService ebookReaderSessionService;

    @Mock
    private RagRetrievalClient ragRetrievalClient;

    @Mock
    private EbookAiRateLimitService rateLimitService;

    @Mock
    private EbookAiMetrics metrics;

    private EbookSemanticSearchServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new EbookSemanticSearchServiceImpl(
                ebookReaderSessionService,
                ragRetrievalClient,
                rateLimitService,
                metrics
        );
    }

    @Test
    void searchShouldUseAuthorizedEbookScopeAndMapEvidence() {
        when(ebookReaderSessionService.authorizeAccess(10L, 501L, "reader-token"))
                .thenReturn(access(EbookIngestionStatus.INDEXED));
        when(ragRetrievalClient.search(org.mockito.ArgumentMatchers.any()))
                .thenReturn(retrieval(1001L, 501L));

        EbookSemanticSearchResponse response = service.search(
                10L,
                501L,
                "reader-token",
                new EbookSemanticSearchRequest(" dependency inversion ", 5, new BigDecimal("0.70"))
        );

        assertThat(response.bookId()).isEqualTo(501L);
        assertThat(response.ebookId()).isEqualTo(1001L);
        assertThat(response.resultCount()).isEqualTo(1);
        assertThat(response.results().getFirst().chunkId()).isEqualTo("vector-1");
        assertThat(response.results().getFirst().citation().pageStart()).isEqualTo(42);
        verify(rateLimitService).check(10L, 501L, EbookAiOperation.SEARCH);

        ArgumentCaptor<RagRetrievalClient.RetrievalRequest> captor =
                ArgumentCaptor.forClass(RagRetrievalClient.RetrievalRequest.class);
        verify(ragRetrievalClient).search(captor.capture());
        assertThat(captor.getValue().query()).isEqualTo("dependency inversion");
        assertThat(captor.getValue().ebookId()).isEqualTo(1001L);
        assertThat(captor.getValue().bookId()).isNull();
        assertThat(captor.getValue().documentId()).isNull();
    }

    @Test
    void searchShouldNotCallRagWhenReadingAccessIsDenied() {
        when(ebookReaderSessionService.authorizeAccess(10L, 501L, "reader-token"))
                .thenThrow(new AppException(ErrorCode.EBOOK_LOAN_REQUIRED));

        assertThatThrownBy(() -> service.search(
                10L, 501L, "reader-token", new EbookSemanticSearchRequest("query", null, null)))
                .isInstanceOfSatisfying(AppException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo(ErrorCode.EBOOK_LOAN_REQUIRED.getCode()));

        verify(ragRetrievalClient, never()).search(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void searchShouldNotCallRagBeforeEbookIsIndexed() {
        when(ebookReaderSessionService.authorizeAccess(10L, 501L, "reader-token"))
                .thenReturn(access(EbookIngestionStatus.INDEXING));

        assertThatThrownBy(() -> service.search(
                10L, 501L, "reader-token", new EbookSemanticSearchRequest("query", null, null)))
                .isInstanceOfSatisfying(AppException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo(ErrorCode.EBOOK_AI_NOT_READY.getCode()));

        verify(ragRetrievalClient, never()).search(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void searchShouldFailClosedWhenRagReturnsAnotherEbook() {
        when(ebookReaderSessionService.authorizeAccess(10L, 501L, "reader-token"))
                .thenReturn(access(EbookIngestionStatus.INDEXED));
        when(ragRetrievalClient.search(org.mockito.ArgumentMatchers.any()))
                .thenReturn(retrieval(9999L, 501L));

        assertThatThrownBy(() -> service.search(
                10L, 501L, "reader-token", new EbookSemanticSearchRequest("query", null, null)))
                .isInstanceOfSatisfying(AppException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo(ErrorCode.RAG_SERVICE_ERROR.getCode()));
    }

    private EbookReadingAccess access(EbookIngestionStatus status) {
        return new EbookReadingAccess(
                7001L,
                501L,
                1001L,
                3001L,
                Instant.parse("2026-10-05T10:00:00Z"),
                status
        );
    }

    private RetrievalResponse retrieval(Long ebookId, Long bookId) {
        RetrievalCitation citation = new RetrievalCitation(
                "doc_ebook_1001",
                77L,
                bookId,
                ebookId,
                "SOLID",
                3,
                42,
                43,
                12,
                "vector-1"
        );
        RetrievalHit hit = new RetrievalHit(
                "point-1",
                "vector-1",
                0.91,
                "Dependency inversion means...",
                citation,
                Map.of()
        );
        return new RetrievalResponse(
                "query-hash",
                "retrieval-query",
                "embedding-v1",
                5,
                1,
                Map.of("ebook_id", ebookId),
                List.of(hit)
        );
    }
}
