package com.vn.service;

import com.vn.dto.staff.statistics.response.StaffBorrowStatisticsResponse;

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
