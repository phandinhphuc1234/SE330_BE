package com.vn.dto.circulation.request;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CheckoutRequest(
        @NotNull(message = "memberId không được để trống")
        Long memberId,

        @JsonAlias("barcode")
        @NotBlank(message = "itemBarcode không được để trống")
        String itemBarcode
) {
}
