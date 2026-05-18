package com.vn.service.circulation;

import com.vn.dto.circulation.response.FineResponse;
import com.vn.entity.Book;
import com.vn.entity.BookCopy;
import com.vn.entity.BorrowRecord;
import com.vn.entity.Member;
import com.vn.enums.BookCopyStatus;
import com.vn.enums.BorrowStatus;
import com.vn.mapper.CirculationMapper;
import com.vn.repository.BorrowRecordRepository;
import com.vn.service.impl.FineServiceImpl;
import com.vn.service.impl.circulation.FineStatusResolver;
import com.vn.testsupport.TestDataFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FineServiceImplTest {

    @Mock
    private BorrowRecordRepository borrowRecordRepository;

    private FineServiceImpl fineService;

    @BeforeEach
    void setUp() {
        fineService = new FineServiceImpl(borrowRecordRepository, new CirculationMapper(new FineStatusResolver()));
    }

    @Test
    void getMyFines_shouldReturnOnlyRepositoryFineRecordsAndMapStatus() {
        BorrowRecord borrow = overdueReturnedBorrow();
        when(borrowRecordRepository.findByMemberIdAndFineAmountGreaterThanOrderByFineCalculatedAtDesc(
                eq(5L),
                eq(BigDecimal.ZERO),
                isA(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of(borrow)));

        Page<FineResponse> response = fineService.getMyFines(5L, -1, 200);

        assertThat(response.getContent()).hasSize(1);
        FineResponse fine = response.getContent().getFirst();
        assertThat(fine.borrowId()).isEqualTo(100L);
        assertThat(fine.bookId()).isEqualTo(10L);
        assertThat(fine.itemBarcode()).isEqualTo("BC-50");
        assertThat(fine.fineAmount()).isEqualByComparingTo("15000");
        assertThat(fine.fineStatus()).isEqualTo("UNPAID");
        verify(borrowRecordRepository).findByMemberIdAndFineAmountGreaterThanOrderByFineCalculatedAtDesc(
                eq(5L),
                eq(BigDecimal.ZERO),
                isA(Pageable.class)
        );
    }

    @Test
    void getMyFines_shouldMapWaivedFineStatus() {
        BorrowRecord borrow = overdueReturnedBorrow();
        borrow.setFineWaivedBy(99L);
        borrow.setFineWaivedReason("Library goodwill");
        when(borrowRecordRepository.findByMemberIdAndFineAmountGreaterThanOrderByFineCalculatedAtDesc(
                eq(5L),
                eq(BigDecimal.ZERO),
                isA(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of(borrow)));

        FineResponse response = fineService.getMyFines(5L, 0, 10).getContent().getFirst();

        assertThat(response.fineStatus()).isEqualTo("WAIVED");
        assertThat(response.fineWaivedBy()).isEqualTo(99L);
        assertThat(response.fineWaivedReason()).isEqualTo("Library goodwill");
    }

    @Test
    void getMyFines_shouldMapPaidFineStatus() {
        BorrowRecord borrow = overdueReturnedBorrow();
        borrow.setFinePaidAt(Instant.parse("2026-05-18T12:00:00Z"));
        when(borrowRecordRepository.findByMemberIdAndFineAmountGreaterThanOrderByFineCalculatedAtDesc(
                eq(5L),
                eq(BigDecimal.ZERO),
                isA(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of(borrow)));

        FineResponse response = fineService.getMyFines(5L, 0, 10).getContent().getFirst();

        assertThat(response.fineStatus()).isEqualTo("PAID");
        assertThat(response.finePaidAt()).isEqualTo(Instant.parse("2026-05-18T12:00:00Z"));
    }

    private BorrowRecord overdueReturnedBorrow() {
        Member member = TestDataFactory.activeMember(5L);
        Book book = TestDataFactory.book(10L, 0);
        BookCopy copy = TestDataFactory.bookCopy(50L, book, BookCopyStatus.AVAILABLE);
        BorrowRecord borrow = TestDataFactory.borrowRecord(100L, member, copy, BorrowStatus.RETURNED);
        borrow.setReturnedAt(Instant.parse("2026-05-18T10:00:00Z"));
        borrow.setFineAmount(new BigDecimal("15000"));
        borrow.setFineCalculatedAt(Instant.parse("2026-05-18T10:00:00Z"));
        return borrow;
    }
}
