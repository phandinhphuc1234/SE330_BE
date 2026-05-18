package com.vn.service.impl.circulation.overdue;

public record OverdueJobSummary(
        int totalProcessed,
        int successCount,
        int failedCount
) {
}
