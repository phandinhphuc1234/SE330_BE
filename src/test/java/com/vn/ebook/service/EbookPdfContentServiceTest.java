package com.vn.ebook.service;

import com.vn.ebook.config.ObjectStorageProperties;
import com.vn.ebook.entity.BookEbook;
import com.vn.ebook.enums.BookEbookStatus;
import com.vn.ebook.enums.EbookIngestionStatus;
import com.vn.ebook.repository.BookEbookRepository;
import com.vn.ebook.service.impl.EbookPdfContentService;
import com.vn.ebook.storage.EbookObjectStorageService;
import com.vn.shared.enums.MediaProvider;
import com.vn.shared.exception.AppException;
import com.vn.shared.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EbookPdfContentServiceTest {
    private final EbookReaderSessionService sessions = mock(EbookReaderSessionService.class);
    private final BookEbookRepository ebooks = mock(BookEbookRepository.class);
    private final EbookObjectStorageService storage = mock(EbookObjectStorageService.class);
    private final ObjectStorageProperties properties = new ObjectStorageProperties(
            "http://storage:8333", "http://127.0.0.1:8333", "unused", "unused", "us-east-1", "private", "temp");
    private final EbookPdfContentService service = new EbookPdfContentService(sessions, ebooks, storage, properties);
    private BookEbook ebook;

    @BeforeEach
    void prepare() {
        ebook = new BookEbook();
        ebook.setStatus(BookEbookStatus.ACTIVE);
        ebook.setProvider(MediaProvider.SEAWEEDFS);
        ebook.setBucketName("private");
        ebook.setObjectKey("ebooks/3343/11/original.pdf");
    }

    private void authorize() {
        when(sessions.authorizeAccess(7L, 3343L, "session")).thenReturn(new EbookReadingAccess(
                1L, 3343L, 11L, 2L, Instant.MAX, EbookIngestionStatus.INDEXED));
        when(ebooks.findByIdAndBookId(11L, 3343L)).thenReturn(Optional.of(ebook));
    }

    private void describe() {
        authorize();
        when(storage.describeRead("private", "ebooks/3343/11/original.pdf"))
                .thenReturn(new EbookObjectStorageService.EbookReadMetadata(100, "etag"));
    }

    @Test
    void authorizationFailureMustNotReadMetadataOrOpenStorage() {
        when(sessions.authorizeAccess(7L, 3343L, "session"))
                .thenThrow(new AppException(ErrorCode.READING_SESSION_FORBIDDEN));
        assertThatThrownBy(() -> service.read(7L, 3343L, "session", "bytes=0-1", false))
                .isInstanceOf(AppException.class);
        verifyNoInteractions(storage, ebooks);
    }

    @Test
    void expiredLoanMustNotOpenStorage() {
        when(sessions.authorizeAccess(7L, 3343L, "session"))
                .thenThrow(new AppException(ErrorCode.EBOOK_LOAN_EXPIRED));
        assertThatThrownBy(() -> service.read(7L, 3343L, "session", null, false))
                .isInstanceOf(AppException.class);
        verifyNoInteractions(storage, ebooks);
    }

    @Test
    void objectKeyOutsideAuthorizedEbookMustBeRejected() {
        authorize();
        ebook.setObjectKey("ebooks/3342/10/original.pdf");
        assertThatThrownBy(() -> service.read(7L, 3343L, "session", null, false))
                .isInstanceOf(AppException.class);
        verifyNoInteractions(storage);
    }

    @Test
    void wrongBucketMustBeRejected() {
        authorize();
        ebook.setBucketName("public");
        assertThatThrownBy(() -> service.read(7L, 3343L, "session", null, false))
                .isInstanceOf(AppException.class);
        verifyNoInteractions(storage);
    }

    @Test
    void inactiveEbookMustBeRejected() {
        authorize();
        ebook.setStatus(BookEbookStatus.INACTIVE);
        assertThatThrownBy(() -> service.read(7L, 3343L, "session", null, false))
                .isInstanceOf(AppException.class);
        verifyNoInteractions(storage);
    }

    @Test
    void fullReadMustStreamAndPinMetadataEtag() throws Exception {
        describe();
        var source = new ByteArrayInputStream(new byte[100]);
        when(storage.openRead("private", "ebooks/3343/11/original.pdf", null, "etag", 100))
                .thenReturn(source);
        EbookPdfContent response = service.read(7L, 3343L, "session", null, false);
        assertThat(response.contentLength()).isEqualTo(100);
        assertThat(response.partial()).isFalse();
        try (var input = response.resource().getInputStream()) {
            assertThat(input).isSameAs(source);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"bytes=0-9", "bytes=-10", "bytes=90-", "bytes=90-999"})
    void oneRangeMustBecomeBoundedStorageRange(String requested) {
        describe();
        long start = requested.equals("bytes=0-9") ? 0 : 90;
        long end = start + 9;
        when(storage.openRead("private", "ebooks/3343/11/original.pdf",
                "bytes=%d-%d".formatted(start, end), "etag", 10))
                .thenReturn(new ByteArrayInputStream(new byte[10]));
        EbookPdfContent response = service.read(7L, 3343L, "session", requested, false);
        assertThat(response.partial()).isTrue();
        assertThat(response.start()).isEqualTo(start);
        assertThat(response.end()).isEqualTo(end);
        assertThat(response.contentLength()).isEqualTo(10);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "garbage", "bytes=", "bytes=100-101", "bytes=90-20", "bytes=-0", "bytes=0-1,4-5", "bytes=9999999999999999999999-"})
    void invalidOrMultipleRangeMustFailWithoutOpeningStream(String requested) {
        describe();
        assertThatThrownBy(() -> service.read(7L, 3343L, "session", requested, false))
                .isInstanceOf(EbookRangeNotSatisfiableException.class);
        verify(storage, never()).openRead(any(), any(), any(), any(), anyLong());
    }

    @Test
    void headMustAuthorizeAndDescribeWithoutOpeningStream() {
        describe();
        EbookPdfContent response = service.read(7L, 3343L, "session", null, true);
        assertThat(response.resource()).isNull();
        assertThat(response.contentLength()).isEqualTo(100);
        verify(storage, never()).openRead(any(), any(), any(), any(), anyLong());
    }

    @Test
    void oversizedObjectMustNotOpenStream() {
        authorize();
        when(storage.describeRead("private", "ebooks/3343/11/original.pdf"))
                .thenReturn(new EbookObjectStorageService.EbookReadMetadata(100L * 1024 * 1024 + 1, "etag"));
        assertThatThrownBy(() -> service.read(7L, 3343L, "session", null, false))
                .isInstanceOf(AppException.class);
        verify(storage, never()).openRead(any(), any(), any(), any(), anyLong());
    }
}
