package com.vn.dto.catalog.request;

import jakarta.validation.constraints.Size;

public record UpdateAuthorRequest(
        @Size(max = 100, message = "Tên tác giả tối đa 100 ký tự")
        String name,

        String bio,

        @Size(max = 2048, message = "URL ảnh tác giả tối đa 2048 ký tự")
        String imageUrl
) {
}

