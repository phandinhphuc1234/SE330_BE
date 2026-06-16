package com.vn.controller;

import com.vn.controller.docs.EbookLoanApiDocs;
import com.vn.dto.common.ApiResponse;
import com.vn.dto.common.PageMeta;
import com.vn.dto.ebook.response.EbookLoanResponse;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.security.MemberUserDetails;
import com.vn.service.EbookLoanService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class EbookLoanController implements EbookLoanApiDocs {

    private final EbookLoanService ebookLoanService;

    @Override
    @PreAuthorize("hasRole('MEMBER')")
    @GetMapping("/ebook-loans/my")
    public ResponseEntity<ApiResponse<List<EbookLoanResponse>>> getMyEbookLoans(
            @AuthenticationPrincipal MemberUserDetails userDetails,
            @RequestParam(defaultValue = "false") boolean history,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        
        Long memberId = getCurrentMemberId(userDetails);
        Page<EbookLoanResponse> ebookLoans = ebookLoanService.getMyEbookLoans(memberId, history, page, size);
        
        return ResponseEntity.ok(ApiResponse.success(
                "Lấy danh sách ebook thành công",
                ebookLoans.getContent(),
                PageMeta.from(ebookLoans)
        ));
    }

    @Override
    @PreAuthorize("hasRole('MEMBER')")
    @PostMapping("/ebooks/{bookId}/loans")
    public ResponseEntity<ApiResponse<EbookLoanResponse>> borrowFreeEbook(
            @PathVariable Long bookId,
            @AuthenticationPrincipal MemberUserDetails userDetails) {
        
        EbookLoanResponse response = ebookLoanService.borrowFreeEbook(getCurrentMemberId(userDetails), bookId);
        return ResponseEntity.ok(ApiResponse.success("Mượn ebook miễn phí thành công", response));
    }

    private Long getCurrentMemberId(MemberUserDetails userDetails) {
        if (userDetails == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        return userDetails.getMember().getId();
    }
}
