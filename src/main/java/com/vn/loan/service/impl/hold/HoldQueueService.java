package com.vn.loan.service.impl.hold;

import com.vn.book.entity.BookCopy;
import com.vn.loan.entity.Reservation;
import com.vn.book.enums.BookCopyStatus;
import com.vn.loan.enums.ReservationStatus;
import com.vn.shared.logging.LogEvent;
import com.vn.shared.logging.LogResult;
import com.vn.book.repository.BookCopyRepository;
import com.vn.book.repository.BookRepository;
import com.vn.loan.repository.ReservationRepository;
import com.vn.loan.service.impl.policy.CirculationSettingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class HoldQueueService {

    private final ReservationRepository reservationRepository;
    private final BookCopyRepository bookCopyRepository;
    private final BookRepository bookRepository;
    private final CirculationSettingService circulationSettingService;
    private final Clock clock;

    // Chức năng: khi một copy được trả về, ưu tiên gán copy đó cho người đầu hàng đợi hold.
    public Optional<Reservation> assignReturnedCopyToNextHold(BookCopy copy) {
        return assignCopyToNextWaitingHold(copy, false);
    }

    // Chức năng: khi hold READY bị hủy/hết hạn, chuyển copy cho hold kế tiếp hoặc trả copy về kệ.
    public Optional<Reservation> reassignOrReleaseHeldCopy(BookCopy copy) {
        return assignCopyToNextWaitingHold(copy, true);
    }

    // Chức năng: tìm người đầu queue và chuyển copy sang ON_HOLD_SHELF; nếu không còn ai chờ thì có thể release copy.
    private Optional<Reservation> assignCopyToNextWaitingHold(BookCopy copy, boolean releaseIfNoWaitingHold) {
        Optional<Reservation> nextHold = findNextWaitingHold(copy);
        if (nextHold.isEmpty()) {
            if (releaseIfNoWaitingHold) {
                releaseCopyToShelf(copy);
            }
            return Optional.empty();
        }

        Reservation hold = nextHold.get();
        Instant now = clock.instant();
        int pickupDays = circulationSettingService.getHoldPickupDaysDefault();

        // Đây là điểm handoff chính: copy không về AVAILABLE mà được giữ riêng cho hold đầu queue.
        hold.setStatus(ReservationStatus.READY_FOR_PICKUP);
        hold.setAssignedCopy(copy);
        hold.setNotifiedAt(now);
        hold.setExpiresAt(now.plus(pickupDays, ChronoUnit.DAYS));
        copy.setStatus(BookCopyStatus.ON_HOLD_SHELF);

        Reservation savedHold = reservationRepository.save(hold);
        bookCopyRepository.save(copy);

        log.info("eventType={} result={} memberId={} entityType=RESERVATION entityId={} bookCopyId={}",
                LogEvent.HOLD_READY_FOR_PICKUP, LogResult.SUCCESS, hold.getMember().getId(), savedHold.getId(), copy.getId());

        return Optional.of(savedHold);
    }

    // Chức năng: lấy hold WAITING đầu tiên theo queuePosition/reservedAt.
    private Optional<Reservation> findNextWaitingHold(BookCopy copy) {
        return reservationRepository
                .findQueueHead(copy.getBook().getId(), ReservationStatus.WAITING, PageRequest.of(0, 1))
                .stream()
                .findFirst();
    }

    // Chức năng: đưa copy trở lại kệ khi không còn hold đang chờ và tăng lại availableCopies.
    private void releaseCopyToShelf(BookCopy copy) {
        copy.setStatus(BookCopyStatus.AVAILABLE);
        bookCopyRepository.save(copy);
        bookRepository.adjustCopyCounters(copy.getBook().getId(), 0, 1);
    }
}
