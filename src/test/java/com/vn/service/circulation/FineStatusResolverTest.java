package com.vn.service.circulation;

import com.vn.entity.BorrowRecord;
import com.vn.enums.BookCopyStatus;
import com.vn.enums.BorrowStatus;
import com.vn.enums.FineStatus;
import com.vn.service.impl.circulation.support.FineStatusResolver;
import com.vn.testsupport.TestDataFactory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class FineStatusResolverTest {

    private final FineStatusResolver resolver = new FineStatusResolver();

    @Test
    void resolve_shouldReturnNone_whenFineAmountIsZero() {
        BorrowRecord borrow = borrowWithFine(BigDecimal.ZERO);

        assertThat(resolver.resolve(borrow)).isEqualTo(FineStatus.NONE);
    }

    @Test
    void resolve_shouldReturnWaived_whenFineWasWaived() {
        BorrowRecord borrow = borrowWithFine(new BigDecimal("15000"));
        borrow.setFineWaivedBy(99L);

        assertThat(resolver.resolve(borrow)).isEqualTo(FineStatus.WAIVED);
    }

    @Test
    void resolve_shouldReturnPaid_whenFineWasPaid() {
        BorrowRecord borrow = borrowWithFine(new BigDecimal("15000"));
        borrow.setFinePaidAt(Instant.parse("2026-05-18T12:00:00Z"));

        assertThat(resolver.resolve(borrow)).isEqualTo(FineStatus.PAID);
    }

    @Test
    void resolve_shouldReturnUnpaid_whenFineHasAmountButNoSettlement() {
        BorrowRecord borrow = borrowWithFine(new BigDecimal("15000"));

        assertThat(resolver.resolve(borrow)).isEqualTo(FineStatus.UNPAID);
    }

    private BorrowRecord borrowWithFine(BigDecimal fineAmount) {
        BorrowRecord borrow = TestDataFactory.borrowRecord(
                100L,
                TestDataFactory.activeMember(5L),
                TestDataFactory.bookCopy(50L, TestDataFactory.book(10L, 0), BookCopyStatus.AVAILABLE),
                BorrowStatus.RETURNED
        );
        borrow.setFineAmount(fineAmount);
        return borrow;
    }
}
