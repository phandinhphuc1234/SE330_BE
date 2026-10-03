package com.vn.loan.controller;

import com.vn.loan.controller.docs.FineApiDocs;
import com.vn.loan.dto.response.FineResponse;
import com.vn.shared.dto.ApiResponse;
import com.vn.shared.dto.PageMeta;
import com.vn.shared.exception.AppException;
import com.vn.shared.exception.ErrorCode;
import com.vn.auth.security.MemberUserDetails;
import com.vn.loan.service.FineService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/fines")
@RequiredArgsConstructor
public class FineController implements FineApiDocs {

    private final FineService fineService;

    @Override
    @PreAuthorize("hasRole('MEMBER')")
    @GetMapping("/my")
    public ResponseEntity<ApiResponse<List<FineResponse>>> getMyFines(
            @AuthenticationPrincipal MemberUserDetails userDetails,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Page<FineResponse> fines = fineService.getMyFines(getCurrentMemberId(userDetails), page, size);
        return ResponseEntity.ok(ApiResponse.success(
                "Lấy danh sách tiền phạt thành công",
                fines.getContent(),
                PageMeta.from(fines)
        ));
    }

    // Chức năng: kiểm tra đăng nhập và lấy memberId từ principal.
    private Long getCurrentMemberId(MemberUserDetails userDetails) {
        if (userDetails == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        return userDetails.getMember().getId();
    }
}
