package com.vn.controller.docs;

import com.vn.dto.common.ApiResponse;
import com.vn.dto.staff.dashboard.response.StaffDashboardSummaryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "Staff Dashboard", description = "Staff/Admin dashboard summary APIs")
@SecurityRequirement(name = "Bearer Authentication")
public interface StaffDashboardApiDocs {

    @Operation(
            summary = "Get staff dashboard summary",
            description = "Returns summary counters for active loans, overdue loans, ready holds, unpaid fines and today's circulation."
    )
    ResponseEntity<ApiResponse<StaffDashboardSummaryResponse>> getSummary();
}
