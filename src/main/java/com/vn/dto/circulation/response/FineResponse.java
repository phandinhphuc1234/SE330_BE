package com.vn.dto.circulation.response;

import java.math.BigDecimal;
import java.time.Instant;

public record FineResponse(
        Long borrowId,
        Long memberId,
        Long bookId,
        String bookTitle,
        Long bookCopyId,
        String itemBarcode,
        Instant borrowedAt,
        Instant dueDate,
        Instant returnedAt,
        BigDecimal fineAmount,
        Instant fineCalculatedAt,
        Instant finePaidAt,
        Long fineWaivedBy,
        String fineWaivedReason,
        String fineStatus
) {
}
