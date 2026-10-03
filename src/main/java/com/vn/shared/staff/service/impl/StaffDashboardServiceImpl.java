package com.vn.shared.staff.service.impl;

import com.vn.shared.staff.dto.dashboard.response.StaffDashboardSummaryResponse;
import com.vn.loan.enums.BorrowStatus;
import com.vn.ebook.enums.EbookLoanStatus;
import com.vn.loan.enums.ReservationStatus;
import com.vn.loan.repository.BorrowRecordRepository;
import com.vn.ebook.repository.EbookLoanRepository;
import com.vn.loan.repository.ReservationRepository;
import com.vn.shared.staff.service.StaffDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class StaffDashboardServiceImpl implements StaffDashboardService {

    private final BorrowRecordRepository borrowRecordRepository;
    private final EbookLoanRepository ebookLoanRepository;
    private final ReservationRepository reservationRepository;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public StaffDashboardSummaryResponse getSummary() {
        Instant now = clock.instant();
        LocalDate today = LocalDate.now(clock);
        Instant todayStart = today.atStartOfDay(clock.getZone()).toInstant();
        Instant tomorrowStart = today.plusDays(1).atStartOfDay(clock.getZone()).toInstant();

        BigDecimal unpaidFineTotal = borrowRecordRepository.sumUnpaidFineTotal();

        return new StaffDashboardSummaryResponse(
                borrowRecordRepository.countByStatusIn(BorrowStatus.activeStatuses())
                        + ebookLoanRepository.countByStatus(EbookLoanStatus.ACTIVE),
                borrowRecordRepository.countOverdueLoans(BorrowStatus.OVERDUE, BorrowStatus.BORROWED, now)
                        + ebookLoanRepository.countOverdueEbookLoans(EbookLoanStatus.ACTIVE, now),
                reservationRepository.countByStatus(ReservationStatus.READY_FOR_PICKUP),
                borrowRecordRepository.countUnpaidFineRecords(),
                unpaidFineTotal == null ? BigDecimal.ZERO : unpaidFineTotal,
                borrowRecordRepository.countByBorrowedAtGreaterThanEqualAndBorrowedAtLessThan(todayStart, tomorrowStart)
                        + ebookLoanRepository.countByBorrowedAtGreaterThanEqualAndBorrowedAtLessThan(todayStart, tomorrowStart),
                borrowRecordRepository.countByReturnedAtGreaterThanEqualAndReturnedAtLessThan(todayStart, tomorrowStart)
                        + ebookLoanRepository.countByReturnedAtGreaterThanEqualAndReturnedAtLessThan(todayStart, tomorrowStart),
                now
        );
    }
}
