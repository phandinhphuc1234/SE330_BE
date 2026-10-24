package com.vn.ebook.service;

import com.vn.book.entity.Book;
import com.vn.ebook.entity.BookEbook;
import com.vn.ebook.entity.EbookLoan;
import com.vn.ebook.dto.response.EbookLoanResponse;
import com.vn.ebook.enums.BookEbookStatus;
import com.vn.ebook.enums.EbookAccessType;
import com.vn.ebook.enums.EbookLoanStatus;
import com.vn.ebook.mapper.EbookMapper;
import com.vn.ebook.repository.BookEbookRepository;
import com.vn.ebook.repository.EbookLoanRepository;
import com.vn.ebook.service.impl.EbookLoanServiceImpl;
import com.vn.loan.service.borrow.MediaBorrowLimitService;
import com.vn.member.entity.Member;
import com.vn.shared.exception.AppException;
import com.vn.shared.exception.ErrorCode;
import com.vn.shared.testsupport.TestTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EbookLoanServiceImplTest {
    private final EbookLoanRepository loans = mock(EbookLoanRepository.class);
    private final BookEbookRepository ebooks = mock(BookEbookRepository.class);
    private final EbookMapper mapper = mock(EbookMapper.class);
    private final MediaBorrowLimitService limits = mock(MediaBorrowLimitService.class);
    private final EbookLoanService service = new EbookLoanServiceImpl(loans, ebooks, mapper, limits, TestTime.CLOCK);
    private BookEbook ebook;

    @BeforeEach
    void setUp() {
        Book book = new Book();
        book.setId(501L);
        ebook = new BookEbook();
        ebook.setId(1001L);
        ebook.setBook(book);
        ebook.setStatus(BookEbookStatus.ACTIVE);
        ebook.setAccessType(EbookAccessType.FREE);
        ebook.setLoanDurationDays(14);
        ebook.setMaxConcurrentLoans(2);
        Member member = new Member();
        member.setId(10L);
        when(ebooks.findFirstByBookIdAndStatusOrderByIdDesc(501L, BookEbookStatus.ACTIVE))
                .thenReturn(Optional.of(ebook));
        when(ebooks.findLockedById(1001L)).thenReturn(Optional.of(ebook));
        when(limits.lockMember(10L)).thenReturn(member);
    }

    @Test
    void reborrowExpiresElapsedRowBeforeCreatingNewLoanUnderLocks() {
        when(loans.expireElapsedActiveLoans(10L, 1001L, TestTime.NOW)).thenReturn(1);
        service.borrowFreeEbook(10L, 501L);

        var order = inOrder(limits, ebooks, loans);
        order.verify(limits).lockMember(10L);
        order.verify(ebooks).findLockedById(1001L);
        order.verify(loans).expireElapsedActiveLoans(10L, 1001L, TestTime.NOW);
        order.verify(loans).findFirstByMemberIdAndBookIdAndBookEbookIdAndStatusAndExpiredAtAfterOrderByExpiredAtDesc(
                10L, 501L, 1001L, EbookLoanStatus.ACTIVE, TestTime.NOW);
        order.verify(loans).countByBookEbookIdAndStatusAndExpiredAtAfter(1001L, EbookLoanStatus.ACTIVE, TestTime.NOW);
        ArgumentCaptor<EbookLoan> created = ArgumentCaptor.forClass(EbookLoan.class);
        order.verify(loans).save(created.capture());
        assertThat(created.getValue().getMemberId()).isEqualTo(10L);
        assertThat(created.getValue().getBookEbookId()).isEqualTo(1001L);
        assertThat(created.getValue().getStatus()).isEqualTo(EbookLoanStatus.ACTIVE);
        assertThat(created.getValue().getExpiredAt()).isEqualTo(TestTime.NOW.plus(14, ChronoUnit.DAYS));
    }

    @Test
    void validExistingLoanIsReturnedWithoutCreatingDuplicate() {
        EbookLoan existing = new EbookLoan();
        when(mapper.toEbookLoanResponse(existing)).thenReturn(mock(EbookLoanResponse.class));
        when(loans.findFirstByMemberIdAndBookIdAndBookEbookIdAndStatusAndExpiredAtAfterOrderByExpiredAtDesc(
                10L, 501L, 1001L, EbookLoanStatus.ACTIVE, TestTime.NOW)).thenReturn(Optional.of(existing));
        service.borrowFreeEbook(10L, 501L);
        verify(mapper).toEbookLoanResponse(existing);
        verify(loans, never()).save(any());
    }

    @Test
    void reborrowStillEnforcesConcurrentLicenseLimit() {
        when(loans.countByBookEbookIdAndStatusAndExpiredAtAfter(1001L, EbookLoanStatus.ACTIVE, TestTime.NOW))
                .thenReturn(2L);
        assertThatThrownBy(() -> service.borrowFreeEbook(10L, 501L))
                .isInstanceOf(AppException.class).extracting("code")
                .isEqualTo(ErrorCode.EBOOK_LICENSE_NOT_AVAILABLE.getCode());
        verify(loans, never()).save(any());
    }

    @Test
    void paidEbookIsNotGrantedByFreeFlow() {
        ebook.setAccessType(EbookAccessType.PAID);
        assertThatThrownBy(() -> service.borrowFreeEbook(10L, 501L))
                .isInstanceOf(AppException.class).extracting("code")
                .isEqualTo(ErrorCode.EBOOK_REQUIRES_PAYMENT.getCode());
        verify(loans, never()).expireElapsedActiveLoans(any(), any(), any());
        verify(loans, never()).save(any());
    }
}
