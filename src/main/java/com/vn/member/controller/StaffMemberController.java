package com.vn.member.controller;

import com.vn.member.controller.docs.StaffMemberApiDocs;
import com.vn.shared.dto.ApiResponse;
import com.vn.shared.dto.PageMeta;
import com.vn.loan.dto.staff.loan.response.StaffLoanResponse;
import com.vn.member.dto.staff.response.StaffMemberDetailResponse;
import com.vn.member.dto.staff.response.StaffMemberListItemResponse;
import com.vn.member.dto.staff.request.UpdateMemberStatusRequest;
import com.vn.member.dto.staff.response.MemberStatusUpdateResponse;
import com.vn.auth.security.MemberUserDetails;
import com.vn.member.service.StaffMemberService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestBody;
import jakarta.validation.Valid;

import java.util.List;

@RestController
@RequestMapping("/api/staff/members")
@RequiredArgsConstructor
public class StaffMemberController implements StaffMemberApiDocs {

    private final StaffMemberService staffMemberService;

    @Override
    @PreAuthorize("hasAnyRole('LIBRARIAN', 'ADMIN')")
    @GetMapping
    public ResponseEntity<ApiResponse<List<StaffMemberListItemResponse>>> searchMembers(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Boolean hasOverdue,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<StaffMemberListItemResponse> members = staffMemberService.searchMembers(q, status, hasOverdue, page, size);
        return ResponseEntity.ok(ApiResponse.success(
                "Lấy danh sách bạn đọc thành công",
                members.getContent(),
                PageMeta.from(members)
        ));
    }

    @Override
    @PreAuthorize("hasAnyRole('LIBRARIAN', 'ADMIN')")
    @GetMapping("/{memberId}")
    public ResponseEntity<ApiResponse<StaffMemberDetailResponse>> getMember(@PathVariable Long memberId) {
        return ResponseEntity.ok(ApiResponse.success(
                "Lấy hồ sơ bạn đọc thành công",
                staffMemberService.getMemberDetail(memberId)
        ));
    }

    @Override
    @PreAuthorize("hasAnyRole('LIBRARIAN', 'ADMIN')")
    @GetMapping("/{memberId}/loans")
    public ResponseEntity<ApiResponse<List<StaffLoanResponse>>> getMemberLoans(
            @PathVariable Long memberId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Boolean openOnly,
            @RequestParam(required = false) Boolean overdue,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<StaffLoanResponse> loans = staffMemberService.getMemberLoans(memberId, status, openOnly, overdue, page, size);
        return ResponseEntity.ok(ApiResponse.success(
                "Lấy danh sách lượt mượn của bạn đọc thành công",
                loans.getContent(),
                PageMeta.from(loans)
        ));
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/{memberId}/status")
    public ResponseEntity<ApiResponse<MemberStatusUpdateResponse>> updateMemberStatus(
            @PathVariable Long memberId,
            @AuthenticationPrincipal MemberUserDetails userDetails,
            @Valid @RequestBody UpdateMemberStatusRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Cập nhật trạng thái tài khoản thành công",
                staffMemberService.updateMemberStatus(userDetails.getMember().getId(), memberId, request)
        ));
    }
}
