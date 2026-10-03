package com.vn.ebook.service;

import com.vn.ebook.dto.response.EbookLoanResponse;
import org.springframework.data.domain.Page;

public interface EbookLoanService {

    Page<EbookLoanResponse> getMyEbookLoans(Long memberId, boolean history, int page, int size);

    EbookLoanResponse borrowFreeEbook(Long memberId, Long bookId);
}
