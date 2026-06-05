package com.vn.dto.catalog.response;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record BookDetailResponse(
        Long id,
        String title,
        String isbn,
        LocalDate publishedDate,
        String language,
        String edition,
        BookCoverImageResponse coverImage,
        Integer totalCopies,
        Integer availableCopies,
        CategoryResponse category,
        List<AuthorResponse> authors,
        Instant createdAt,
        Instant updatedAt
) {
}

