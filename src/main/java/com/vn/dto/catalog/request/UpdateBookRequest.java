package com.vn.dto.catalog.request;

import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record UpdateBookRequest(
        @Size(max = 255, message = "Tên sách tối đa 255 ký tự")
        String title,

        LocalDate publishedDate,

        @Size(max = 10, message = "Ngôn ngữ tối đa 10 ký tự")
        String language,

        @Size(max = 50, message = "Phiên bản tối đa 50 ký tự")
        String edition,

        Long categoryId
) {
}

