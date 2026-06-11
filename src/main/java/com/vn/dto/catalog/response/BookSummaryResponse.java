package com.vn.dto.catalog.response;

import java.time.LocalDate;
import java.util.List;

public record BookSummaryResponse(
        Long id,
        String title,
        String isbn,
        LocalDate publishedDate,
        String language,
        String edition,
        Integer totalCopies,
        Integer availableCopies,
        CategoryResponse category,
        List<AuthorResponse> authors,
        String ebookUrl
) {
}

