package com.vn.service.circulation;

import com.vn.entity.Book;
import com.vn.entity.BookCopy;
import com.vn.entity.BorrowRecord;
import com.vn.entity.Member;
import com.vn.enums.BookCopyStatus;
import com.vn.enums.BorrowStatus;
import com.vn.enums.NotificationTargetType;
import com.vn.enums.NotificationType;
import com.vn.repository.BorrowRecordRepository;
import com.vn.service.NotificationQueueService;
import com.vn.service.impl.circulation.reminder.DueSoonReminderProcessor;
import com.vn.service.impl.circulation.reminder.DueSoonReminderResult;
import com.vn.service.impl.circulation.reminder.DueSoonReminderWindow;
import com.vn.service.notification.EmailNotificationCommand;
import com.vn.service.notification.NotificationEnqueueResult;
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
    private NotificationQueueService notificationQueueService;

    private DueSoonReminderProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new DueSoonReminderProcessor(
                borrowRecordRepository,
                notificationQueueService
        );
    }

    @Test
    void createReminderIfNeeded_shouldCreateNotificationAndQueueWhenBorrowIsStillDueSoon() {
        BorrowRecord borrow = dueSoonBorrow();
        when(borrowRecordRepository.findById(100L)).thenReturn(Optional.of(borrow));
        when(notificationQueueService.enqueueEmail(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new NotificationEnqueueResult(99L, 199L, true));

        DueSoonReminderResult result = processor.createReminderIfNeeded(100L, reminderWindow());

        assertThat(result.created()).isTrue();

        ArgumentCaptor<EmailNotificationCommand> commandCaptor =
                ArgumentCaptor.forClass(EmailNotificationCommand.class);
        verify(notificationQueueService).enqueueEmail(commandCaptor.capture());
        EmailNotificationCommand command = commandCaptor.getValue();
        assertThat(command.notificationType()).isEqualTo(NotificationType.DUE_SOON_REMINDER);
        assertThat(command.targetType()).isEqualTo(NotificationTargetType.BORROW_RECORD);
        assertThat(command.targetId()).isEqualTo(100L);
        assertThat(command.eventKey()).isEqualTo("DUE_SOON_REMINDER:BORROW_RECORD:100:EMAIL");
        assertThat(command.templateCode()).isEqualTo("due-soon-reminder");
        assertThat(command.payload()).containsEntry("bookTitle", "Clean Code");
    }

    @Test
    void createReminderIfNeeded_shouldSkipWhenReminderAlreadyExists() {
        BorrowRecord borrow = dueSoonBorrow();
        when(borrowRecordRepository.findById(100L)).thenReturn(Optional.of(borrow));
        when(notificationQueueService.enqueueEmail(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new NotificationEnqueueResult(99L, 199L, false));

        DueSoonReminderResult result = processor.createReminderIfNeeded(100L, reminderWindow());

        assertThat(result.created()).isFalse();
        verify(notificationQueueService).enqueueEmail(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void createReminderIfNeeded_shouldSkipWhenBorrowIsNoLongerBorrowed() {
        BorrowRecord borrow = dueSoonBorrow();
        borrow.setStatus(BorrowStatus.RETURNED);
        when(borrowRecordRepository.findById(100L)).thenReturn(Optional.of(borrow));

        DueSoonReminderResult result = processor.createReminderIfNeeded(100L, reminderWindow());

        assertThat(result.created()).isFalse();
        verify(notificationQueueService, never()).enqueueEmail(org.mockito.ArgumentMatchers.any());
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
