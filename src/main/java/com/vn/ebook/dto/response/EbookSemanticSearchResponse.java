package com.vn.ebook.dto.response;

import java.util.List;

public record EbookSemanticSearchResponse(
        Long bookId,
        Long ebookId,
        int resultCount,
        List<EbookSemanticSearchHitResponse> results
) {
}
