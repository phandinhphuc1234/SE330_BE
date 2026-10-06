package com.vn.ebook.dto.response;

public record EbookSemanticSearchHitResponse(
        String chunkId,
        double score,
        String text,
        EbookSemanticSearchCitationResponse citation
) {
}
