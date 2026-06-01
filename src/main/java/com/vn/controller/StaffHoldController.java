package com.vn.controller;

import com.vn.controller.docs.StaffHoldApiDocs;
import com.vn.dto.common.ApiResponse;
import com.vn.dto.common.PageMeta;
import com.vn.dto.staff.hold.response.StaffHoldResponse;
import com.vn.service.StaffHoldService;
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
@RequestMapping("/api/staff/holds")
@RequiredArgsConstructor
public class StaffHoldController implements StaffHoldApiDocs {

    private final StaffHoldService staffHoldService;

    @Override
    @PreAuthorize("hasAnyRole('LIBRARIAN', 'ADMIN')")
    @GetMapping
    public ResponseEntity<ApiResponse<List<StaffHoldResponse>>> searchHolds(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<StaffHoldResponse> holds = staffHoldService.searchHolds(status, page, size);
        return ResponseEntity.ok(ApiResponse.success(
                "Lấy danh sách giữ chỗ toàn hệ thống thành công",
                holds.getContent(),
                PageMeta.from(holds)
        ));
    }
}
