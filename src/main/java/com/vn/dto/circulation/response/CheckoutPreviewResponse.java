package com.vn.dto.circulation.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

@Schema(description = "Preview result for a staff checkout before the borrow record is created")
public record CheckoutPreviewResponse(
        @Schema(description = "Whether the checkout is allowed after applying circulation policy", example = "true")
        boolean allowed,

        @Schema(description = "Member id submitted for checkout", example = "2")
        Long memberId,

        @Schema(description = "Member full name when the member exists", example = "Nguyen Van A")
        String memberName,

        @Schema(description = "Member email when the member exists", example = "member@example.com")
        String memberEmail,

        @Schema(description = "Book id of the scanned copy", example = "10")
        Long bookId,

        @Schema(description = "Book title of the scanned copy", example = "Clean Code")
        String bookTitle,

        @Schema(description = "Physical book copy id resolved from the barcode", example = "3803")
        Long bookCopyId,

        @Schema(description = "Physical item barcode used for checkout", example = "LIB-2026-003516")
        String itemBarcode,

        @Schema(description = "Current status of the physical copy", example = "AVAILABLE")
        String itemStatus,

        @Schema(description = "Loan period that will be applied if checkout is committed", example = "14")
        Integer loanPeriodDays,

        @Schema(description = "Maximum renewal count captured at checkout time", example = "1")
        Integer maxRenewals,

        @Schema(description = "Calculated due date if checkout is allowed")
        Instant dueDate,

        @Schema(description = "Policy block reasons when checkout is not allowed")
        List<CirculationBlockResponse> reasons
) {
}
