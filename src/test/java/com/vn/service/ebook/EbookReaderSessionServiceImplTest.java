package com.vn.service.ebook;

import com.vn.dto.ebook.response.EbookReadingSessionResponse;
import com.vn.dto.ebook.response.EbookSignedContentResponse;
import com.vn.entity.Book;
import com.vn.entity.BookEbook;
import com.vn.entity.EbookLoan;
import com.vn.entity.EbookReadingSession;
import com.vn.enums.BookEbookStatus;
import com.vn.enums.EbookLoanStatus;
import com.vn.enums.EbookReadingSessionStatus;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.repository.BookEbookRepository;
import com.vn.repository.EbookLoanRepository;
import com.vn.repository.EbookReadingSessionRepository;
import com.vn.service.impl.EbookReaderSessionServiceImpl;
import com.vn.service.storage.MediaDeliveryType;
import com.vn.service.storage.MediaResourceType;
import com.vn.service.storage.MediaSignedUrlResult;
import com.vn.service.storage.MediaStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EbookReaderSessionServiceImplTest {

    private BookEbookRepository bookEbookRepository;
    private EbookLoanRepository ebookLoanRepository;
    private EbookReadingSessionRepository readingSessionRepository;
    private EbookReadingSessionTokenService tokenService;
    private MediaStorageService mediaStorageService;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private EbookReaderSessionServiceImpl service;

    @BeforeEach
    void setUp() {
        bookEbookRepository = mock(BookEbookRepository.class);
        ebookLoanRepository = mock(EbookLoanRepository.class);
        readingSessionRepository = mock(EbookReadingSessionRepository.class);
        tokenService = mock(EbookReadingSessionTokenService.class);
        mediaStorageService = mock(MediaStorageService.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        service = new EbookReaderSessionServiceImpl(
                bookEbookRepository,
                ebookLoanRepository,
                readingSessionRepository,
                tokenService,
                mediaStorageService,
                redisTemplate
        );
    }

    @Test
    void createSessionShouldReturnRawTokenOnceAndCacheHash() {
        BookEbook ebook = ebook();
        EbookLoan loan = loan(Instant.now().plusSeconds(3600));
        when(bookEbookRepository.findFirstByBookIdAndStatusOrderByIdDesc(501L, BookEbookStatus.ACTIVE))
                .thenReturn(Optional.of(ebook));
        when(ebookLoanRepository.findFirstByMemberIdAndBookIdAndBookEbookIdAndStatusAndExpiredAtAfterOrderByExpiredAtDesc(
                eq(10L), eq(501L), eq(1001L), eq(EbookLoanStatus.ACTIVE), any(Instant.class)))
                .thenReturn(Optional.of(loan));
        when(tokenService.generateRawToken()).thenReturn("raw-token");
        when(tokenService.hashToken("raw-token")).thenReturn("hashed-token");
        when(tokenService.hashUserAgent("Mozilla")).thenReturn("ua-hash");
        when(readingSessionRepository.save(any(EbookReadingSession.class))).thenAnswer(invocation -> {
            EbookReadingSession session = invocation.getArgument(0);
            session.setId(7001L);
            return session;
        });

        EbookReadingSessionResponse response = service.createSession(10L, 501L, "127.0.0.1", "Mozilla");

        assertThat(response.sessionId()).isEqualTo(7001L);
        assertThat(response.sessionToken()).isEqualTo("raw-token");
        assertThat(response.bookEbookId()).isEqualTo(1001L);

        ArgumentCaptor<EbookReadingSession> sessionCaptor = ArgumentCaptor.forClass(EbookReadingSession.class);
        verify(readingSessionRepository).save(sessionCaptor.capture());
        assertThat(sessionCaptor.getValue().getSessionTokenHash()).isEqualTo("hashed-token");
        verify(valueOperations).set(eq("reading_session:hashed-token"), anyString(), any(java.time.Duration.class));
    }

    @Test
    void createSessionShouldRejectWhenMemberHasNoActiveLoan() {
        BookEbook ebook = ebook();
        when(bookEbookRepository.findFirstByBookIdAndStatusOrderByIdDesc(501L, BookEbookStatus.ACTIVE))
                .thenReturn(Optional.of(ebook));
        when(ebookLoanRepository.findFirstByMemberIdAndBookIdAndBookEbookIdAndStatusAndExpiredAtAfterOrderByExpiredAtDesc(
                eq(10L), eq(501L), eq(1001L), eq(EbookLoanStatus.ACTIVE), any(Instant.class)))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createSession(10L, 501L, "127.0.0.1", "Mozilla"))
                .isInstanceOf(AppException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.EBOOK_LOAN_REQUIRED.getCode());
    }

    @Test
    void getSignedContentShouldUseRedisSessionCacheWithoutQueryingSessionDb() {
        Instant sessionExpiresAt = Instant.now().plusSeconds(900);
        Instant loanExpiresAt = Instant.now().plusSeconds(3600);
        when(tokenService.hashToken("raw-token")).thenReturn("hashed-token");
        when(valueOperations.get("reading_session:hashed-token"))
                .thenReturn("""
                        {"sessionId":7001,"memberId":10,"bookId":501,"bookEbookId":1001,"loanId":3001,
                        "sessionExpiresAt":"%s","loanExpiresAt":"%s","status":"ACTIVE"}
                        """.formatted(sessionExpiresAt, loanExpiresAt));
        when(ebookLoanRepository.findById(3001L)).thenReturn(Optional.of(loan(loanExpiresAt)));
        when(bookEbookRepository.findByIdAndBookId(1001L, 501L)).thenReturn(Optional.of(ebook()));
        when(mediaStorageService.generateSignedUrl(any()))
                .thenReturn(new MediaSignedUrlResult("https://res.cloudinary.com/demo/raw/authenticated/signed.pdf", Instant.now().plusSeconds(300)));

        EbookSignedContentResponse response = service.getSignedContent(10L, 501L, "raw-token");

        assertThat(response.signedUrl()).contains("cloudinary.com");
        verify(readingSessionRepository, never()).findBySessionTokenHash(anyString());
    }

    @Test
    void getSignedContentShouldRejectClosedSessionFromDbFallback() {
        EbookReadingSession closedSession = readingSession(EbookReadingSessionStatus.CLOSED, Instant.now().plusSeconds(900));
        when(tokenService.hashToken("raw-token")).thenReturn("hashed-token");
        when(valueOperations.get("reading_session:hashed-token")).thenReturn(null);
        when(readingSessionRepository.findBySessionTokenHash("hashed-token")).thenReturn(Optional.of(closedSession));

        assertThatThrownBy(() -> service.getSignedContent(10L, 501L, "raw-token"))
                .isInstanceOf(AppException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.READING_SESSION_NOT_ACTIVE.getCode());
    }

    private BookEbook ebook() {
        Book book = new Book();
        book.setId(501L);

        BookEbook ebook = new BookEbook();
        ebook.setId(1001L);
        ebook.setBook(book);
        ebook.setStatus(BookEbookStatus.ACTIVE);
        ebook.setPublicId("pdf/9780132350884/main.pdf");
        ebook.setResourceType(MediaResourceType.RAW);
        ebook.setDeliveryType(MediaDeliveryType.AUTHENTICATED);
        ebook.setFormat("pdf");
        return ebook;
    }

    private EbookLoan loan(Instant expiredAt) {
        EbookLoan loan = new EbookLoan();
        loan.setId(3001L);
        loan.setMemberId(10L);
        loan.setBookId(501L);
        loan.setBookEbookId(1001L);
        loan.setStatus(EbookLoanStatus.ACTIVE);
        loan.setBorrowedAt(Instant.now().minusSeconds(60));
        loan.setExpiredAt(expiredAt);
        return loan;
    }

    private EbookReadingSession readingSession(EbookReadingSessionStatus status, Instant sessionExpiresAt) {
        EbookReadingSession session = new EbookReadingSession();
        session.setId(7001L);
        session.setSessionTokenHash("hashed-token");
        session.setMemberId(10L);
        session.setBookId(501L);
        session.setBookEbookId(1001L);
        session.setLoanId(3001L);
        session.setStatus(status);
        session.setSessionExpiresAt(sessionExpiresAt);
        return session;
    }
}
