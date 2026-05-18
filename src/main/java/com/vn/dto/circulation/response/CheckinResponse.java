package com.vn.dto.circulation.response;

import java.math.BigDecimal;
import java.time.Instant;

public record CheckinResponse(
        Long borrowId,
        Long memberId,
        Long bookId,
        String bookTitle,
        Long bookCopyId,
        String itemBarcode,
        Instant returnedAt,
        long overdueDays,
        BigDecimal fineAmount,
        String borrowStatus,
        String bookCopyStatus,
        Long nextHoldId,
        String nextHoldStatus
) {
}
