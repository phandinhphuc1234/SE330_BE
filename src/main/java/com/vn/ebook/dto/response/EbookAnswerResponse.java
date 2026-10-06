package com.vn.ebook.dto.response;

import java.util.List;

public record EbookAnswerResponse(
        Long bookId,
        Long ebookId,
        String answer,
        boolean grounded,
        boolean abstained,
        String reason,
        List<EbookAnswerCitationResponse> citations,
        String promptVersion
) {
}
