package com.vn.service.impl.circulation.reminder;

public record DueSoonReminderJobSummary(int totalProcessed, int successCount, int failedCount) {
}
