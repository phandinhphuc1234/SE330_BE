package com.vn.ebook.dto.response;

public record EbookSemanticSearchCitationResponse(
        String documentId,
        Long bookId,
        Long ebookId,
        String chapterTitle,
        Integer chapterIndex,
        Integer pageStart,
        Integer pageEnd,
        Integer chunkIndex
) {
}
