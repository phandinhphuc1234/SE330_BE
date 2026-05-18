package com.vn.controller.docs;

import com.vn.dto.circulation.request.CreateHoldRequest;
import com.vn.dto.circulation.response.BorrowResponse;
import com.vn.dto.circulation.response.HoldResponse;
import com.vn.dto.common.ApiResponse;
import com.vn.security.MemberUserDetails;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

import java.util.List;

@Tag(name = "Holds", description = "APIs for member holds and staff hold checkout")
@SecurityRequirement(name = "Bearer Authentication")
public interface HoldApiDocs {

    @Operation(
            summary = "Create hold",
            description = """
                    Member places a hold for a book.
                    The backend only allows this when the book has no AVAILABLE physical copies.
                    """
    )
    ResponseEntity<ApiResponse<HoldResponse>> createHold(
            @Parameter(hidden = true) MemberUserDetails userDetails,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Book to place on hold",
                    required = true
            )
            CreateHoldRequest request
    );

    @Operation(
            summary = "Get my holds",
            description = "Member views their own hold queue records, optionally filtered by status."
    )
    ResponseEntity<ApiResponse<List<HoldResponse>>> getMyHolds(
            @Parameter(hidden = true) MemberUserDetails userDetails,
            @Parameter(
                    description = "Optional hold status filter",
                    schema = @Schema(allowableValues = {
                            "WAITING",
                            "NOTIFIED",
                            "READY_FOR_PICKUP",
                            "FULFILLED",
                            "CANCELLED",
                            "EXPIRED"
                    })
            )
            String status,
            @Parameter(description = "Page number, starts from 0") int page,
            @Parameter(description = "Page size, maximum allowed size is 100") int size
    );

    @Operation(
            summary = "Cancel hold",
            description = """
                    Member cancels their own active hold.
                    Librarian/Admin can also cancel a hold on behalf of a member.
                    If a READY_FOR_PICKUP hold has an assigned copy, the copy is reassigned to the next waiting hold or returned to shelf.
                    """
    )
    ResponseEntity<ApiResponse<HoldResponse>> cancelHold(
            @Parameter(hidden = true) MemberUserDetails userDetails,
            @Parameter(description = "Hold ID", required = true) Long holdId
    );

    @Operation(
            summary = "Checkout hold",
            description = """
                    Librarian/Admin checks out a READY_FOR_PICKUP hold at the circulation desk.
                    The assigned copy must be ON_HOLD_SHELF.
                    Requires Idempotency-Key.
                    """
    )
    ResponseEntity<ApiResponse<BorrowResponse>> checkoutHold(
            @Parameter(description = "Idempotency key", required = true) String idempotencyKey,
            @Parameter(hidden = true) MemberUserDetails userDetails,
            @Parameter(description = "Hold ID", required = true) Long holdId
    );
}
