package com.vn.service.impl.circulation.autorenewal;

public record AutoRenewalJobSummary(
        int totalProcessed,
        int successCount,
        int failedCount
) {
}
