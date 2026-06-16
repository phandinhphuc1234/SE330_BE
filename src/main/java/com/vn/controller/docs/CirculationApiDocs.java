package com.vn.controller.docs;

import com.vn.dto.circulation.request.CheckinRequest;
import com.vn.dto.circulation.request.CheckoutRequest;
import com.vn.dto.circulation.request.RenewBorrowRequest;
import com.vn.dto.circulation.response.BorrowResponse;
import com.vn.dto.circulation.response.CheckinResponse;
import com.vn.dto.circulation.response.CheckoutPreviewResponse;
import com.vn.dto.circulation.response.RenewBorrowResponse;
import com.vn.dto.common.ApiResponse;
import com.vn.security.MemberUserDetails;
import com.vn.dto.staff.loan.response.StaffLoanResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

import java.util.List;

@Tag(name = "Circulation", description = "APIs for borrowing, returning and renewing physical book copies")
@SecurityRequirement(name = "Bearer Authentication")
public interface CirculationApiDocs {

    @Operation(
            summary = "Preview staff checkout",
            description = """
                    Validate whether a MEMBER can borrow a physical copy before committing checkout.
                    The response includes display data for the staff UI such as member name, book title,
                    item barcode, item status, loan period, due date and max renewals.
                    """
    )
    ResponseEntity<ApiResponse<CheckoutPreviewResponse>> previewCheckout(CheckoutRequest request);

    @Operation(summary = "Staff checkout", description = "Librarian/Admin checks out an AVAILABLE copy for a MEMBER. Requires Idempotency-Key.")
    ResponseEntity<ApiResponse<BorrowResponse>> checkout(String idempotencyKey, MemberUserDetails userDetails, CheckoutRequest request);

    @Operation(summary = "Staff check-in", description = "Librarian/Admin returns a borrowed copy by barcode. Requires Idempotency-Key.")
    ResponseEntity<ApiResponse<CheckinResponse>> checkin(String idempotencyKey, MemberUserDetails userDetails, CheckinRequest request);

    @Operation(summary = "Get my active borrows", description = "Member views currently borrowed/overdue books.")
    ResponseEntity<ApiResponse<List<StaffLoanResponse>>> getMyActiveBorrows(MemberUserDetails userDetails, int page, int size);

    @Operation(summary = "Get my borrow history", description = "Member views all borrow history.")
    ResponseEntity<ApiResponse<List<StaffLoanResponse>>> getMyBorrowHistory(MemberUserDetails userDetails, int page, int size);

    @Operation(summary = "Renew my borrow", description = "Member renews their own borrow. Requires Idempotency-Key.")
    ResponseEntity<ApiResponse<RenewBorrowResponse>> renewMyBorrow(
            @Parameter(description = "Idempotency key", required = true) String idempotencyKey,
            MemberUserDetails userDetails,
            Long borrowId,
            RenewBorrowRequest request
    );

    @Operation(summary = "Staff renew borrow", description = "Librarian/Admin renews a MEMBER borrow. Requires Idempotency-Key.")
    ResponseEntity<ApiResponse<RenewBorrowResponse>> staffRenewBorrow(
            @Parameter(description = "Idempotency key", required = true) String idempotencyKey,
            MemberUserDetails userDetails,
            Long borrowId,
            RenewBorrowRequest request
    );
}
