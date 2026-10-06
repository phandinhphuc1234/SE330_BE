package com.vn.member.controller.docs;

import com.vn.shared.dto.ApiResponse;
import com.vn.loan.dto.staff.loan.response.StaffLoanResponse;
import com.vn.member.dto.staff.response.StaffMemberDetailResponse;
import com.vn.member.dto.staff.response.StaffMemberListItemResponse;
import com.vn.member.dto.staff.request.UpdateMemberStatusRequest;
import com.vn.member.dto.staff.response.MemberStatusUpdateResponse;
import com.vn.auth.security.MemberUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

import java.util.List;

@Tag(name = "Staff Members", description = "Staff APIs for borrower-centric member and loan views")
@SecurityRequirement(name = "Bearer Authentication")
public interface StaffMemberApiDocs {
    // Lấy ra danh sách staff trong hệ thống
    @Operation(
            summary = "Search staff members",
            description = "Librarian/Admin searches members and sees circulation summary counts."
    )
    ResponseEntity<ApiResponse<List<StaffMemberListItemResponse>>> searchMembers(
            @Parameter(description = "Search by member id, full name, email or phone") String q,
            @Parameter(description = "Member account status") String status,
            @Parameter(description = "When true, only members with overdue loans are returned") Boolean hasOverdue,
            int page,
            int size
    );
    // API lấy member detail trong hệ thống
    @Operation(
            summary = "Get staff member detail",
            description = "Librarian/Admin views one member profile with circulation summary."
    )
    ResponseEntity<ApiResponse<StaffMemberDetailResponse>> getMember(Long memberId);

    @Operation(
            summary = "Get member loans for staff",
            description = "Librarian/Admin views open loans or borrow history for one member."
    )
    ResponseEntity<ApiResponse<List<StaffLoanResponse>>> getMemberLoans(
            Long memberId,
            @Parameter(description = "Borrow status") String status,
            @Parameter(description = "Defaults to true. Use false for borrow history.") Boolean openOnly,
            @Parameter(description = "When true, only overdue loans are returned") Boolean overdue,
            int page,
            int size
    );

    @Operation(
            summary = "Update member account status",
            description = "Admin changes a member to ACTIVE, INACTIVE or BANNED. The system locks the member row, writes an audit entry, and revokes every existing session. An admin cannot change their own status."
    )
    ResponseEntity<ApiResponse<MemberStatusUpdateResponse>> updateMemberStatus(
            Long memberId,
            MemberUserDetails userDetails,
            UpdateMemberStatusRequest request
    );
}
