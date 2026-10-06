package com.vn.shared.staff.dto.statistics.response;

import java.time.LocalDate;

public record StaffBorrowStatisticsDayResponse(
        LocalDate date,
        long borrowed,
        long returned
) {
}
