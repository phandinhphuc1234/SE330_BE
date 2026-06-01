package com.vn.controller.docs;

import com.vn.dto.common.ApiResponse;
import com.vn.dto.staff.hold.response.StaffHoldResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

import java.util.List;

@Tag(name = "Staff Holds", description = "Staff APIs for viewing holds across the library")
@SecurityRequirement(name = "Bearer Authentication")
public interface StaffHoldApiDocs {

    @Operation(
            summary = "Search staff holds",
            description = "Librarian/Admin views holds across the system, commonly filtered by READY_FOR_PICKUP."
    )
    ResponseEntity<ApiResponse<List<StaffHoldResponse>>> searchHolds(
            @Parameter(description = "Reservation status, for example READY_FOR_PICKUP") String status,
            int page,
            int size
    );
}
