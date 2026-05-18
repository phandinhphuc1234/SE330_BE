package com.vn.dto.circulation.request;

import jakarta.validation.constraints.NotNull;

public record CreateHoldRequest(
        @NotNull(message = "bookId không được để trống")
        Long bookId
) {
}
