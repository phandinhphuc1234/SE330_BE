package com.vn.service.impl.circulation.autorenewal;

import java.time.Instant;

public record AutoRenewalWindow(
        Instant start,
        Instant end
) {
}
