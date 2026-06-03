package com.vn.dto.catalog.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

public record CreateBookRequest(
        @NotBlank(message = "Tên sách không được để trống")
        @Size(max = 255, message = "Tên sách tối đa 255 ký tự")
        String title,

        @NotBlank(message = "ISBN không được để trống")
        @Size(max = 20, message = "ISBN tối đa 20 ký tự")
        String isbn,

        LocalDate publishedDate,

        @Size(max = 10, message = "Ngôn ngữ tối đa 10 ký tự")
        String language,

        @Size(max = 50, message = "Phiên bản tối đa 50 ký tự")
        String edition,

        @Size(max = 2048, message = "Link ảnh tối đa 2048 ký tự")
        String imageUrl,

        Long categoryId,

        List<Long> authorIds
) {
}

