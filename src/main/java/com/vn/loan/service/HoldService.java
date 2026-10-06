package com.vn.loan.service;

import com.vn.loan.dto.request.CreateHoldRequest;
import com.vn.loan.dto.response.BorrowResponse;
import com.vn.loan.dto.response.HoldResponse;
import com.vn.loan.enums.ReservationStatus;
import org.springframework.data.domain.Page;

public interface HoldService {

    HoldResponse createHold(Long memberId, CreateHoldRequest request);

    Page<HoldResponse> getMyHolds(Long memberId, ReservationStatus status, int page, int size);

    HoldResponse cancelHold(Long actorId, boolean staffActor, Long holdId);

    BorrowResponse checkoutHold(Long actorId, String idempotencyKey, Long holdId);
}
