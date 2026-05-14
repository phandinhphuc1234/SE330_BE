package com.vn.dto.catalog.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;
// Request tạo danh sách các sách từ list được parse từ file CSV
public record BulkCreateBookCopiesRequest(
        @NotEmpty(message = "Danh sách bản sao sách không được để trống")
        List<@Valid CreateBookCopyRequest> copies
) {
}

