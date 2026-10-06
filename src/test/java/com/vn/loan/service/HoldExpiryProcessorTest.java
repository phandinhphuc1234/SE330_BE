package com.vn.loan.service;

import com.vn.book.entity.Book;
import com.vn.book.entity.BookCopy;
import com.vn.member.entity.Member;
import com.vn.loan.entity.Reservation;
import com.vn.book.enums.BookCopyStatus;
import com.vn.loan.enums.ReservationStatus;
import com.vn.loan.repository.ReservationRepository;
import com.vn.notification.service.NotificationQueueService;
import com.vn.loan.service.impl.hold.HoldQueueService;
import com.vn.loan.service.impl.holdexpiry.HoldExpiryProcessor;
import com.vn.loan.service.impl.holdexpiry.HoldExpiryResult;
import com.vn.notification.service.EmailNotificationCommand;
import com.vn.shared.testsupport.TestDataFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HoldExpiryProcessorTest {

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private HoldQueueService holdQueueService;
    @Mock
    private NotificationQueueService notificationQueueService;

    private HoldExpiryProcessor holdExpiryProcessor;

    @BeforeEach
    void setUp() {
        holdExpiryProcessor = new HoldExpiryProcessor(
                reservationRepository, holdQueueService, notificationQueueService);
    }

    @Test
    void expireOne_shouldExpireReadyHoldAndReassignCopy() {
        BookCopy copy = copy(50L, book(10L), BookCopyStatus.ON_HOLD_SHELF);
        Reservation hold = reservation(700L, ReservationStatus.READY_FOR_PICKUP, copy);
        hold.setExpiresAt(Instant.parse("2026-05-18T10:00:00Z"));
        when(reservationRepository.findLockedForExpiryById(700L)).thenReturn(Optional.of(hold));
        when(reservationRepository.saveAndFlush(hold)).thenReturn(hold);

        HoldExpiryResult result = holdExpiryProcessor.expireOne(
                700L,
                Instant.parse("2026-05-19T10:00:00Z")
        );

        assertThat(result.success()).isTrue();
        assertThat(hold.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        verify(reservationRepository).saveAndFlush(hold);
        verify(holdQueueService).reassignOrReleaseHeldCopy(copy);
        ArgumentCaptor<EmailNotificationCommand> commandCaptor =
                ArgumentCaptor.forClass(EmailNotificationCommand.class);
        verify(notificationQueueService).enqueueEmail(commandCaptor.capture());
        assertThat(commandCaptor.getValue().eventKey())
                .isEqualTo("HOLD_EXPIRED:RESERVATION:700:EMAIL");
    }

    @Test
    void expireOne_shouldSkipWhenHoldIsNotExpiredYet() {
        BookCopy copy = copy(50L, book(10L), BookCopyStatus.ON_HOLD_SHELF);
        Reservation hold = reservation(700L, ReservationStatus.READY_FOR_PICKUP, copy);
        hold.setExpiresAt(Instant.parse("2026-05-20T10:00:00Z"));
        when(reservationRepository.findLockedForExpiryById(700L)).thenReturn(Optional.of(hold));

        HoldExpiryResult result = holdExpiryProcessor.expireOne(
                700L,
                Instant.parse("2026-05-19T10:00:00Z")
        );

        assertThat(result.success()).isFalse();
        assertThat(hold.getStatus()).isEqualTo(ReservationStatus.READY_FOR_PICKUP);
        verify(reservationRepository, never()).saveAndFlush(hold);
        verify(holdQueueService, never()).reassignOrReleaseHeldCopy(copy);
        verify(notificationQueueService, never()).enqueueEmail(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void expireOne_shouldSkipWhenHoldIsAlreadyFinalized() {
        BookCopy copy = copy(50L, book(10L), BookCopyStatus.ON_HOLD_SHELF);
        Reservation hold = reservation(700L, ReservationStatus.FULFILLED, copy);
        hold.setExpiresAt(Instant.parse("2026-05-18T10:00:00Z"));
        when(reservationRepository.findLockedForExpiryById(700L)).thenReturn(Optional.of(hold));

        HoldExpiryResult result = holdExpiryProcessor.expireOne(
                700L,
                Instant.parse("2026-05-19T10:00:00Z")
        );

        assertThat(result.success()).isFalse();
        verify(reservationRepository, never()).saveAndFlush(hold);
        verify(holdQueueService, never()).reassignOrReleaseHeldCopy(copy);
        verify(notificationQueueService, never()).enqueueEmail(org.mockito.ArgumentMatchers.any());
    }

    private Reservation reservation(Long id, ReservationStatus status, BookCopy assignedCopy) {
        Member member = TestDataFactory.activeMember(5L);
        return TestDataFactory.reservation(id, member, assignedCopy.getBook(), status, assignedCopy);
    }

    private Book book(Long id) {
        return TestDataFactory.book(id, 0);
    }

    private BookCopy copy(Long id, Book book, BookCopyStatus status) {
        return TestDataFactory.bookCopy(id, book, status);
    }
}
