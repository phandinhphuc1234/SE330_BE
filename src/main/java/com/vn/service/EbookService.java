package com.vn.service;

import com.vn.dto.ebook.request.BorrowEbookRequest;
import com.vn.dto.ebook.response.EbookLoanResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface EbookService {

    /**
     * Member mượn ebook. Kiểm tra: ebook tồn tại, chưa đang mượn,
     * chưa vượt giới hạn concurrent, membership còn hiệu lực.
     */
    EbookLoanResponse borrowEbook(Long memberId, BorrowEbookRequest request);

    /**
     * Member trả sớm ebook đang mượn.
     */
    EbookLoanResponse returnEbook(Long memberId, Long loanId);

    /**
     * Gia hạn thêm 14 ngày (nếu còn quota).
     */
    EbookLoanResponse renewEbook(Long memberId, Long loanId);

    /**
     * Lấy danh sách ebook đang mượn (ACTIVE) của member.
     */
    Page<EbookLoanResponse> getMyEbookLoans(Long memberId, Pageable pageable);

    /**
     * Lấy lịch sử mượn ebook (tất cả status).
     */
    Page<EbookLoanResponse> getMyEbookHistory(Long memberId, Pageable pageable);

    /**
     * Scheduler job: expire các loan hết hạn.
     * @return số bản ghi đã expire
     */
    int expireOverdueLoans();
}
