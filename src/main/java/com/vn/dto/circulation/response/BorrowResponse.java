package com.vn.dto.circulation.response;

import java.math.BigDecimal;
import java.time.Instant;

public record BorrowResponse(
        Long borrowId,
        Long memberId,
        Long bookId,
        String bookTitle,
        Long bookCopyId,
        String itemBarcode,
        Instant borrowedAt,
        Instant dueDate,
        Instant returnedAt,
        String status,
        Integer renewCount,
        Integer maxRenewals,
        BigDecimal fineAmount
) {
}
