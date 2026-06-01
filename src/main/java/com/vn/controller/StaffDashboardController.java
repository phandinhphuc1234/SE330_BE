package com.vn.controller;

import com.vn.controller.docs.StaffDashboardApiDocs;
import com.vn.dto.common.ApiResponse;
import com.vn.dto.staff.dashboard.response.StaffDashboardSummaryResponse;
import com.vn.service.StaffDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/staff/dashboard")
@RequiredArgsConstructor
public class StaffDashboardController implements StaffDashboardApiDocs {

    private final StaffDashboardService staffDashboardService;

    @Override
    @PreAuthorize("hasAnyRole('LIBRARIAN', 'ADMIN')")
    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<StaffDashboardSummaryResponse>> getSummary() {
        return ResponseEntity.ok(ApiResponse.success(
                "Lấy tổng quan dashboard staff thành công",
                staffDashboardService.getSummary()
        ));
    }
}
