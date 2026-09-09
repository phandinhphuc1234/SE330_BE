package com.vn.controller;

import com.vn.controller.docs.StaffStatisticsApiDocs;
import com.vn.dto.common.ApiResponse;
import com.vn.dto.staff.statistics.response.StaffBorrowStatisticsResponse;
import com.vn.service.StaffStatisticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/staff/statistics")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('LIBRARIAN', 'ADMIN')")
public class StaffStatisticsController implements StaffStatisticsApiDocs {

    private final StaffStatisticsService staffStatisticsService;

    @GetMapping("/borrows")
    @Override
    public ResponseEntity<ApiResponse<StaffBorrowStatisticsResponse>> getBorrowStatistics(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String filterType,
            @RequestParam(required = false) String filterValue,
            @RequestParam(required = false) String language) {

        StaffBorrowStatisticsResponse statistics = staffStatisticsService.getBorrowStatistics(
                from, to, filterType, filterValue, language
        );
        return ResponseEntity.ok(ApiResponse.success("Lấy thống kê mượn/trả thành công", statistics));
    }
}
