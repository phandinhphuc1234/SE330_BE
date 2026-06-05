package com.vn.dto.catalog.response;

public record BookCoverImageResponse(
        String originalUrl,
        String thumbnailUrl,
        String detailUrl,
        String altText
) {
}
