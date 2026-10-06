package com.vn.loan.service;

import com.vn.book.entity.Book;
import com.vn.book.entity.BookCopy;
import com.vn.loan.entity.BorrowRecord;
import com.vn.member.entity.Member;
import com.vn.book.enums.BookCopyStatus;
import com.vn.loan.enums.BorrowStatus;
import com.vn.loan.repository.BorrowRecordRepository;
import com.vn.loan.service.impl.policy.CirculationSettingService;
import com.vn.loan.service.impl.reminder.DueSoonReminderJobSummary;
import com.vn.loan.service.impl.reminder.DueSoonReminderProcessor;
import com.vn.loan.service.impl.reminder.DueSoonReminderResult;
import com.vn.loan.service.impl.reminder.DueSoonReminderService;
import com.vn.shared.testsupport.TestDataFactory;
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

    private DueSoonReminderService dueSoonReminderService;

    @BeforeEach
    void setUp() {
        dueSoonReminderService = new DueSoonReminderService(
                borrowRecordRepository,
                circulationSettingService,
                dueSoonReminderProcessor,
                com.vn.shared.testsupport.TestTime.CLOCK
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
                .thenReturn(DueSoonReminderResult.enqueued());
        when(dueSoonReminderProcessor.createReminderIfNeeded(eq(101L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(DueSoonReminderResult.skipped());

        DueSoonReminderJobSummary summary = dueSoonReminderService.sendDueSoonReminders();

        assertThat(summary.totalProcessed()).isEqualTo(2);
        assertThat(summary.successCount()).isEqualTo(1);
        assertThat(summary.failedCount()).isEqualTo(1);
        verify(borrowRecordRepository).findDueSoonReminderCandidates(
                eq(BorrowStatus.BORROWED),
                eq(Instant.parse("2026-06-16T17:00:00Z")),
                eq(Instant.parse("2026-06-17T17:00:00Z")),
                isA(Pageable.class));
        verify(dueSoonReminderProcessor).createReminderIfNeeded(
                eq(100L), org.mockito.ArgumentMatchers.any());
    }

    private BorrowRecord borrow(Long id) {
        Member member = TestDataFactory.activeMember(5L);
        Book book = TestDataFactory.book(10L, 0);
        BookCopy copy = TestDataFactory.bookCopy(50L + id, book, BookCopyStatus.BORROWED);
        return TestDataFactory.borrowRecord(id, member, copy, BorrowStatus.BORROWED);
    }
}
