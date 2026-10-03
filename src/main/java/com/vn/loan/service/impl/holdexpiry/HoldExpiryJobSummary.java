package com.vn.loan.service.impl.holdexpiry;

public record HoldExpiryJobSummary(
        int totalProcessed,
        int successCount,
        int failedCount
) {
}
