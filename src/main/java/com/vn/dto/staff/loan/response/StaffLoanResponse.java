package com.vn.dto.staff.loan.response;

import java.math.BigDecimal;
import java.time.Instant;

public record StaffLoanResponse(
        Long borrowId,
        Long memberId,
        String memberName,
        String memberEmail,
        Long bookId,
        String bookTitle,
        Long bookCopyId,
        String itemBarcode,
        String copyStatus,
        Instant borrowedAt,
        Instant dueDate,
        Instant returnedAt,
        String status,
        Integer renewCount,
        Integer maxRenewals,
        BigDecimal fineAmount,
        String fineStatus,
        boolean overdue,
        long daysOverdue
) {
}
