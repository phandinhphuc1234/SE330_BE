package com.vn.service;

import com.vn.dto.ebook.response.BookEbookUploadResponse;
import com.vn.entity.Book;
import com.vn.entity.BookEbook;
import com.vn.enums.BookEbookStatus;
import com.vn.enums.EbookAccessType;
import com.vn.enums.EbookIngestionStatus;
import com.vn.enums.MediaProvider;
import com.vn.repository.BookEbookRepository;
import com.vn.repository.BookRepository;
import com.vn.service.ebook.EbookPdfValidator;
import com.vn.service.impl.BookEbookServiceImpl;
import com.vn.service.impl.ebook.EbookRagIngestionAsyncProcessor;
import com.vn.service.storage.MediaDeliveryType;
import com.vn.service.storage.MediaResourceType;
import com.vn.service.storage.ebook.EbookObjectStorageService;
import com.vn.service.storage.ebook.EbookObjectStorageService.EbookObjectMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookEbookServiceImplTest {

    @Mock
    private BookRepository bookRepository;

    @Mock
    private BookEbookRepository bookEbookRepository;

    @Mock
    private EbookObjectStorageService ebookObjectStorageService;

    @Mock
    private EbookPdfValidator ebookPdfValidator;

    @Mock
    private EbookRagIngestionAsyncProcessor ragIngestionAsyncProcessor;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private MultipartFile file;

    private BookEbookServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BookEbookServiceImpl(
                bookRepository,
                bookEbookRepository,
                ebookObjectStorageService,
                ebookPdfValidator,
                ragIngestionAsyncProcessor,
                transactionTemplate
        );
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
    }

    @Test
    void uploadMainPdf_shouldScheduleRagIngestionAsyncAndReturnQueuedResponse() {
        Book book = new Book();
        book.setId(10L);
        BookEbook[] ebookRow = new BookEbook[1];
        EbookObjectMetadata metadata = new EbookObjectMetadata(
                "library-private",
                "ebooks/10/200/original.pdf",
                "clean-code.pdf",
                "application/pdf",
                1024L,
                "abc123",
                Instant.now()
        );

        when(bookRepository.findByIdAndDeletedAtIsNull(10L)).thenReturn(Optional.of(book));
        when(bookEbookRepository.findFirstByBookIdOrderByIdDesc(10L)).thenReturn(Optional.empty());
        when(bookEbookRepository.saveAndFlush(any(BookEbook.class))).thenAnswer(invocation -> {
            BookEbook ebook = invocation.getArgument(0);
            ebook.setId(200L);
            applyEntityDefaults(ebook);
            ebookRow[0] = ebook;
            return ebook;
        });
        when(bookEbookRepository.findById(200L)).thenAnswer(invocation -> Optional.of(ebookRow[0]));
        when(bookEbookRepository.save(any(BookEbook.class))).thenAnswer(invocation -> {
            BookEbook ebook = invocation.getArgument(0);
            applyEntityDefaults(ebook);
            ebookRow[0] = ebook;
            return ebook;
        });
        when(ebookObjectStorageService.upload(eq("ebooks/10/200/original.pdf"), eq(file))).thenReturn(metadata);

        BookEbookUploadResponse response = service.uploadMainPdf(10L, file);

        assertThat(response.bookEbookId()).isEqualTo(200L);
        assertThat(response.ingestionStatus()).isEqualTo(EbookIngestionStatus.QUEUED.name());
        assertThat(response.ragDocumentId()).isNull();
        assertThat(response.ragJobId()).isNull();
        verify(ebookPdfValidator).validate(file);
        verify(ebookObjectStorageService).upload("ebooks/10/200/original.pdf", file);
        verify(ragIngestionAsyncProcessor).requestIngestionAsync(200L);
    }

    private void applyEntityDefaults(BookEbook ebook) {
        if (ebook.getProvider() == null) {
            ebook.setProvider(MediaProvider.SEAWEEDFS);
        }
        if (ebook.getResourceType() == null) {
            ebook.setResourceType(MediaResourceType.RAW);
        }
        if (ebook.getDeliveryType() == null) {
            ebook.setDeliveryType(MediaDeliveryType.PRIVATE);
        }
        if (ebook.getStatus() == null) {
            ebook.setStatus(BookEbookStatus.ACTIVE);
        }
        if (ebook.getMaxConcurrentLoans() == null) {
            ebook.setMaxConcurrentLoans(5);
        }
        if (ebook.getLoanDurationDays() == null) {
            ebook.setLoanDurationDays(14);
        }
        if (ebook.getAccessType() == null) {
            ebook.setAccessType(EbookAccessType.FREE);
        }
        if (ebook.getAccessFee() == null) {
            ebook.setAccessFee(BigDecimal.ZERO);
        }
        if (ebook.getCurrency() == null) {
            ebook.setCurrency("VND");
        }
        if (ebook.getAccessDurationDays() == null) {
            ebook.setAccessDurationDays(14);
        }
    }
}
