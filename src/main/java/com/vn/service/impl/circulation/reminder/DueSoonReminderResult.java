package com.vn.service.impl.circulation.reminder;

import java.time.Instant;

public record DueSoonReminderResult(
        boolean created,
        Long memberId,
        String toEmail,
        String fullName,
        String bookTitle,
        String barcode,
        Instant dueDate
) {

    public static DueSoonReminderResult skipped() {
        return new DueSoonReminderResult(false, null, null, null, null, null, null);
    }

    public static DueSoonReminderResult created(Long memberId,
                                                String toEmail,
                                                String fullName,
                                                String bookTitle,
                                                String barcode,
                                                Instant dueDate) {
        return new DueSoonReminderResult(true, memberId, toEmail, fullName, bookTitle, barcode, dueDate);
    }
}
