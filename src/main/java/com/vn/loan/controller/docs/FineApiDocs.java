package com.vn.loan.controller.docs;

import com.vn.loan.dto.response.FineResponse;
import com.vn.shared.dto.ApiResponse;
import com.vn.auth.security.MemberUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

import java.util.List;

@Tag(name = "Fines", description = "APIs for viewing library fines")
@SecurityRequirement(name = "Bearer Authentication")
public interface FineApiDocs {

    @Operation(
            summary = "Get my fines",
            description = """
                    Member views fines that were calculated from overdue returned borrows.
                    This endpoint only reads calculated fines; real payment is intentionally not implemented yet.
                    """
    )
    ResponseEntity<ApiResponse<List<FineResponse>>> getMyFines(
            @Parameter(hidden = true) MemberUserDetails userDetails,
            @Parameter(description = "Page number, starts from 0") int page,
            @Parameter(description = "Page size, maximum allowed size is 100") int size
    );
}
