package com.vn.dto.circulation.response;

import java.time.Instant;
import java.util.List;

public record CheckoutPreviewResponse(
        boolean allowed,
        Long memberId,
        Long bookCopyId,
        Integer loanPeriodDays,
        Instant dueDate,
        List<CirculationBlockResponse> reasons
) {
}
