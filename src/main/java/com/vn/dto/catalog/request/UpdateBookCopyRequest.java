package com.vn.dto.catalog.request;

import jakarta.validation.constraints.Size;

public record UpdateBookCopyRequest(
        @Size(max = 50, message = "Tình trạng bản sao tối đa 50 ký tự")
        String condition,

        @Size(max = 100, message = "Vị trí bản sao tối đa 100 ký tự")
        String location
) {
}

