package com.vn.service.impl.circulation.holdexpiry;

import com.vn.entity.Reservation;
import com.vn.enums.ReservationStatus;
import com.vn.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

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

    // Chức năng: tìm các hold quá hạn lấy sách và expire từng record trong transaction riêng.
    public HoldExpiryJobSummary expireReadyHolds() {
        Instant now = Instant.now();
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
