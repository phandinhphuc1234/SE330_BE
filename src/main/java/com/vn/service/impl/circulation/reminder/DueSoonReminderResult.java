package com.vn.service.impl.circulation.reminder;

public record DueSoonReminderResult(boolean created) {

    public static DueSoonReminderResult skipped() {
        return new DueSoonReminderResult(false);
    }

    public static DueSoonReminderResult enqueued() {
        return new DueSoonReminderResult(true);
    }
}
