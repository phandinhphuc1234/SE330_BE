package com.vn.service.circulation;

import com.vn.entity.Book;
import com.vn.entity.BookCopy;
import com.vn.entity.BorrowRecord;
import com.vn.entity.Member;
import com.vn.entity.Notification;
import com.vn.entity.NotificationQueue;
import com.vn.enums.BookCopyStatus;
import com.vn.enums.BorrowStatus;
import com.vn.enums.NotificationChannel;
import com.vn.enums.NotificationTargetType;
import com.vn.enums.NotificationType;
import com.vn.repository.BorrowRecordRepository;
import com.vn.repository.NotificationQueueRepository;
import com.vn.repository.NotificationRepository;
import com.vn.service.impl.circulation.reminder.DueSoonReminderProcessor;
import com.vn.service.impl.circulation.reminder.DueSoonReminderResult;
import com.vn.service.impl.circulation.reminder.DueSoonReminderWindow;
import com.vn.testsupport.TestDataFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DueSoonReminderProcessorTest {

    @Mock
    private BorrowRecordRepository borrowRecordRepository;

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private NotificationQueueRepository notificationQueueRepository;

    private DueSoonReminderProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new DueSoonReminderProcessor(
                borrowRecordRepository,
                notificationRepository,
                notificationQueueRepository
        );
    }

    @Test
    void createReminderIfNeeded_shouldCreateNotificationAndQueueWhenBorrowIsStillDueSoon() {
        BorrowRecord borrow = dueSoonBorrow();
        Notification savedNotification = Notification.builder().id(99L).member(borrow.getMember()).build();
        when(borrowRecordRepository.findById(100L)).thenReturn(Optional.of(borrow));
        when(notificationQueueRepository.existsByNotificationTypeAndTargetTypeAndTargetIdAndChannel(
                NotificationType.DUE_SOON_REMINDER,
                NotificationTargetType.BORROW_RECORD,
                100L,
                NotificationChannel.EMAIL
        )).thenReturn(false);
        when(notificationRepository.save(org.mockito.ArgumentMatchers.any(Notification.class)))
                .thenReturn(savedNotification);

        DueSoonReminderResult result = processor.createReminderIfNeeded(100L, reminderWindow());

        assertThat(result.created()).isTrue();
        assertThat(result.memberId()).isEqualTo(5L);
        assertThat(result.bookTitle()).isEqualTo("Clean Code");
        assertThat(result.barcode()).isEqualTo("BC-50");

        ArgumentCaptor<NotificationQueue> queueCaptor = ArgumentCaptor.forClass(NotificationQueue.class);
        verify(notificationQueueRepository).save(queueCaptor.capture());
        NotificationQueue queue = queueCaptor.getValue();
        assertThat(queue.getNotification()).isSameAs(savedNotification);
        assertThat(queue.getNotificationType()).isEqualTo(NotificationType.DUE_SOON_REMINDER);
        assertThat(queue.getTargetType()).isEqualTo(NotificationTargetType.BORROW_RECORD);
        assertThat(queue.getTargetId()).isEqualTo(100L);
        assertThat(queue.getChannel()).isEqualTo(NotificationChannel.EMAIL);
    }

    @Test
    void createReminderIfNeeded_shouldSkipWhenReminderAlreadyExists() {
        BorrowRecord borrow = dueSoonBorrow();
        when(borrowRecordRepository.findById(100L)).thenReturn(Optional.of(borrow));
        when(notificationQueueRepository.existsByNotificationTypeAndTargetTypeAndTargetIdAndChannel(
                NotificationType.DUE_SOON_REMINDER,
                NotificationTargetType.BORROW_RECORD,
                100L,
                NotificationChannel.EMAIL
        )).thenReturn(true);

        DueSoonReminderResult result = processor.createReminderIfNeeded(100L, reminderWindow());

        assertThat(result.created()).isFalse();
        verify(notificationRepository, never()).save(org.mockito.ArgumentMatchers.any(Notification.class));
        verify(notificationQueueRepository, never()).save(org.mockito.ArgumentMatchers.any(NotificationQueue.class));
    }

    @Test
    void createReminderIfNeeded_shouldSkipWhenBorrowIsNoLongerBorrowed() {
        BorrowRecord borrow = dueSoonBorrow();
        borrow.setStatus(BorrowStatus.RETURNED);
        when(borrowRecordRepository.findById(100L)).thenReturn(Optional.of(borrow));

        DueSoonReminderResult result = processor.createReminderIfNeeded(100L, reminderWindow());

        assertThat(result.created()).isFalse();
        verify(notificationQueueRepository, never()).existsByNotificationTypeAndTargetTypeAndTargetIdAndChannel(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    private BorrowRecord dueSoonBorrow() {
        Member member = TestDataFactory.activeMember(5L);
        Book book = TestDataFactory.book(10L, 0);
        BookCopy copy = TestDataFactory.bookCopy(50L, book, BookCopyStatus.BORROWED);
        BorrowRecord borrow = TestDataFactory.borrowRecord(100L, member, copy, BorrowStatus.BORROWED);
        borrow.setDueDate(Instant.parse("2026-05-20T03:00:00Z"));
        return borrow;
    }

    private DueSoonReminderWindow reminderWindow() {
        return new DueSoonReminderWindow(
                Instant.parse("2026-05-20T00:00:00Z"),
                Instant.parse("2026-05-21T00:00:00Z")
        );
    }
}
