package com.vn.dto.payment.response;

import java.time.Instant;

public record PaymentDashboardSummaryResponse(
        long totalPayments,
        long successPayments,
        long pendingPayments,
        long failedPayments,
        long totalRevenue,
        long todayRevenue,
        long todaySuccessPayments,
        String currency,
        Instant generatedAt
) {
}
