package com.vn.ebook.service;

import com.vn.ebook.dto.request.EbookAskRequest;
import com.vn.ebook.dto.response.EbookAnswerResponse;
import com.vn.ebook.enums.EbookAiOperation;
import com.vn.ebook.enums.EbookIngestionStatus;
import com.vn.ebook.service.impl.EbookQuestionAnswerServiceImpl;
import com.vn.rag.client.RagAnswerClient;
import com.vn.rag.client.RagAnswerClient.AnswerCitation;
import com.vn.rag.client.RagAnswerClient.AnswerResponse;
import com.vn.shared.exception.AppException;
import com.vn.shared.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EbookQuestionAnswerServiceImplTest {

    @Mock
    private EbookReaderSessionService ebookReaderSessionService;

    @Mock
    private RagAnswerClient ragAnswerClient;

    @Mock
    private EbookAiRateLimitService rateLimitService;

    @Mock
    private EbookAiMetrics metrics;

    private EbookQuestionAnswerServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new EbookQuestionAnswerServiceImpl(
                ebookReaderSessionService,
                ragAnswerClient,
                rateLimitService,
                metrics
        );
    }

    @Test
    void answerShouldReturnVerifiedGroundedResponse() {
        when(ebookReaderSessionService.authorizeAccess(10L, 501L, "reader-token"))
                .thenReturn(access(EbookIngestionStatus.INDEXED));
        when(ragAnswerClient.answer(any())).thenReturn(groundedResponse(1001L, 501L));

        EbookAnswerResponse response = service.answer(
                10L, 501L, "reader-token", new EbookAskRequest(" DIP là gì? ", null, null)
        );

        assertThat(response.grounded()).isTrue();
        assertThat(response.citations()).hasSize(1);
        assertThat(response.citations().getFirst().ebookId()).isEqualTo(1001L);
        assertThat(response.citations().getFirst().pageStart()).isEqualTo(42);
        verify(rateLimitService).check(10L, 501L, EbookAiOperation.ASK);
    }

    @Test
    void answerShouldNotCallRagBeforeEbookIsIndexed() {
        when(ebookReaderSessionService.authorizeAccess(10L, 501L, "reader-token"))
                .thenReturn(access(EbookIngestionStatus.INDEXING));

        assertThatThrownBy(() -> service.answer(
                10L, 501L, "reader-token", new EbookAskRequest("Question", null, null)))
                .isInstanceOfSatisfying(AppException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo(ErrorCode.EBOOK_AI_NOT_READY.getCode()));
        verify(ragAnswerClient, never()).answer(any());
    }

    @Test
    void answerShouldFailClosedForCitationFromAnotherEbook() {
        when(ebookReaderSessionService.authorizeAccess(10L, 501L, "reader-token"))
                .thenReturn(access(EbookIngestionStatus.INDEXED));
        when(ragAnswerClient.answer(any())).thenReturn(groundedResponse(9999L, 501L));

        assertThatThrownBy(() -> service.answer(
                10L, 501L, "reader-token", new EbookAskRequest("Question", null, null)))
                .isInstanceOfSatisfying(AppException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo(ErrorCode.RAG_SERVICE_ERROR.getCode()));
    }

    @Test
    void answerShouldAcceptConsistentAbstention() {
        when(ebookReaderSessionService.authorizeAccess(10L, 501L, "reader-token"))
                .thenReturn(access(EbookIngestionStatus.INDEXED));
        when(ragAnswerClient.answer(any())).thenReturn(new AnswerResponse(
                "Không đủ bằng chứng trong ebook để trả lời câu hỏi này.",
                false,
                true,
                "INSUFFICIENT_EVIDENCE",
                List.of(),
                null,
                "library-ebook-answer-v1"
        ));

        EbookAnswerResponse response = service.answer(
                10L, 501L, "reader-token", new EbookAskRequest("Ngoài phạm vi?", null, null)
        );

        assertThat(response.abstained()).isTrue();
        assertThat(response.citations()).isEmpty();
    }

    @Test
    void answerShouldRejectUngroundedClaimWithoutCitation() {
        when(ebookReaderSessionService.authorizeAccess(10L, 501L, "reader-token"))
                .thenReturn(access(EbookIngestionStatus.INDEXED));
        when(ragAnswerClient.answer(any())).thenReturn(new AnswerResponse(
                "Invented answer", true, false, null, List.of(), "model", "prompt-v1"
        ));

        assertThatThrownBy(() -> service.answer(
                10L, 501L, "reader-token", new EbookAskRequest("Question", null, null)))
                .isInstanceOfSatisfying(AppException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo(ErrorCode.RAG_SERVICE_ERROR.getCode()));
    }

    private EbookReadingAccess access(EbookIngestionStatus status) {
        return new EbookReadingAccess(
                7001L, 501L, 1001L, 3001L,
                Instant.parse("2026-10-05T10:00:00Z"), status
        );
    }

    private AnswerResponse groundedResponse(Long ebookId, Long bookId) {
        return new AnswerResponse(
                "DIP separates modules.",
                true,
                false,
                null,
                List.of(new AnswerCitation(
                        "doc_ebook_1001", 77L, bookId, ebookId, "SOLID", 3,
                        42, 43, 12, "vector-1", "Dependency inversion means...", 0.91
                )),
                "test-model",
                "library-ebook-answer-v1"
        );
    }
}
