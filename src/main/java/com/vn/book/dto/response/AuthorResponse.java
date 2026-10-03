package com.vn.book.dto.response;

import java.time.Instant;

public record AuthorResponse(
        Long id,
        String name,
        String bio,
        String imageUrl,
        String imageProvider,
        Instant createdAt,
        Instant updatedAt
) {
}

