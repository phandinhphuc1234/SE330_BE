package com.vn.testsupport;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

public final class TestTime {

    public static final Instant NOW = Instant.parse("2026-06-15T03:00:00Z");
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    public static final Clock CLOCK = Clock.fixed(NOW, BUSINESS_ZONE);

    private TestTime() {
    }
}
