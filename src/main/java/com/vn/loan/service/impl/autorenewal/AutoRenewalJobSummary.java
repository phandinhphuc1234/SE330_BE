package com.vn.loan.service.impl.autorenewal;

public record AutoRenewalJobSummary(
        int totalProcessed,
        int successCount,
        int failedCount
) {
}
