package com.vn.dto.circulation.response;

import java.time.Instant;

public record RenewBorrowResponse(
        Long borrowId,
        Instant oldDueDate,
        Instant newDueDate,
        Integer renewCount,
        Integer maxRenewals
) {
}
