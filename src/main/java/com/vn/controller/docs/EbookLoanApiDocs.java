package com.vn.controller.docs;

import com.vn.dto.common.ApiResponse;
import com.vn.dto.ebook.response.EbookLoanResponse;
import com.vn.security.MemberUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

import java.util.List;

@Tag(name = "Ebook Loans", description = "Member APIs for ebook loan management and history")
public interface EbookLoanApiDocs {

    @Operation(
            summary = "Get my ebook loans",
            description = "Retrieve the current active ebook loans or the full loan history for the authenticated member."
    )
    ResponseEntity<ApiResponse<List<EbookLoanResponse>>> getMyEbookLoans(
            @Parameter(hidden = true) MemberUserDetails userDetails,
            @Parameter(description = "If true, returns all loans; if false, returns only ACTIVE loans") boolean history,
            @Parameter(description = "Page number (0-based)") int page,
            @Parameter(description = "Page size") int size
    );

    @Operation(
            summary = "Borrow free ebook",
            description = "Create an active ebook loan for a FREE ebook. PAID ebooks must use the payment flow instead."
    )
    ResponseEntity<ApiResponse<EbookLoanResponse>> borrowFreeEbook(
            @Parameter(description = "ID of the book containing the ebook") Long bookId,
            @Parameter(hidden = true) MemberUserDetails userDetails
    );
}
