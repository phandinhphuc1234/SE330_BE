package com.vn.ebook.service;

import com.vn.ebook.dto.request.EbookSemanticSearchRequest;
import com.vn.ebook.dto.response.EbookSemanticSearchResponse;

public interface EbookSemanticSearchService {

    EbookSemanticSearchResponse search(Long memberId,
                                       Long bookId,
                                       String rawReadingSessionToken,
                                       EbookSemanticSearchRequest request);
}
