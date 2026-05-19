package com.vn.service.impl.circulation.holdexpiry;

import com.vn.entity.BookCopy;
import com.vn.entity.Reservation;
import com.vn.enums.BookCopyStatus;
import com.vn.enums.ReservationStatus;
import com.vn.logging.LogEvent;
import com.vn.logging.LogResult;
import com.vn.repository.ReservationRepository;
import com.vn.service.impl.circulation.hold.HoldQueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class HoldExpiryProcessor {

    private static final Set<ReservationStatus> EXPIRABLE_STATUSES = Set.of(
            ReservationStatus.NOTIFIED,
            ReservationStatus.READY_FOR_PICKUP
    );

    private final ReservationRepository reservationRepository;
    private final HoldQueueService holdQueueService;

    // Chức năng: expire một hold quá hạn lấy sách, rồi chuyển copy cho người kế tiếp hoặc trả về kệ.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public HoldExpiryResult expireOne(Long holdId, Instant now) {
        Reservation hold = reservationRepository.findLockedForExpiryById(holdId).orElse(null);
        if (hold == null || !isStillExpiredReadyHold(hold, now)) {
            return HoldExpiryResult.skipped();
        }

        BookCopy assignedCopy = hold.getAssignedCopy();
        hold.setStatus(ReservationStatus.EXPIRED);

        // Flush trước khi gán copy cho hold kế tiếp để unique index active assigned_copy không bị đụng.
        reservationRepository.saveAndFlush(hold);

        if (assignedCopy != null && assignedCopy.getStatus() == BookCopyStatus.ON_HOLD_SHELF) {
            holdQueueService.reassignOrReleaseHeldCopy(assignedCopy);
        }

        log.info("eventType={} result={} memberId={} entityType=RESERVATION entityId={} bookCopyId={}",
                LogEvent.EXPIRE_READY_HOLD,
                LogResult.SUCCESS,
                hold.getMember().getId(),
                hold.getId(),
                assignedCopy == null ? null : assignedCopy.getId());

        return HoldExpiryResult.expired();
    }

    private boolean isStillExpiredReadyHold(Reservation hold, Instant now) {
        return EXPIRABLE_STATUSES.contains(hold.getStatus())
                && hold.getExpiresAt() != null
                && hold.getExpiresAt().isBefore(now);
    }
}
