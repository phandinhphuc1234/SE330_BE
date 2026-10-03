package com.vn.loan.service;

import com.vn.loan.dto.request.CheckinRequest;
import com.vn.loan.dto.response.CheckinResponse;
import com.vn.book.entity.Book;
import com.vn.book.entity.BookCopy;
import com.vn.loan.entity.BorrowRecord;
import com.vn.member.entity.Member;
import com.vn.loan.entity.Reservation;
import com.vn.book.enums.BookCopyStatus;
import com.vn.loan.enums.BorrowStatus;
import com.vn.member.enums.MemberRole;
import com.vn.member.enums.MemberStatus;
import com.vn.loan.enums.ReservationStatus;
import com.vn.shared.exception.AppException;
import com.vn.shared.exception.ErrorCode;
import com.vn.loan.mapper.CirculationMapper;
import com.vn.book.repository.BookCopyRepository;
import com.vn.book.repository.BookRepository;
import com.vn.loan.repository.BorrowRecordRepository;
import com.vn.loan.service.impl.usecase.CheckinUseCase;
import com.vn.loan.service.impl.support.CirculationFineService;
import com.vn.loan.service.impl.support.CirculationLookupService;
import com.vn.loan.service.impl.support.FineStatusResolver;
import com.vn.loan.service.impl.hold.HoldQueueService;
import com.vn.shared.testsupport.TestDataFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CheckinUseCaseTest {

    @Mock
    private CirculationLookupService circulationLookupService;

    @Mock
    private CirculationFineService circulationFineService;

    @Mock
    private BorrowRecordRepository borrowRecordRepository;

    @Mock
    private BookCopyRepository bookCopyRepository;

    @Mock
    private BookRepository bookRepository;

    @Mock
    private HoldQueueService holdQueueService;

    private CheckinUseCase checkinUseCase;

    @BeforeEach
    void setUp() {
        checkinUseCase = new CheckinUseCase(
                circulationLookupService,
                circulationFineService,
                borrowRecordRepository,
                bookCopyRepository,
                bookRepository,
                new CirculationMapper(new FineStatusResolver()),
                holdQueueService,
                com.vn.shared.testsupport.TestTime.CLOCK
        );
    }

    @Test
    void checkin_shouldThrowActiveBorrowNotFound_whenCopyHasNoOpenBorrow() {
        BookCopy copy = copy(BookCopyStatus.BORROWED);
        when(circulationLookupService.getCopyByBarcode("BC-50")).thenReturn(copy);
        when(borrowRecordRepository.findFirstByBookCopyIdAndStatusInOrderByBorrowedAtDesc(eq(50L), any()))
                .thenReturn(Optional.empty());

        AppException exception = assertThrows(
                AppException.class,
                () -> checkinUseCase.checkin(new CheckinRequest("BC-50", "GOOD", null))
        );

        assertThat(exception.getCode()).isEqualTo(ErrorCode.ACTIVE_BORROW_NOT_FOUND.getCode());
        verify(bookCopyRepository, never()).save(any());
    }

    @Test
    void checkin_shouldPutCopyOnHoldShelfAndNotIncreaseAvailableCopies_whenWaitingHoldExists() {
        BookCopy copy = copy(BookCopyStatus.BORROWED);
        BorrowRecord borrow = borrow(copy);
        Reservation nextHold = reservation(copy);
        when(circulationLookupService.getCopyByBarcode("BC-50")).thenReturn(copy);
        when(borrowRecordRepository.findFirstByBookCopyIdAndStatusInOrderByBorrowedAtDesc(eq(50L), any()))
                .thenReturn(Optional.of(borrow));
        when(circulationFineService.calculateOverdueDays(eq(borrow.getDueDate()), any(Instant.class))).thenReturn(0L);
        when(holdQueueService.assignReturnedCopyToNextHold(copy)).thenAnswer(invocation -> {
            copy.setStatus(BookCopyStatus.ON_HOLD_SHELF);
            return Optional.of(nextHold);
        });
        when(borrowRecordRepository.save(any(BorrowRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CheckinResponse response = checkinUseCase.checkin(new CheckinRequest("BC-50", "GOOD", null));

        assertThat(response.borrowStatus()).isEqualTo(BorrowStatus.RETURNED.name());
        assertThat(response.bookCopyStatus()).isEqualTo(BookCopyStatus.ON_HOLD_SHELF.name());
        assertThat(response.nextHoldId()).isEqualTo(700L);
        assertThat(borrow.getReturnedAt()).isNotNull();
        verify(bookRepository, never()).adjustCopyCounters(any(), any(Integer.class), any(Integer.class));
    }

    @Test
    void checkin_shouldReturnCopyToAvailableAndIncreaseCounter_whenNoWaitingHoldExists() {
        BookCopy copy = copy(BookCopyStatus.BORROWED);
        BorrowRecord borrow = borrow(copy);
        when(circulationLookupService.getCopyByBarcode("BC-50")).thenReturn(copy);
        when(borrowRecordRepository.findFirstByBookCopyIdAndStatusInOrderByBorrowedAtDesc(eq(50L), any()))
                .thenReturn(Optional.of(borrow));
        when(circulationFineService.calculateOverdueDays(eq(borrow.getDueDate()), any(Instant.class))).thenReturn(0L);
        when(holdQueueService.assignReturnedCopyToNextHold(copy)).thenReturn(Optional.empty());
        when(borrowRecordRepository.save(any(BorrowRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CheckinResponse response = checkinUseCase.checkin(new CheckinRequest("BC-50", "GOOD", null));

        assertThat(response.bookCopyStatus()).isEqualTo(BookCopyStatus.AVAILABLE.name());
        assertThat(response.nextHoldId()).isNull();
        verify(bookRepository).adjustCopyCounters(10L, 0, 1);
    }

    @Test
    void checkin_shouldMarkCopyDamagedAndSkipHoldQueue_whenReturnConditionDamaged() {
        BookCopy copy = copy(BookCopyStatus.BORROWED);
        BorrowRecord borrow = borrow(copy);
        when(circulationLookupService.getCopyByBarcode("BC-50")).thenReturn(copy);
        when(borrowRecordRepository.findFirstByBookCopyIdAndStatusInOrderByBorrowedAtDesc(eq(50L), any()))
                .thenReturn(Optional.of(borrow));
        when(circulationFineService.calculateOverdueDays(eq(borrow.getDueDate()), any(Instant.class))).thenReturn(0L);
        when(borrowRecordRepository.save(any(BorrowRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CheckinResponse response = checkinUseCase.checkin(new CheckinRequest("BC-50", "DAMAGED", null));

        assertThat(response.bookCopyStatus()).isEqualTo(BookCopyStatus.DAMAGED.name());
        verify(holdQueueService, never()).assignReturnedCopyToNextHold(any());
        verify(bookRepository, never()).adjustCopyCounters(any(), any(Integer.class), any(Integer.class));
    }

    @Test
    void checkin_shouldApplyOverdueFine_whenBorrowIsOverdue() {
        BookCopy copy = copy(BookCopyStatus.BORROWED);
        BorrowRecord borrow = borrow(copy);
        when(circulationLookupService.getCopyByBarcode("BC-50")).thenReturn(copy);
        when(borrowRecordRepository.findFirstByBookCopyIdAndStatusInOrderByBorrowedAtDesc(eq(50L), any()))
                .thenReturn(Optional.of(borrow));
        when(circulationFineService.calculateOverdueDays(eq(borrow.getDueDate()), any(Instant.class))).thenReturn(3L);
        when(holdQueueService.assignReturnedCopyToNextHold(copy)).thenReturn(Optional.empty());
        when(borrowRecordRepository.save(any(BorrowRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CheckinResponse response = checkinUseCase.checkin(new CheckinRequest("BC-50", "GOOD", null));

        assertThat(response.overdueDays()).isEqualTo(3L);
        verify(circulationFineService).applyOverdueFine(eq(borrow), eq(3L), any(Instant.class));
    }

    private BookCopy copy(BookCopyStatus status) {
        return TestDataFactory.bookCopy(50L, TestDataFactory.book(10L, 0), status);
    }

    private BorrowRecord borrow(BookCopy copy) {
        return TestDataFactory.borrowRecord(100L, TestDataFactory.activeMember(5L), copy, BorrowStatus.BORROWED);
    }

    private Reservation reservation(BookCopy copy) {
        return TestDataFactory.reservation(
                700L,
                TestDataFactory.activeMember(6L),
                copy.getBook(),
                ReservationStatus.READY_FOR_PICKUP,
                copy
        );
    }
}
