package com.vn.service;

import com.vn.dto.circulation.request.CreateHoldRequest;
import com.vn.dto.circulation.response.BorrowResponse;
import com.vn.dto.circulation.response.HoldResponse;
import com.vn.enums.ReservationStatus;
import org.springframework.data.domain.Page;

public interface HoldService {

    HoldResponse createHold(Long memberId, CreateHoldRequest request);

    Page<HoldResponse> getMyHolds(Long memberId, ReservationStatus status, int page, int size);

    HoldResponse cancelHold(Long actorId, boolean staffActor, Long holdId);

    BorrowResponse checkoutHold(Long actorId, String idempotencyKey, Long holdId);
}
