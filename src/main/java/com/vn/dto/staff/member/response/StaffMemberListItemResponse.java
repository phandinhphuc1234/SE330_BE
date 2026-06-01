package com.vn.dto.staff.member.response;

import java.math.BigDecimal;
import java.time.Instant;

public record StaffMemberListItemResponse(
        Long memberId,
        String fullName,
        String email,
        String phone,
        String role,
        String status,
        Integer maxBorrowLimit,
        Instant membershipExpiresAt,
        long activeLoansCount,
        long overdueLoansCount,
        long activeHoldsCount,
        BigDecimal unpaidFineTotal,
        Instant createdAt
) {
}
