package com.vn.loan.service;

import com.vn.book.entity.Book;
import com.vn.book.entity.BookCopy;
import com.vn.loan.entity.BorrowRecord;
import com.vn.member.entity.Member;
import com.vn.book.enums.BookCopyStatus;
import com.vn.loan.enums.BorrowStatus;
import com.vn.loan.repository.BorrowRecordRepository;
import com.vn.loan.service.impl.overdue.OverdueJobSummary;
import com.vn.loan.service.impl.overdue.OverdueMarkProcessor;
import com.vn.loan.service.impl.overdue.OverdueMarkResult;
import com.vn.loan.service.impl.overdue.OverdueMarkService;
import com.vn.shared.testsupport.TestDataFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OverdueMarkServiceTest {

    @Mock
    private BorrowRecordRepository borrowRecordRepository;

    @Mock
    private OverdueMarkProcessor overdueMarkProcessor;

    private OverdueMarkService overdueMarkService;

    @BeforeEach
    void setUp() {
        overdueMarkService = new OverdueMarkService(
                borrowRecordRepository, overdueMarkProcessor, com.vn.shared.testsupport.TestTime.CLOCK);
    }

    @Test
    void markOverdueBorrows_shouldReturnSummaryFromProcessorResults() {
        BorrowRecord first = borrow(100L);
        BorrowRecord second = borrow(101L);
        when(borrowRecordRepository.findOverdueCandidates(
                eq(BorrowStatus.BORROWED),
                isA(java.time.Instant.class),
                isA(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of(first, second)));
        when(overdueMarkProcessor.markOne(100L)).thenReturn(OverdueMarkResult.succeeded());
        when(overdueMarkProcessor.markOne(101L)).thenReturn(OverdueMarkResult.skipped());

        OverdueJobSummary summary = overdueMarkService.markOverdueBorrows();

        assertThat(summary.totalProcessed()).isEqualTo(2);
        assertThat(summary.successCount()).isEqualTo(1);
        assertThat(summary.failedCount()).isEqualTo(1);
    }

    private BorrowRecord borrow(Long id) {
        Member member = TestDataFactory.activeMember(5L);
        Book book = TestDataFactory.book(10L, 0);
        BookCopy copy = TestDataFactory.bookCopy(50L + id, book, BookCopyStatus.BORROWED);
        return TestDataFactory.borrowRecord(id, member, copy, BorrowStatus.BORROWED);
    }
}
