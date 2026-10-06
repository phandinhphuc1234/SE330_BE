package com.vn.ebook.service;

import com.vn.ebook.dto.request.EbookAskRequest;
import com.vn.ebook.dto.response.EbookAnswerResponse;

public interface EbookQuestionAnswerService {

    EbookAnswerResponse answer(Long memberId,
                               Long bookId,
                               String rawReadingSessionToken,
                               EbookAskRequest request);
}
