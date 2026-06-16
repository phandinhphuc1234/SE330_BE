package com.vn.service;

import com.vn.dto.ebook.response.EbookLoanResponse;
import org.springframework.data.domain.Page;

public interface EbookLoanService {

    Page<EbookLoanResponse> getMyEbookLoans(Long memberId, boolean history, int page, int size);

    EbookLoanResponse borrowFreeEbook(Long memberId, Long bookId);
}
