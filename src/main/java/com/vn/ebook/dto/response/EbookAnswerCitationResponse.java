package com.vn.ebook.dto.response;

public record EbookAnswerCitationResponse(
        String documentId,
        Long bookId,
        Long ebookId,
        String chapterTitle,
        Integer chapterIndex,
        Integer pageStart,
        Integer pageEnd,
        Integer chunkIndex,
        String chunkId,
        String excerpt,
        double score
) {
}
