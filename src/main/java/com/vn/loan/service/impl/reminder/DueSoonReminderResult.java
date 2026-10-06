package com.vn.loan.service.impl.reminder;

public record DueSoonReminderResult(boolean created) {

    public static DueSoonReminderResult skipped() {
        return new DueSoonReminderResult(false);
    }

    public static DueSoonReminderResult enqueued() {
        return new DueSoonReminderResult(true);
    }
}
