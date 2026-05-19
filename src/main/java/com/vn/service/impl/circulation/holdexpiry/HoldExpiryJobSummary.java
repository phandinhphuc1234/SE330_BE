package com.vn.service.impl.circulation.holdexpiry;

public record HoldExpiryJobSummary(
        int totalProcessed,
        int successCount,
        int failedCount
) {
}
