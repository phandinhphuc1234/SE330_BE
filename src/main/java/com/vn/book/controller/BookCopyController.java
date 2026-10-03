package com.vn.book.controller;

import com.vn.book.controller.docs.BookCopyApiDocs;
import com.vn.book.dto.request.UpdateBookCopyRequest;
import com.vn.shared.dto.ApiResponse;
import com.vn.book.dto.response.BookCopyResponse;
import com.vn.shared.exception.AppException;
import com.vn.shared.exception.ErrorCode;
import com.vn.auth.security.MemberUserDetails;
import com.vn.book.service.BookCopyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/book-copies")
@RequiredArgsConstructor
public class BookCopyController implements BookCopyApiDocs {

    private final BookCopyService bookCopyService;
    // Cập nhật bản sao sách
    @PreAuthorize("hasAnyRole('LIBRARIAN', 'ADMIN')")
    @PatchMapping("/{copyId}")
    @Override
    public ResponseEntity<ApiResponse<BookCopyResponse>> updateBookCopy(
            @PathVariable Long copyId,
            @Valid @RequestBody UpdateBookCopyRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Cập nhật bản sao sách thành công",
                bookCopyService.updateBookCopy(copyId, request)
        ));
    }
    // Xóa 1 bản sao sách
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{copyId}")
    @Override
    public ResponseEntity<ApiResponse<Void>> deleteBookCopy(
            @PathVariable Long copyId,
            @AuthenticationPrincipal MemberUserDetails userDetails) {
        bookCopyService.deleteBookCopy(copyId, getCurrentMemberId(userDetails));
        return ResponseEntity.ok(ApiResponse.success("Xóa bản sao sách thành công", null));
    }

    private Long getCurrentMemberId(MemberUserDetails userDetails) {
        if (userDetails == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        return userDetails.getMember().getId();
    }
}

