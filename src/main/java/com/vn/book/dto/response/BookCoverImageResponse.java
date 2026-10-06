package com.vn.book.dto.response;

public record BookCoverImageResponse(
        String originalUrl,
        String thumbnailUrl,
        String detailUrl,
        String altText
) {
}
