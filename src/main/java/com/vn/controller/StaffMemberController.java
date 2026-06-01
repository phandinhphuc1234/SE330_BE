package com.vn.controller;

import com.vn.controller.docs.StaffMemberApiDocs;
import com.vn.dto.common.ApiResponse;
import com.vn.dto.common.PageMeta;
import com.vn.dto.staff.loan.response.StaffLoanResponse;
import com.vn.dto.staff.member.response.StaffMemberDetailResponse;
import com.vn.dto.staff.member.response.StaffMemberListItemResponse;
import com.vn.service.StaffMemberService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
}
