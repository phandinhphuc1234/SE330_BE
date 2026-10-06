package com.vn.shared.staff.service;

import com.vn.shared.staff.dto.statistics.response.StaffBorrowStatisticsResponse;

import java.time.LocalDate;

public interface StaffStatisticsService {

    StaffBorrowStatisticsResponse getBorrowStatistics(
            LocalDate from,
            LocalDate to,
            String filterType,
            String filterValue,
            String language
    );
}
