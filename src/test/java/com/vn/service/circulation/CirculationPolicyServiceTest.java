package com.vn.service.circulation;

import com.vn.entity.Book;
import com.vn.entity.BookCopy;
import com.vn.entity.BorrowRecord;
import com.vn.entity.Member;
import com.vn.enums.AutoRenewalResultCode;
import com.vn.enums.BookCopyStatus;
import com.vn.enums.BorrowStatus;
import com.vn.enums.ReservationStatus;
import com.vn.exception.ErrorCode;
import com.vn.repository.BorrowRecordRepository;
import com.vn.repository.ReservationRepository;
import com.vn.service.impl.circulation.policy.CirculationPolicyService;
import com.vn.service.impl.circulation.policy.CirculationSettingService;
import com.vn.testsupport.TestDataFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CirculationPolicyServiceTest {

    @Mock
    private BorrowRecordRepository borrowRecordRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private CirculationSettingService circulationSettingService;

    private CirculationPolicyService policyService;

    @BeforeEach
    void setUp() {
        policyService = new CirculationPolicyService(
                borrowRecordRepository,
                reservationRepository,
                circulationSettingService
        );
    }

    @Test
    void validateAutoRenewal_shouldReturnSuccess_whenAllRulesPass() {
        BorrowRecord borrow = borrow();
        when(reservationRepository.existsByBookIdAndStatusIn(10L, ReservationStatus.activeStatuses())).thenReturn(false);

        AutoRenewalResultCode result = policyService.validateAutoRenewal(borrow);

        assertThat(result).isEqualTo(AutoRenewalResultCode.SUCCESS);
    }

    @Test
    void validateAutoRenewal_shouldBlock_whenMaxRenewalsReached() {
        BorrowRecord borrow = borrow();
        borrow.setRenewCount(2);
        borrow.setMaxRenewalsAtCheckout(2);

        AutoRenewalResultCode result = policyService.validateAutoRenewal(borrow);

        assertThat(result).isEqualTo(AutoRenewalResultCode.MAX_RENEWALS_REACHED);
    }

    @Test
    void validateAutoRenewal_shouldBlock_whenBookHasActiveHold() {
        BorrowRecord borrow = borrow();
        when(reservationRepository.existsByBookIdAndStatusIn(10L, ReservationStatus.activeStatuses())).thenReturn(true);

        AutoRenewalResultCode result = policyService.validateAutoRenewal(borrow);

        assertThat(result).isEqualTo(AutoRenewalResultCode.BLOCKED_BY_HOLD);
    }

    @Test
    void validateAutoRenewal_shouldBlock_whenCopyIsNotBorrowed() {
        BorrowRecord borrow = borrow();
        borrow.getBookCopy().setStatus(BookCopyStatus.AVAILABLE);

        AutoRenewalResultCode result = policyService.validateAutoRenewal(borrow);

        assertThat(result).isEqualTo(AutoRenewalResultCode.BOOK_COPY_NOT_BORROWED);
    }

    @Test
    void validateAutoRenewal_shouldBlock_whenBookIsDeleted() {
        BorrowRecord borrow = borrow();
        borrow.getBookCopy().getBook().setDeletedAt(Instant.now());

        AutoRenewalResultCode result = policyService.validateAutoRenewal(borrow);

        assertThat(result).isEqualTo(AutoRenewalResultCode.BOOK_DELETED);
    }

    @Test
    void validateCheckout_shouldBlock_whenMemberAlreadyBorrowedSameBook() {
        Member member = TestDataFactory.activeMember(5L);
        Book book = TestDataFactory.book(10L, 1);
        BookCopy copy = TestDataFactory.bookCopy(51L, book, BookCopyStatus.AVAILABLE);
        when(borrowRecordRepository.existsOpenBorrowForMemberAndBook(5L, 10L, BorrowStatus.openStatuses()))
                .thenReturn(true);

        var blocks = policyService.validateCheckout(member, copy);

        assertThat(blocks)
                .extracting("code")
                .contains(ErrorCode.MEMBER_ALREADY_BORROWED_BOOK.getCode());
        verify(borrowRecordRepository).existsOpenBorrowForMemberAndBook(5L, 10L, BorrowStatus.openStatuses());
    }

    private BorrowRecord borrow() {
        Member member = TestDataFactory.activeMember(5L);
        Book book = TestDataFactory.book(10L, 0);
        BookCopy copy = TestDataFactory.bookCopy(50L, book, BookCopyStatus.BORROWED);
        BorrowRecord borrow = TestDataFactory.borrowRecord(100L, member, copy, BorrowStatus.BORROWED);
        borrow.setDueDate(Instant.now().plusSeconds(86_400));
        borrow.setRenewCount(0);
        borrow.setMaxRenewalsAtCheckout(2);
        return borrow;
    }
}
