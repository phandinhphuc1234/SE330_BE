package com.vn.loan.service;

import com.vn.book.entity.Book;
import com.vn.book.entity.BookCopy;
import com.vn.loan.entity.BorrowRecord;
import com.vn.member.entity.Member;
import com.vn.book.enums.BookCopyStatus;
import com.vn.loan.enums.BorrowStatus;
import com.vn.loan.repository.BorrowRecordRepository;
import com.vn.loan.service.impl.autorenewal.AutoRenewalJobSummary;
import com.vn.loan.service.impl.autorenewal.AutoRenewalProcessor;
import com.vn.loan.service.impl.autorenewal.AutoRenewalResult;
import com.vn.loan.service.impl.autorenewal.AutoRenewalService;
import com.vn.loan.service.impl.policy.CirculationSettingService;
import com.vn.shared.testsupport.TestDataFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AutoRenewalServiceTest {

    @Mock private BorrowRecordRepository borrowRecordRepository;
    @Mock private CirculationSettingService circulationSettingService;
    @Mock private AutoRenewalProcessor autoRenewalProcessor;

    private AutoRenewalService service;

    @BeforeEach
    void setUp() {
        service = new AutoRenewalService(
                borrowRecordRepository, circulationSettingService, autoRenewalProcessor,
                com.vn.shared.testsupport.TestTime.CLOCK);
    }

    @Test
    void runDailyAutoRenewal_shouldContinueAfterOneTransactionalRecordFails() {
        BorrowRecord first = borrow(100L);
        BorrowRecord second = borrow(101L);
        when(circulationSettingService.getAutoRenewDaysBeforeDue()).thenReturn(2);
        when(circulationSettingService.getAutoRenewMaxItemsPerRun()).thenReturn(500);
        when(borrowRecordRepository.findAutoRenewalCandidates(
                eq(BorrowStatus.BORROWED), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(first, second)));
        when(autoRenewalProcessor.processOne(100L, 900L))
                .thenThrow(new IllegalStateException("queue unavailable"));
        when(autoRenewalProcessor.processOne(101L, 900L))
                .thenReturn(AutoRenewalResult.succeeded());

        AutoRenewalJobSummary summary = service.runDailyAutoRenewal(900L);

        assertThat(summary.totalProcessed()).isEqualTo(2);
        assertThat(summary.successCount()).isEqualTo(1);
        assertThat(summary.failedCount()).isEqualTo(1);
        verify(autoRenewalProcessor).processOne(101L, 900L);
    }

    private BorrowRecord borrow(Long id) {
        Member member = TestDataFactory.activeMember(5L);
        Book book = TestDataFactory.book(10L, 0);
        BookCopy copy = TestDataFactory.bookCopy(50L + id, book, BookCopyStatus.BORROWED);
        return TestDataFactory.borrowRecord(id, member, copy, BorrowStatus.BORROWED);
    }
}
