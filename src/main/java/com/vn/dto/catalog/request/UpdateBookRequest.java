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

        @Size(max = 2048, message = "Link ảnh tối đa 2048 ký tự")
        String imageUrl,

        Long categoryId
) {
}

