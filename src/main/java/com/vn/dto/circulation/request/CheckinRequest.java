package com.vn.dto.circulation.request;

import jakarta.validation.constraints.NotBlank;

public record CheckinRequest(
        @NotBlank(message = "itemBarcode không được để trống")
        String itemBarcode,

        String returnCondition,

        String note
) {
}
