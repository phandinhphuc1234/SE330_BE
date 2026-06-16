package com.vn.dto.ebook.response;

import java.time.Instant;

public record EbookLoanResponse(
        Long loanId,
        Long memberId,
        Long bookId,
        String bookTitle,
        Long bookEbookId,
        Long paymentId,
        String status,
        Instant borrowedAt,
        Instant expiredAt,
        Instant returnedAt
) {
}
