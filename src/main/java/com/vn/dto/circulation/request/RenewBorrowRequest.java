package com.vn.dto.circulation.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record RenewBorrowRequest(
        @Min(value = 1, message = "requestedDays phải lớn hơn 0")
        @Max(value = 30, message = "requestedDays tối đa là 30")
        Integer requestedDays
) {
}
