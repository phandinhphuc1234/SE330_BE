package com.vn.service.impl;

import com.vn.dto.staff.dashboard.response.StaffDashboardSummaryResponse;
import com.vn.enums.BorrowStatus;
import com.vn.enums.ReservationStatus;
import com.vn.repository.BorrowRecordRepository;
import com.vn.repository.ReservationRepository;
import com.vn.service.StaffDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

@Service
@RequiredArgsConstructor
public class StaffDashboardServiceImpl implements StaffDashboardService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final BorrowRecordRepository borrowRecordRepository;
    private final ReservationRepository reservationRepository;

    @Override
    @Transactional(readOnly = true)
    public StaffDashboardSummaryResponse getSummary() {
        Instant now = Instant.now();
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        Instant todayStart = today.atStartOfDay(BUSINESS_ZONE).toInstant();
        Instant tomorrowStart = today.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant();

        BigDecimal unpaidFineTotal = borrowRecordRepository.sumUnpaidFineTotal();

        return new StaffDashboardSummaryResponse(
                borrowRecordRepository.countByStatusIn(BorrowStatus.activeStatuses()),
                borrowRecordRepository.countOverdueLoans(BorrowStatus.OVERDUE, BorrowStatus.BORROWED, now),
                reservationRepository.countByStatus(ReservationStatus.READY_FOR_PICKUP),
                borrowRecordRepository.countUnpaidFineRecords(),
                unpaidFineTotal == null ? BigDecimal.ZERO : unpaidFineTotal,
                borrowRecordRepository.countByBorrowedAtGreaterThanEqualAndBorrowedAtLessThan(todayStart, tomorrowStart),
                borrowRecordRepository.countByReturnedAtGreaterThanEqualAndReturnedAtLessThan(todayStart, tomorrowStart),
                now
        );
    }
}
