package com.vn.service.payment.business;

import com.vn.entity.Book;
import com.vn.entity.BookEbook;
import com.vn.entity.EbookLoan;
import com.vn.entity.Member;
import com.vn.entity.PaymentTransaction;
import com.vn.enums.BookEbookStatus;
import com.vn.enums.EbookAccessType;
import com.vn.enums.EbookLoanStatus;
import com.vn.enums.PaymentProvider;
import com.vn.enums.PaymentPurpose;
import com.vn.enums.PaymentStatus;
import com.vn.enums.PaymentTargetType;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.repository.BookEbookRepository;
import com.vn.repository.EbookLoanRepository;
import com.vn.service.borrow.MediaBorrowLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EbookPaymentApplierTest {

    private BookEbookRepository bookEbookRepository;
    private EbookLoanRepository ebookLoanRepository;
    private MediaBorrowLimitService mediaBorrowLimitService;
    private EbookPaymentApplier applier;

    @BeforeEach
    void setUp() {
        bookEbookRepository = mock(BookEbookRepository.class);
        ebookLoanRepository = mock(EbookLoanRepository.class);
        mediaBorrowLimitService = mock(MediaBorrowLimitService.class);
        applier = new EbookPaymentApplier(bookEbookRepository, ebookLoanRepository, mediaBorrowLimitService);
    }

    @Test
    void validatePayableTargetShouldRejectUnavailableLicense() {
        BookEbook ebook = ebook();
        when(bookEbookRepository.findById(1001L)).thenReturn(Optional.of(ebook));
        when(ebookLoanRepository.countByBookEbookIdAndStatusAndExpiredAtAfter(
                eq(1001L), eq(EbookLoanStatus.ACTIVE), any(Instant.class)))
                .thenReturn(ebook.getMaxConcurrentLoans().longValue());

        assertThatThrownBy(() -> applier.validatePayableTarget(10L, 1001L))
                .isInstanceOf(AppException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.EBOOK_LICENSE_NOT_AVAILABLE.getCode());
    }

    @Test
    void applySuccessShouldCreateActiveLoanOnce() {
        PaymentTransaction payment = payment();
        BookEbook ebook = ebook();
        when(ebookLoanRepository.existsByPaymentId(9001L)).thenReturn(false);
        when(mediaBorrowLimitService.lockMember(10L)).thenReturn(member());
        when(bookEbookRepository.findLockedById(1001L)).thenReturn(Optional.of(ebook));
        when(ebookLoanRepository.countByBookEbookIdAndStatusAndExpiredAtAfter(
                eq(1001L), eq(EbookLoanStatus.ACTIVE), any(Instant.class)))
                .thenReturn(0L);

        applier.applySuccess(payment);

        ArgumentCaptor<EbookLoan> loanCaptor = ArgumentCaptor.forClass(EbookLoan.class);
        verify(ebookLoanRepository).save(loanCaptor.capture());
        EbookLoan loan = loanCaptor.getValue();
        assertThat(loan.getMemberId()).isEqualTo(10L);
        assertThat(loan.getBookId()).isEqualTo(501L);
        assertThat(loan.getBookEbookId()).isEqualTo(1001L);
        assertThat(loan.getPaymentId()).isEqualTo(9001L);
        assertThat(loan.getStatus()).isEqualTo(EbookLoanStatus.ACTIVE);
        assertThat(loan.getExpiredAt()).isAfter(loan.getBorrowedAt());
        assertThat(payment.getProviderMetadata()).containsEntry("ebookFulfillmentStatus", "FULFILLED");
    }

    @Test
    void applySuccessShouldReturnWhenPaymentAlreadyHasLoan() {
        PaymentTransaction payment = payment();
        when(ebookLoanRepository.existsByPaymentId(9001L)).thenReturn(true);

        applier.applySuccess(payment);

        verify(bookEbookRepository, never()).findLockedById(any());
        verify(ebookLoanRepository, never()).save(any());
    }

    @Test
    void applySuccessShouldNotCreateLoanWhenLicenseUnavailableAfterIpnRace() {
        PaymentTransaction payment = payment();
        BookEbook ebook = ebook();
        when(ebookLoanRepository.existsByPaymentId(9001L)).thenReturn(false);
        when(mediaBorrowLimitService.lockMember(10L)).thenReturn(member());
        when(bookEbookRepository.findLockedById(1001L)).thenReturn(Optional.of(ebook));
        when(ebookLoanRepository.countByBookEbookIdAndStatusAndExpiredAtAfter(
                eq(1001L), eq(EbookLoanStatus.ACTIVE), any(Instant.class)))
                .thenReturn(ebook.getMaxConcurrentLoans().longValue());

        applier.applySuccess(payment);

        verify(ebookLoanRepository, never()).save(any());
        assertThat(payment.getProviderMetadata())
                .containsEntry("ebookFulfillmentStatus", "LICENSE_UNAVAILABLE");
    }

    @Test
    void validatePayableTargetShouldRejectWhenTotalMediaBorrowLimitReached() {
        BookEbook ebook = ebook();
        when(bookEbookRepository.findById(1001L)).thenReturn(Optional.of(ebook));
        org.mockito.Mockito.doThrow(new AppException(ErrorCode.BORROW_LIMIT_EXCEEDED))
                .when(mediaBorrowLimitService)
                .assertCanBorrowMore(10L);

        assertThatThrownBy(() -> applier.validatePayableTarget(10L, 1001L))
                .isInstanceOf(AppException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.BORROW_LIMIT_EXCEEDED.getCode());
    }

    @Test
    void applySuccessShouldNotCreateLoanWhenTotalMediaBorrowLimitReachedAfterPayment() {
        PaymentTransaction payment = payment();
        BookEbook ebook = ebook();
        Member member = member();
        when(ebookLoanRepository.existsByPaymentId(9001L)).thenReturn(false);
        when(mediaBorrowLimitService.lockMember(10L)).thenReturn(member);
        when(bookEbookRepository.findLockedById(1001L)).thenReturn(Optional.of(ebook));
        org.mockito.Mockito.doThrow(new AppException(ErrorCode.BORROW_LIMIT_EXCEEDED))
                .when(mediaBorrowLimitService)
                .assertCanBorrowMore(member);

        applier.applySuccess(payment);

        verify(ebookLoanRepository, never()).save(any());
        assertThat(payment.getProviderMetadata())
                .containsEntry("ebookFulfillmentStatus", "BORROW_LIMIT_EXCEEDED");
    }

    private BookEbook ebook() {
        Book book = new Book();
        book.setId(501L);

        BookEbook ebook = new BookEbook();
        ebook.setId(1001L);
        ebook.setBook(book);
        ebook.setStatus(BookEbookStatus.ACTIVE);
        ebook.setAccessType(EbookAccessType.PAID);
        ebook.setAccessFee(BigDecimal.valueOf(25_000));
        ebook.setCurrency("VND");
        ebook.setMaxConcurrentLoans(2);
        ebook.setLoanDurationDays(14);
        return ebook;
    }

    private PaymentTransaction payment() {
        PaymentTransaction payment = new PaymentTransaction();
        payment.setId(9001L);
        payment.setPaymentCode("PAY202606130001");
        payment.setMemberId(10L);
        payment.setProvider(PaymentProvider.VNPAY);
        payment.setPurpose(PaymentPurpose.EBOOK_PAYMENT);
        payment.setTargetType(PaymentTargetType.BOOK_EBOOK);
        payment.setTargetId(1001L);
        payment.setAmount(25_000L);
        payment.setCurrency("VND");
        payment.setStatus(PaymentStatus.SUCCESS);
        payment.setExpiredAt(Instant.now().plusSeconds(900));
        payment.setProviderMetadata(new LinkedHashMap<>());
        return payment;
    }

    private Member member() {
        Member member = new Member();
        member.setId(10L);
        member.setMaxBorrowLimit(5);
        return member;
    }
}
