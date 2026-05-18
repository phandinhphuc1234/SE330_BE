package com.vn.service;

import com.vn.dto.circulation.request.CheckinRequest;
import com.vn.dto.circulation.request.CheckoutRequest;
import com.vn.dto.circulation.request.RenewBorrowRequest;
import com.vn.dto.circulation.response.BorrowResponse;
import com.vn.dto.circulation.response.CheckinResponse;
import com.vn.dto.circulation.response.CheckoutPreviewResponse;
import com.vn.dto.circulation.response.RenewBorrowResponse;
import org.springframework.data.domain.Page;

public interface CirculationService {

    CheckoutPreviewResponse previewCheckout(CheckoutRequest request);

    BorrowResponse checkout(Long actorId, String idempotencyKey, CheckoutRequest request);

    CheckinResponse checkin(Long actorId, String idempotencyKey, CheckinRequest request);

    RenewBorrowResponse renewMyBorrow(Long actorId, String idempotencyKey, Long borrowId, RenewBorrowRequest request);

    RenewBorrowResponse staffRenewBorrow(Long actorId, String idempotencyKey, Long borrowId, RenewBorrowRequest request);

    Page<BorrowResponse> getMyActiveBorrows(Long memberId, int page, int size);

    Page<BorrowResponse> getMyBorrowHistory(Long memberId, int page, int size);
}
