package com.vn.dto.catalog.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
// Tạo ra request
public record CreateBookCopyRequest(
        @NotBlank(message = "Barcode không được để trống")
        @Size(max = 100, message = "Barcode tối đa 100 ký tự")
        String barcode,

        @Size(max = 50, message = "Tình trạng bản sao tối đa 50 ký tự")
        String condition,

        @Size(max = 100, message = "Vị trí bản sao tối đa 100 ký tự")
        String location
) {
}

