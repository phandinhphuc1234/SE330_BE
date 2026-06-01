package com.vn.dto.staff.hold.response;

import java.time.Instant;

public record StaffHoldResponse(
        Long holdId,
        Long memberId,
        String memberName,
        String memberEmail,
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
