package com.vn.dto.catalog.request;

import jakarta.validation.constraints.NotNull;

import java.util.List;

public record UpdateBookAuthorsRequest(
        @NotNull(message = "Danh sách tác giả không được để trống")
        List<Long> authorIds
) {
}

