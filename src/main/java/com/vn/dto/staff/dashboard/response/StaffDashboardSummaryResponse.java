package com.vn.dto.staff.dashboard.response;

import java.math.BigDecimal;
import java.time.Instant;

public record StaffDashboardSummaryResponse(
        long activeLoans,
        long overdueLoans,
        long holdsReadyForPickup,
        long unpaidFineCount,
        BigDecimal unpaidFineTotal,
        long borrowedToday,
        long returnedToday,
        Instant generatedAt
) {
}
