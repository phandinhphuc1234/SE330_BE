package com.vn.loan.service.impl.reminder;

import java.time.Instant;

public record DueSoonReminderWindow(Instant start, Instant end) {
}
