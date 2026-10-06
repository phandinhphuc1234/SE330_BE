package com.vn.loan.service;

import com.vn.book.entity.Book;
import com.vn.book.entity.BookCopy;
import com.vn.member.entity.Member;
import com.vn.loan.entity.Reservation;
import com.vn.book.enums.BookCopyStatus;
import com.vn.loan.enums.ReservationStatus;
import com.vn.loan.repository.ReservationRepository;
import com.vn.loan.service.impl.holdexpiry.HoldExpiryJobSummary;
import com.vn.loan.service.impl.holdexpiry.HoldExpiryProcessor;
import com.vn.loan.service.impl.holdexpiry.HoldExpiryResult;
import com.vn.loan.service.impl.holdexpiry.HoldExpiryService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HoldExpiryServiceTest {

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private HoldExpiryProcessor holdExpiryProcessor;

    private HoldExpiryService holdExpiryService;

    @BeforeEach
    void setUp() {
        holdExpiryService = new HoldExpiryService(
                reservationRepository, holdExpiryProcessor, com.vn.shared.testsupport.TestTime.CLOCK);
    }

    @Test
    void expireReadyHolds_shouldReturnSummaryFromProcessorResults() {
        Reservation first = reservation(700L);
        Reservation second = reservation(701L);
        when(reservationRepository.findExpiredReadyHoldCandidates(
                any(),
                isA(Instant.class),
                isA(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of(first, second)));
        when(holdExpiryProcessor.expireOne(org.mockito.ArgumentMatchers.eq(700L), isA(Instant.class)))
                .thenReturn(HoldExpiryResult.expired());
        when(holdExpiryProcessor.expireOne(org.mockito.ArgumentMatchers.eq(701L), isA(Instant.class)))
                .thenReturn(HoldExpiryResult.skipped());

        HoldExpiryJobSummary summary = holdExpiryService.expireReadyHolds();

        assertThat(summary.totalProcessed()).isEqualTo(2);
        assertThat(summary.successCount()).isEqualTo(1);
        assertThat(summary.failedCount()).isEqualTo(1);
    }

    private Reservation reservation(Long id) {
        Member member = TestDataFactory.activeMember(5L);
        Book book = TestDataFactory.book(10L, 0);
        BookCopy copy = TestDataFactory.bookCopy(50L + id, book, BookCopyStatus.ON_HOLD_SHELF);
        return TestDataFactory.reservation(id, member, book, ReservationStatus.READY_FOR_PICKUP, copy);
    }
}
