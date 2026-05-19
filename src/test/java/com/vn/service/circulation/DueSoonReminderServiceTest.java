package com.vn.service.circulation;

import com.vn.entity.Book;
import com.vn.entity.BookCopy;
import com.vn.entity.BorrowRecord;
import com.vn.entity.Member;
import com.vn.enums.BookCopyStatus;
import com.vn.enums.BorrowStatus;
import com.vn.repository.BorrowRecordRepository;
import com.vn.service.EmailService;
import com.vn.service.impl.circulation.policy.CirculationSettingService;
import com.vn.service.impl.circulation.reminder.DueSoonReminderJobSummary;
import com.vn.service.impl.circulation.reminder.DueSoonReminderProcessor;
import com.vn.service.impl.circulation.reminder.DueSoonReminderResult;
import com.vn.service.impl.circulation.reminder.DueSoonReminderService;
import com.vn.testsupport.TestDataFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DueSoonReminderServiceTest {

    @Mock
    private BorrowRecordRepository borrowRecordRepository;

    @Mock
    private CirculationSettingService circulationSettingService;

    @Mock
    private DueSoonReminderProcessor dueSoonReminderProcessor;

    @Mock
    private EmailService emailService;

    private DueSoonReminderService dueSoonReminderService;

    @BeforeEach
    void setUp() {
        dueSoonReminderService = new DueSoonReminderService(
                borrowRecordRepository,
                circulationSettingService,
                dueSoonReminderProcessor,
                emailService
        );
    }

    @Test
    void sendDueSoonReminders_shouldSendEmailOnlyForCreatedReminder() {
        BorrowRecord first = borrow(100L);
        BorrowRecord second = borrow(101L);
        when(circulationSettingService.getDueSoonReminderDaysBeforeDue()).thenReturn(2);
        when(circulationSettingService.getDueSoonReminderMaxItemsPerRun()).thenReturn(500);
        when(borrowRecordRepository.findDueSoonReminderCandidates(
                eq(BorrowStatus.BORROWED),
                isA(Instant.class),
                isA(Instant.class),
                isA(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of(first, second)));
        when(dueSoonReminderProcessor.createReminderIfNeeded(eq(100L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(DueSoonReminderResult.created(
                        5L,
                        "member5@example.com",
                        "Member 5",
                        "Clean Code",
                        "BC-150",
                        first.getDueDate()
                ));
        when(dueSoonReminderProcessor.createReminderIfNeeded(eq(101L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(DueSoonReminderResult.skipped());

        DueSoonReminderJobSummary summary = dueSoonReminderService.sendDueSoonReminders();

        assertThat(summary.totalProcessed()).isEqualTo(2);
        assertThat(summary.successCount()).isEqualTo(1);
        assertThat(summary.failedCount()).isEqualTo(1);
        verify(emailService).sendDueSoonReminderEmail(
                5L,
                "member5@example.com",
                "Member 5",
                "Clean Code",
                "BC-150",
                first.getDueDate()
        );
    }

    private BorrowRecord borrow(Long id) {
        Member member = TestDataFactory.activeMember(5L);
        Book book = TestDataFactory.book(10L, 0);
        BookCopy copy = TestDataFactory.bookCopy(50L + id, book, BookCopyStatus.BORROWED);
        return TestDataFactory.borrowRecord(id, member, copy, BorrowStatus.BORROWED);
    }
}
