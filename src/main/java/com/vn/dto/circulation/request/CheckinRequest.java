package com.vn.dto.circulation.request;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;

public record CheckinRequest(
        @JsonAlias("barcode")
        @NotBlank(message = "itemBarcode không được để trống")
        String itemBarcode,

        String returnCondition,

        String note
) {
}
