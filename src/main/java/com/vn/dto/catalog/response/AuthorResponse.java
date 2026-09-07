package com.vn.dto.catalog.response;

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

