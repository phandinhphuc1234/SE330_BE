package com.vn.dto.catalog.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateCategoryRequest(
        @NotBlank(message = "Tên thể loại không được để trống")
        @Size(max = 50, message = "Tên thể loại tối đa 50 ký tự")
        String name,

        @Size(max = 255, message = "Mô tả thể loại tối đa 255 ký tự")
        String description
) {
}

