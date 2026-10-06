package com.vn.book.dto.response;

public record BookCoverManagementResponse(
        Long id,
        Long bookId,
        String provider,
        String publicId,
        String originalUrl,
        String thumbnailUrl,
        String detailUrl,
        String altText,
        Boolean isPrimary,
        String status,
        String oldImageStatus
) {
}
