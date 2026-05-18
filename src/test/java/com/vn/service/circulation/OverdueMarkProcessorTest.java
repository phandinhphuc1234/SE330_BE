package com.vn.service.circulation;

import com.vn.entity.Book;
import com.vn.entity.BookCopy;
import com.vn.entity.BorrowRecord;
import com.vn.entity.Member;
import com.vn.enums.BookCopyStatus;
import com.vn.enums.BorrowStatus;
import com.vn.repository.BorrowRecordRepository;
import com.vn.service.impl.circulation.overdue.OverdueMarkProcessor;
import com.vn.service.impl.circulation.overdue.OverdueMarkResult;
import com.vn.testsupport.TestDataFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OverdueMarkProcessorTest {

    @Mock
    private BorrowRecordRepository borrowRecordRepository;

    private OverdueMarkProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new OverdueMarkProcessor(borrowRecordRepository);
    }

    @Test
    void markOne_shouldSetBorrowAndCopyOverdue_whenBorrowIsStillBorrowed() {
        BorrowRecord borrow = borrow(BorrowStatus.BORROWED, BookCopyStatus.BORROWED);
        when(borrowRecordRepository.findLockedForOverdueById(100L)).thenReturn(Optional.of(borrow));
        when(borrowRecordRepository.save(any(BorrowRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OverdueMarkResult result = processor.markOne(100L);

        assertThat(result.success()).isTrue();
        assertThat(borrow.getStatus()).isEqualTo(BorrowStatus.OVERDUE);
        assertThat(borrow.getBookCopy().getStatus()).isEqualTo(BookCopyStatus.OVERDUE);
        verify(borrowRecordRepository).save(borrow);
    }

    @Test
    void markOne_shouldSkip_whenBorrowWasAlreadyClosedByAnotherFlow() {
        BorrowRecord borrow = borrow(BorrowStatus.RETURNED, BookCopyStatus.AVAILABLE);
        when(borrowRecordRepository.findLockedForOverdueById(100L)).thenReturn(Optional.of(borrow));

        OverdueMarkResult result = processor.markOne(100L);

        assertThat(result.success()).isFalse();
        assertThat(borrow.getStatus()).isEqualTo(BorrowStatus.RETURNED);
        verify(borrowRecordRepository, never()).save(any());
    }

    @Test
    void markOne_shouldSkip_whenBorrowNotFound() {
        when(borrowRecordRepository.findLockedForOverdueById(404L)).thenReturn(Optional.empty());

        OverdueMarkResult result = processor.markOne(404L);

        assertThat(result.success()).isFalse();
        verify(borrowRecordRepository, never()).save(any());
    }

    private BorrowRecord borrow(BorrowStatus borrowStatus, BookCopyStatus copyStatus) {
        Member member = TestDataFactory.activeMember(5L);
        Book book = TestDataFactory.book(10L, 0);
        BookCopy copy = TestDataFactory.bookCopy(50L, book, copyStatus);
        return TestDataFactory.borrowRecord(100L, member, copy, borrowStatus);
    }
}
