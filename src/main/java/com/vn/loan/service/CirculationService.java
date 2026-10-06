package com.vn.loan.service;

import com.vn.loan.dto.request.CheckinRequest;
import com.vn.loan.dto.request.CheckoutRequest;
import com.vn.loan.dto.request.RenewBorrowRequest;
import com.vn.loan.dto.response.BorrowResponse;
import com.vn.loan.dto.response.CheckinResponse;
import com.vn.loan.dto.response.CheckoutPreviewResponse;
import com.vn.loan.dto.response.RenewBorrowResponse;
import com.vn.loan.dto.staff.loan.response.StaffLoanResponse;
import org.springframework.data.domain.Page;

public interface CirculationService {

    CheckoutPreviewResponse previewCheckout(CheckoutRequest request);

    BorrowResponse checkout(Long actorId, String idempotencyKey, CheckoutRequest request);

    CheckinResponse checkin(Long actorId, String idempotencyKey, CheckinRequest request);

    RenewBorrowResponse renewMyBorrow(Long actorId, String idempotencyKey, Long borrowId, RenewBorrowRequest request);

    RenewBorrowResponse staffRenewBorrow(Long actorId, String idempotencyKey, Long borrowId, RenewBorrowRequest request);

    Page<StaffLoanResponse> getMyActiveBorrows(Long memberId, int page, int size);

    Page<StaffLoanResponse> getMyBorrowHistory(Long memberId, int page, int size);
}
