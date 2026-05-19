package com.vn.service.impl.circulation.reminder;

import java.time.Instant;

public record DueSoonReminderWindow(Instant start, Instant end) {
}
