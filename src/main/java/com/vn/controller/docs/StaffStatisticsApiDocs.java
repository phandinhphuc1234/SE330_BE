package com.vn.controller.docs;

import com.vn.dto.common.ApiResponse;
import com.vn.dto.staff.statistics.response.StaffBorrowStatisticsResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;

@Tag(name = "Staff Statistics", description = "Staff/Admin circulation statistics APIs")
@SecurityRequirement(name = "Bearer Authentication")
public interface StaffStatisticsApiDocs {

    @Operation(
            summary = "Get daily borrow and return statistics",
            description = "Returns a maximum 21-day range in the Asia/Ho_Chi_Minh business timezone. "
                    + "Supported filters are category, isbn and title."
    )
    ResponseEntity<ApiResponse<StaffBorrowStatisticsResponse>> getBorrowStatistics(
            @Parameter(description = "Range start, ISO date", required = true) LocalDate from,
            @Parameter(description = "Range end, ISO date", required = true) LocalDate to,
            @Parameter(description = "Optional filter: category, isbn or title") String filterType,
            @Parameter(description = "Required when filterType is present") String filterValue,
            @Parameter(description = "Optional exact book language") String language
    );
}
