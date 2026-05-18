package com.vn.dto.circulation.response;

import java.time.Instant;

public record HoldResponse(
        Long holdId,
        Long memberId,
        Long bookId,
        String bookTitle,
        String status,
        Integer queuePosition,
        Long assignedCopyId,
        String assignedCopyBarcode,
        Instant reservedAt,
        Instant notifiedAt,
        Instant expiresAt
) {
}
