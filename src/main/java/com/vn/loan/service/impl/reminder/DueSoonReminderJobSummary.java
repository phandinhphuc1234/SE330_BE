package com.vn.loan.service.impl.reminder;

public record DueSoonReminderJobSummary(int totalProcessed, int successCount, int failedCount) {
}
