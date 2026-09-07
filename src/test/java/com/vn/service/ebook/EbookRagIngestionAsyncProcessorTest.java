package com.vn.service.ebook;

import com.vn.config.RagServiceProperties;
import com.vn.entity.Book;
import com.vn.entity.BookEbook;
import com.vn.enums.EbookIngestionStatus;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.repository.BookEbookRepository;
import com.vn.service.impl.ebook.EbookRagIngestionAsyncProcessor;
import com.vn.service.rag.RagIngestionClient;
import com.vn.service.rag.RagIngestionClient.IngestionRequest;
import com.vn.service.rag.RagIngestionClient.IngestionResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EbookRagIngestionAsyncProcessorTest {

    @Mock
    private BookEbookRepository bookEbookRepository;

    @Mock
    private RagIngestionClient ragIngestionClient;

    @Mock
    private TransactionTemplate transactionTemplate;

    private EbookRagIngestionAsyncProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new EbookRagIngestionAsyncProcessor(
                bookEbookRepository,
                ragIngestionClient,
                new RagServiceProperties(true, "http://localhost:8000", "test-key"),
                transactionTemplate
        );
    }

    @Test
    void requestIngestionAsync_shouldSendLibraryEbookPayloadAndStoreRagJob() {
        BookEbook ebook = ebook();
        when(bookEbookRepository.findById(200L)).thenReturn(Optional.of(ebook));
        when(ragIngestionClient.ingestLibraryEbook(any()))
                .thenReturn(new IngestionResponse("doc_ebook_200", 300L, "QUEUED"));
        stubTransactions();

        processor.requestIngestionAsync(200L);

        ArgumentCaptor<IngestionRequest> requestCaptor = ArgumentCaptor.forClass(IngestionRequest.class);
        verify(ragIngestionClient).ingestLibraryEbook(requestCaptor.capture());
        IngestionRequest request = requestCaptor.getValue();
        assertThat(request.sourceType()).isEqualTo("LIBRARY_EBOOK");
        assertThat(request.bookId()).isEqualTo(10L);
        assertThat(request.ebookId()).isEqualTo(200L);
        assertThat(request.bucket()).isEqualTo("library-private");
        assertThat(request.objectKey()).isEqualTo("ebooks/10/200/original.pdf");
        assertThat(request.checksumSha256()).isEqualTo("abc123");

        assertThat(ebook.getRagDocumentId()).isEqualTo("doc_ebook_200");
        assertThat(ebook.getRagJobId()).isEqualTo(300L);
        assertThat(ebook.getIngestionStatus()).isEqualTo(EbookIngestionStatus.QUEUED);
        verify(bookEbookRepository).save(ebook);
    }

    @Test
    void requestIngestionAsync_shouldDoNothingWhenRagIsDisabled() {
        EbookRagIngestionAsyncProcessor disabledProcessor = new EbookRagIngestionAsyncProcessor(
                bookEbookRepository,
                ragIngestionClient,
                new RagServiceProperties(false, "http://localhost:8000", ""),
                transactionTemplate
        );

        disabledProcessor.requestIngestionAsync(200L);

        verifyNoInteractions(bookEbookRepository, ragIngestionClient, transactionTemplate);
    }

    @Test
    void requestIngestionAsync_shouldMarkFailedWhenRagClientFails() {
        BookEbook ebook = ebook();
        when(bookEbookRepository.findById(200L)).thenReturn(Optional.of(ebook));
        when(ragIngestionClient.ingestLibraryEbook(any()))
                .thenThrow(new AppException(ErrorCode.RAG_SERVICE_ERROR));
        stubTransactions();

        processor.requestIngestionAsync(200L);

        assertThat(ebook.getIngestionStatus()).isEqualTo(EbookIngestionStatus.FAILED);
        assertThat(ebook.getIngestionLastError()).isEqualTo(ErrorCode.RAG_SERVICE_ERROR.getCode());
        verify(bookEbookRepository).save(ebook);
    }

    private void stubTransactions() {
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
    }

    private BookEbook ebook() {
        Book book = new Book();
        book.setId(10L);

        BookEbook ebook = new BookEbook();
        ebook.setId(200L);
        ebook.setBook(book);
        ebook.setBucketName("library-private");
        ebook.setObjectKey("ebooks/10/200/original.pdf");
        ebook.setOriginalFilename("clean-code.pdf");
        ebook.setMimeType("application/pdf");
        ebook.setSizeBytes(1024L);
        ebook.setChecksumSha256("abc123");
        ebook.setIngestionStatus(EbookIngestionStatus.QUEUED);
        return ebook;
    }
}
