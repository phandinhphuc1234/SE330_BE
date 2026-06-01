package com.vn.dto.staff.member.response;

import java.math.BigDecimal;
import java.time.Instant;

public record StaffMemberDetailResponse(
        Long memberId,
        String fullName,
        String email,
        String phone,
        String role,
        String status,
        Integer maxBorrowLimit,
        Instant membershipExpiresAt,
        long activeLoansCount,
        long openLoansCount,
        long overdueLoansCount,
        long borrowHistoryCount,
        long activeHoldsCount,
        BigDecimal unpaidFineTotal,
        Instant createdAt,
        Instant updatedAt
) {
}
