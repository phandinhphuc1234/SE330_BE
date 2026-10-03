package com.vn.loan.service.impl.holdexpiry;

import com.vn.loan.entity.Reservation;
import com.vn.loan.enums.ReservationStatus;
import com.vn.loan.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class HoldExpiryService {

    private static final int MAX_ITEMS_PER_RUN = 500;
    private static final List<ReservationStatus> EXPIRABLE_STATUSES = List.of(
            ReservationStatus.NOTIFIED,
            ReservationStatus.READY_FOR_PICKUP
    );

    private final ReservationRepository reservationRepository;
    private final HoldExpiryProcessor holdExpiryProcessor;
    private final Clock clock;

    // Chức năng: tìm các hold quá hạn lấy sách và expire từng record trong transaction riêng.
    public HoldExpiryJobSummary expireReadyHolds() {
        Instant now = clock.instant();
        Page<Reservation> candidates = reservationRepository.findExpiredReadyHoldCandidates(
                EXPIRABLE_STATUSES,
                now,
                PageRequest.of(0, MAX_ITEMS_PER_RUN)
        );

        int successCount = 0;
        int failedCount = 0;
        for (Reservation hold : candidates.getContent()) {
            HoldExpiryResult result = holdExpiryProcessor.expireOne(hold.getId(), now);
            if (result.success()) {
                successCount++;
            } else {
                failedCount++;
            }
        }

        return new HoldExpiryJobSummary(candidates.getNumberOfElements(), successCount, failedCount);
    }
}
