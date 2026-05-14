package com.vn.dto.catalog.response;

import com.vn.enums.BookCopyStatus;

import java.time.Instant;

public record BookCopyResponse(
        Long id,
        Long bookId,
        String barcode,
        BookCopyStatus status,
        String condition,
        String location,
        Instant createdAt,
        Instant updatedAt
) {
}

