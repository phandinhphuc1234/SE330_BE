package com.vn.service.circulation;

import com.vn.dto.circulation.request.CheckinRequest;
import com.vn.dto.circulation.response.CheckinResponse;
import com.vn.entity.Book;
import com.vn.entity.BookCopy;
import com.vn.entity.BorrowRecord;
import com.vn.entity.Member;
import com.vn.entity.Reservation;
import com.vn.enums.BookCopyStatus;
import com.vn.enums.BorrowStatus;
import com.vn.enums.MemberRole;
import com.vn.enums.MemberStatus;
import com.vn.enums.ReservationStatus;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.mapper.CirculationMapper;
import com.vn.repository.BookCopyRepository;
import com.vn.repository.BookRepository;
import com.vn.repository.BorrowRecordRepository;
import com.vn.service.impl.circulation.usecase.CheckinUseCase;
import com.vn.service.impl.circulation.support.CirculationFineService;
import com.vn.service.impl.circulation.support.CirculationLookupService;
import com.vn.service.impl.circulation.support.FineStatusResolver;
import com.vn.service.impl.circulation.hold.HoldQueueService;
import com.vn.testsupport.TestDataFactory;
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
                holdQueueService
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
