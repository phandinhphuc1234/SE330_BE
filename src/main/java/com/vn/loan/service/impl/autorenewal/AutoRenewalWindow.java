package com.vn.loan.service.impl.autorenewal;

import java.time.Instant;

public record AutoRenewalWindow(
        Instant start,
        Instant end
) {
}
