package com.vn.ebook.service;

import com.vn.ebook.enums.EbookIngestionStatus;

import java.time.Instant;

public record EbookReadingAccess(
        Long readingSessionId,
        Long bookId,
        Long ebookId,
        Long loanId,
        Instant loanExpiresAt,
        EbookIngestionStatus ingestionStatus
) {
}
