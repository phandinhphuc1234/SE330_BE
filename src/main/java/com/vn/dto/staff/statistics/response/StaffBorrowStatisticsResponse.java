package com.vn.dto.staff.statistics.response;

import java.time.LocalDate;
import java.util.List;

public record StaffBorrowStatisticsResponse(
        List<StaffBorrowStatisticsDayResponse> days,
        long totalBorrowed,
        long totalReturned,
        long netOnLoan,
        LocalDate peakBorrowDate,
        long peakBorrowCount
) {
}
