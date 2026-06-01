package com.vn.controller;

import com.vn.controller.docs.StaffLoanApiDocs;
import com.vn.dto.common.ApiResponse;
import com.vn.dto.common.PageMeta;
import com.vn.dto.staff.loan.response.StaffLoanResponse;
import com.vn.service.StaffLoanService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/staff/loans")
@RequiredArgsConstructor
public class StaffLoanController implements StaffLoanApiDocs {

    private final StaffLoanService staffLoanService;

    @Override
    @PreAuthorize("hasAnyRole('LIBRARIAN', 'ADMIN')")
    @GetMapping
    public ResponseEntity<ApiResponse<List<StaffLoanResponse>>> searchLoans(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Boolean openOnly,
            @RequestParam(required = false) Boolean overdue,
            @RequestParam(required = false) String dueFrom,
            @RequestParam(required = false) String dueTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<StaffLoanResponse> loans = staffLoanService.searchLoans(q, status, openOnly, overdue, dueFrom, dueTo, page, size);
        return ResponseEntity.ok(ApiResponse.success(
                "Lấy danh sách lượt mượn thành công",
                loans.getContent(),
                PageMeta.from(loans)
        ));
    }
}
