package com.vn.service.circulation;

import com.vn.dto.circulation.request.CreateHoldRequest;
import com.vn.dto.circulation.response.BorrowResponse;
import com.vn.dto.circulation.response.HoldResponse;
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
import com.vn.repository.BookRepository;
import com.vn.repository.BorrowRecordRepository;
import com.vn.repository.MemberRepository;
import com.vn.repository.ReservationRepository;
import com.vn.service.IdempotencyService;
import com.vn.service.impl.HoldServiceImpl;
import com.vn.service.impl.circulation.CirculationPolicyService;
import com.vn.service.impl.circulation.CirculationSettingService;
import com.vn.service.impl.circulation.FineStatusResolver;
import com.vn.service.impl.circulation.HoldQueueService;
import com.vn.testsupport.TestDataFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HoldServiceImplTest {

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private BookRepository bookRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private BorrowRecordRepository borrowRecordRepository;

    @Mock
    private CirculationPolicyService circulationPolicyService;

    @Mock
    private CirculationSettingService circulationSettingService;

    @Mock
    private HoldQueueService holdQueueService;

    @Mock
    private IdempotencyService idempotencyService;

    private HoldServiceImpl holdService;

    @BeforeEach
    void setUp() {
        holdService = new HoldServiceImpl(
                memberRepository,
                bookRepository,
                reservationRepository,
                borrowRecordRepository,
                circulationPolicyService,
                circulationSettingService,
                holdQueueService,
                new CirculationMapper(new FineStatusResolver()),
                idempotencyService
        );
    }

    @Test
    void createHold_shouldThrowBookAvailableOnShelf_whenBookStillHasAvailableCopies() {
        Member member = member(5L);
        Book book = book(10L, 2);
        when(memberRepository.findById(5L)).thenReturn(Optional.of(member));
        when(bookRepository.findLockedByIdAndDeletedAtIsNull(10L)).thenReturn(Optional.of(book));

        AppException exception = assertThrows(
                AppException.class,
                () -> holdService.createHold(5L, new CreateHoldRequest(10L))
        );

        assertThat(exception.getCode()).isEqualTo(ErrorCode.BOOK_AVAILABLE_ON_SHELF.getCode());
        verify(reservationRepository, never()).save(any());
    }

    @Test
    void createHold_shouldThrowHoldAlreadyExists_whenMemberAlreadyHasActiveHoldForBook() {
        Member member = member(5L);
        Book book = book(10L, 0);
        when(memberRepository.findById(5L)).thenReturn(Optional.of(member));
        when(bookRepository.findLockedByIdAndDeletedAtIsNull(10L)).thenReturn(Optional.of(book));
        when(reservationRepository.existsByMemberIdAndBookIdAndStatusIn(eq(5L), eq(10L), any()))
                .thenReturn(true);

        AppException exception = assertThrows(
                AppException.class,
                () -> holdService.createHold(5L, new CreateHoldRequest(10L))
        );

        assertThat(exception.getCode()).isEqualTo(ErrorCode.HOLD_ALREADY_EXISTS.getCode());
        verify(reservationRepository, never()).save(any());
    }

    @Test
    void createHold_shouldCreateWaitingHoldWithNextQueuePosition_whenBookUnavailable() {
        Member member = member(5L);
        Book book = book(10L, 0);
        when(memberRepository.findById(5L)).thenReturn(Optional.of(member));
        when(bookRepository.findLockedByIdAndDeletedAtIsNull(10L)).thenReturn(Optional.of(book));
        when(reservationRepository.existsByMemberIdAndBookIdAndStatusIn(eq(5L), eq(10L), any()))
                .thenReturn(false);
        when(reservationRepository.findMaxQueuePositionByBookId(10L)).thenReturn(2);
        when(circulationSettingService.getHoldPickupDaysDefault()).thenReturn(3);
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(invocation -> {
            Reservation reservation = invocation.getArgument(0);
            reservation.setId(700L);
            return reservation;
        });

        HoldResponse response = holdService.createHold(5L, new CreateHoldRequest(10L));

        assertThat(response.holdId()).isEqualTo(700L);
        assertThat(response.status()).isEqualTo(ReservationStatus.WAITING.name());
        assertThat(response.queuePosition()).isEqualTo(3);

        ArgumentCaptor<Reservation> reservationCaptor = ArgumentCaptor.forClass(Reservation.class);
        verify(reservationRepository).save(reservationCaptor.capture());
        assertThat(reservationCaptor.getValue().getMember()).isEqualTo(member);
        assertThat(reservationCaptor.getValue().getBook()).isEqualTo(book);
    }

    @Test
    void cancelHold_shouldThrowAccessDenied_whenMemberCancelsAnotherMemberHold() {
        Reservation hold = reservation(700L, member(5L), book(10L, 0), ReservationStatus.WAITING, null);
        when(reservationRepository.findById(700L)).thenReturn(Optional.of(hold));

        AppException exception = assertThrows(
                AppException.class,
                () -> holdService.cancelHold(99L, false, 700L)
        );

        assertThat(exception.getCode()).isEqualTo(ErrorCode.ACCESS_DENIED.getCode());
        verify(reservationRepository, never()).save(any());
    }

    @Test
    void cancelHold_shouldThrowHoldNotActive_whenHoldAlreadyFinalized() {
        Reservation hold = reservation(700L, member(5L), book(10L, 0), ReservationStatus.FULFILLED, null);
        when(reservationRepository.findById(700L)).thenReturn(Optional.of(hold));

        AppException exception = assertThrows(
                AppException.class,
                () -> holdService.cancelHold(5L, false, 700L)
        );

        assertThat(exception.getCode()).isEqualTo(ErrorCode.HOLD_NOT_ACTIVE.getCode());
        verify(reservationRepository, never()).save(any());
    }

    @Test
    void cancelHold_shouldReassignAssignedCopy_whenReadyHoldIsCancelled() {
        BookCopy copy = copy(50L, book(10L, 0), BookCopyStatus.ON_HOLD_SHELF);
        Reservation hold = reservation(700L, member(5L), copy.getBook(), ReservationStatus.READY_FOR_PICKUP, copy);
        when(reservationRepository.findById(700L)).thenReturn(Optional.of(hold));
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(invocation -> invocation.getArgument(0));

        HoldResponse response = holdService.cancelHold(99L, true, 700L);

        assertThat(response.status()).isEqualTo(ReservationStatus.CANCELLED.name());
        verify(holdQueueService).reassignOrReleaseHeldCopy(copy);
    }

    @Test
    void checkoutHold_shouldThrowHoldNotReady_whenHoldIsStillWaiting() {
        Reservation hold = reservation(700L, member(5L), book(10L, 0), ReservationStatus.WAITING, null);
        when(reservationRepository.findById(700L)).thenReturn(Optional.of(hold));
        executeIdempotentAction();

        AppException exception = assertThrows(
                AppException.class,
                () -> holdService.checkoutHold(99L, "hold-key", 700L)
        );

        assertThat(exception.getCode()).isEqualTo(ErrorCode.HOLD_NOT_READY_FOR_PICKUP.getCode());
        verify(borrowRecordRepository, never()).save(any());
    }

    @Test
    void checkoutHold_shouldThrowAssignedCopyInvalid_whenCopyIsMissingOrNotOnHoldShelf() {
        BookCopy copy = copy(50L, book(10L, 0), BookCopyStatus.AVAILABLE);
        Reservation hold = reservation(700L, member(5L), copy.getBook(), ReservationStatus.READY_FOR_PICKUP, copy);
        when(reservationRepository.findById(700L)).thenReturn(Optional.of(hold));
        executeIdempotentAction();

        AppException exception = assertThrows(
                AppException.class,
                () -> holdService.checkoutHold(99L, "hold-key", 700L)
        );

        assertThat(exception.getCode()).isEqualTo(ErrorCode.HOLD_ASSIGNED_COPY_INVALID.getCode());
        verify(borrowRecordRepository, never()).save(any());
    }

    @Test
    void checkoutHold_shouldCreateBorrowAndFulfillHold_whenHoldReadyForPickup() {
        BookCopy copy = copy(50L, book(10L, 0), BookCopyStatus.ON_HOLD_SHELF);
        Reservation hold = reservation(700L, member(5L), copy.getBook(), ReservationStatus.READY_FOR_PICKUP, copy);
        when(reservationRepository.findById(700L)).thenReturn(Optional.of(hold));
        when(circulationSettingService.getBorrowDaysDefault()).thenReturn(14);
        when(circulationSettingService.getMaxRenewalsDefault()).thenReturn(1);
        when(borrowRecordRepository.save(any(BorrowRecord.class))).thenAnswer(invocation -> {
            BorrowRecord borrow = invocation.getArgument(0);
            borrow.setId(100L);
            return borrow;
        });
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(invocation -> invocation.getArgument(0));
        executeIdempotentAction();

        BorrowResponse response = holdService.checkoutHold(99L, "hold-key", 700L);

        assertThat(response.borrowId()).isEqualTo(100L);
        assertThat(copy.getStatus()).isEqualTo(BookCopyStatus.BORROWED);
        assertThat(hold.getStatus()).isEqualTo(ReservationStatus.FULFILLED);

        ArgumentCaptor<BorrowRecord> borrowCaptor = ArgumentCaptor.forClass(BorrowRecord.class);
        verify(borrowRecordRepository).save(borrowCaptor.capture());
        assertThat(borrowCaptor.getValue().getStatus()).isEqualTo(BorrowStatus.BORROWED);
        assertThat(borrowCaptor.getValue().getFineAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    private void executeIdempotentAction() {
        when(idempotencyService.execute(
                eq(99L),
                eq("POST"),
                eq("/api/staff/holds/{holdId}/checkout"),
                eq("hold-key"),
                eq(700L),
                eq(BorrowResponse.class),
                any()
        )).thenAnswer(invocation -> {
            Supplier<BorrowResponse> action = invocation.getArgument(6);
            return action.get();
        });
    }

    private Member member(Long id) {
        return TestDataFactory.activeMember(id);
    }

    private Book book(Long id, int availableCopies) {
        return TestDataFactory.book(id, availableCopies);
    }

    private BookCopy copy(Long id, Book book, BookCopyStatus status) {
        return TestDataFactory.bookCopy(id, book, status);
    }

    private Reservation reservation(Long id, Member member, Book book, ReservationStatus status, BookCopy assignedCopy) {
        return TestDataFactory.reservation(id, member, book, status, assignedCopy);
    }
}
