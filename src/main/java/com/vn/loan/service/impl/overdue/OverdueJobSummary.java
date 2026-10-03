package com.vn.loan.service.impl.overdue;

public record OverdueJobSummary(
        int totalProcessed,
        int successCount,
        int failedCount
) {
}
