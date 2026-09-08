package com.vn.dto.staff.statistics.response;

import java.time.LocalDate;

public record StaffBorrowStatisticsDayResponse(
        LocalDate date,
        long borrowed,
        long returned
) {
}
