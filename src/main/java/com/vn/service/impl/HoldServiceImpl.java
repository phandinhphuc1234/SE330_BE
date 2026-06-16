package com.vn.service.impl;

import com.vn.dto.circulation.request.CreateHoldRequest;
import com.vn.dto.circulation.response.BorrowResponse;
import com.vn.dto.circulation.response.HoldResponse;
import com.vn.entity.Book;
import com.vn.entity.BookCopy;
import com.vn.entity.BorrowRecord;
import com.vn.entity.Member;
import com.vn.entity.Reservation;
import com.vn.enums.BookCopyStatus;
import com.vn.enums.BorrowStatus;
import com.vn.enums.ReservationStatus;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.logging.LogEvent;
import com.vn.logging.LogResult;
import com.vn.mapper.CirculationMapper;
import com.vn.repository.BookRepository;
import com.vn.repository.BorrowRecordRepository;
import com.vn.repository.MemberRepository;
import com.vn.repository.ReservationRepository;
import com.vn.service.HoldService;
import com.vn.service.IdempotencyService;
import com.vn.service.impl.circulation.policy.CirculationPolicyService;
import com.vn.service.impl.circulation.policy.CirculationSettingService;
import com.vn.service.impl.circulation.hold.HoldQueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class HoldServiceImpl implements HoldService {

    private static final int MAX_PAGE_SIZE = 100;

    private final MemberRepository memberRepository;
    private final BookRepository bookRepository;
    private final ReservationRepository reservationRepository;
    private final BorrowRecordRepository borrowRecordRepository;
    private final CirculationPolicyService circulationPolicyService;
    private final CirculationSettingService circulationSettingService;
    private final HoldQueueService holdQueueService;
    private final CirculationMapper circulationMapper;
    private final IdempotencyService idempotencyService;

    // Chức năng: member đặt hold khi đầu sách đã hết bản AVAILABLE.
    @Override
    @Transactional
    public HoldResponse createHold(Long memberId, CreateHoldRequest request) {
        Member member = getMember(memberId);
        circulationPolicyService.assertBorrowerAccountAllowed(member);

        // Lock đầu sách trong lúc tính queuePosition vì đây là flow online có thể nhiều user đặt cùng lúc.
        Book book = bookRepository.findLockedByIdAndDeletedAtIsNull(request.bookId())
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));

        if (safeAvailableCopies(book) > 0) {
            throw new AppException(ErrorCode.BOOK_AVAILABLE_ON_SHELF);
        }
        if (reservationRepository.existsByMemberIdAndBookIdAndStatusIn(member.getId(), book.getId(), ReservationStatus.activeStatuses())) {
            throw new AppException(ErrorCode.HOLD_ALREADY_EXISTS);
        }
        // Tìm ra sách đầu của queue và cho người đó mượn sách
        int queuePosition = reservationRepository.findMaxQueuePositionByBookId(book.getId()) + 1;
        Instant now = Instant.now();
        Reservation reservation = Reservation.builder()
                .member(member)
                .book(book)
                .reservedAt(now)
                .expiresAt(now.plus(circulationSettingService.getHoldPickupDaysDefault(), ChronoUnit.DAYS))
                .status(ReservationStatus.WAITING)
                .queuePosition(queuePosition)
                .build();

        Reservation savedReservation = reservationRepository.save(reservation);
        log.info("eventType={} result={} memberId={} entityType=RESERVATION entityId={} bookId={} queuePosition={}",
                LogEvent.CREATE_HOLD, LogResult.SUCCESS, member.getId(), savedReservation.getId(), book.getId(), queuePosition);

        return circulationMapper.toHoldResponse(savedReservation);
    }

    // Chức năng: lấy danh sách hold của member hiện tại, có thể lọc theo status.
    @Override
    @Transactional(readOnly = true)
    public Page<HoldResponse> getMyHolds(Long memberId, ReservationStatus status, int page, int size) {
        Pageable pageable = buildPageable(page, size);
        Page<Reservation> holds = status == null
                ? reservationRepository.findByMemberIdOrderByReservedAtDesc(memberId, pageable)
                : reservationRepository.findByMemberIdAndStatusOrderByReservedAtDesc(memberId, status, pageable);
        return holds.map(circulationMapper::toHoldResponse);
    }

    // Chức năng: hủy hold; member chỉ được hủy hold của mình, staff/admin có thể hủy hộ.
    @Override
    @Transactional
    public HoldResponse cancelHold(Long actorId, boolean staffActor, Long holdId) {
        Reservation hold = getHold(holdId);
        if (!staffActor && !hold.getMember().getId().equals(actorId)) {
            throw new AppException(ErrorCode.ACCESS_DENIED);
        }
        if (!ReservationStatus.activeStatuses().contains(hold.getStatus())) {
            throw new AppException(ErrorCode.HOLD_NOT_ACTIVE);
        }

        BookCopy assignedCopy = hold.getAssignedCopy();
        hold.setStatus(ReservationStatus.CANCELLED);
        Reservation savedHold = reservationRepository.save(hold);

        // Nếu hold đã được gán copy trên hold shelf, copy đó phải chuyển cho người kế tiếp hoặc về AVAILABLE.
        if (assignedCopy != null && assignedCopy.getStatus() == BookCopyStatus.ON_HOLD_SHELF) {
            holdQueueService.reassignOrReleaseHeldCopy(assignedCopy);
        }

        log.info("eventType={} result={} memberId={} entityType=RESERVATION entityId={}",
                LogEvent.CANCEL_HOLD, LogResult.SUCCESS, hold.getMember().getId(), savedHold.getId());

        return circulationMapper.toHoldResponse(savedHold);
    }

    // Chức năng: staff checkout bản sách đang ON_HOLD_SHELF cho đúng member của hold.
    @Override
    public BorrowResponse checkoutHold(Long actorId, String idempotencyKey, Long holdId) {
        return idempotencyService.execute(
                actorId,
                "POST",
                "/api/staff/holds/{holdId}/checkout",
                idempotencyKey,
                holdId,
                BorrowResponse.class,
                () -> checkoutHoldInternal(holdId)
        );
    }

    // Chức năng: tạo BorrowRecord từ hold READY_FOR_PICKUP sau khi qua lớp idempotency.
    private BorrowResponse checkoutHoldInternal(Long holdId) {
        Reservation hold = getHold(holdId);
        validateHoldReadyForCheckout(hold);

        // Lock member trước khi tạo BorrowRecord từ hold để chia sẻ hạn mức với ebook loan.
        Member member = getLockedMember(hold.getMember().getId());
        BookCopy copy = hold.getAssignedCopy();
        circulationPolicyService.assertBorrowerAccountAllowed(member);
        circulationPolicyService.assertBorrowingCapacityAllowed(member);

        Instant now = Instant.now();
        BorrowRecord borrow = BorrowRecord.builder()
                .member(member)
                .bookCopy(copy)
                .borrowedAt(now)
                .dueDate(now.plus(circulationSettingService.getBorrowDaysDefault(), ChronoUnit.DAYS))
                .status(BorrowStatus.BORROWED)
                .renewCount(0)
                .maxRenewalsAtCheckout(circulationSettingService.getMaxRenewalsDefault())
                .fineAmount(BigDecimal.ZERO)
                .build();

        copy.setStatus(BookCopyStatus.BORROWED);
        hold.setStatus(ReservationStatus.FULFILLED);

        BorrowRecord savedBorrow = borrowRecordRepository.save(borrow);
        reservationRepository.save(hold);

        log.info("eventType={} result={} memberId={} entityType=BORROW_RECORD entityId={} holdId={} bookCopyId={}",
                LogEvent.CHECKOUT_HOLD, LogResult.SUCCESS, member.getId(), savedBorrow.getId(), hold.getId(), copy.getId());

        return circulationMapper.toBorrowResponse(savedBorrow);
    }

    // Chức năng: đảm bảo hold đã sẵn sàng, có assigned copy và copy đang nằm trên hold shelf.
    private void validateHoldReadyForCheckout(Reservation hold) {
        if (hold.getStatus() != ReservationStatus.READY_FOR_PICKUP) {
            throw new AppException(ErrorCode.HOLD_NOT_READY_FOR_PICKUP);
        }
        BookCopy copy = hold.getAssignedCopy();
        if (copy == null || copy.getStatus() != BookCopyStatus.ON_HOLD_SHELF) {
            throw new AppException(ErrorCode.HOLD_ASSIGNED_COPY_INVALID);
        }
    }

    // Chức năng: lấy member và chuẩn hóa lỗi not found.
    private Member getMember(Long memberId) {
        return memberRepository.findById(memberId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    // Chức năng: lock member khi checkout hold vì đây là lúc tạo borrow thật.
    private Member getLockedMember(Long memberId) {
        return memberRepository.findLockedById(memberId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    // Chức năng: lấy hold và chuẩn hóa lỗi not found.
    private Reservation getHold(Long holdId) {
        return reservationRepository.findById(holdId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    // Chức năng: tránh NullPointerException nếu dữ liệu cũ chưa có availableCopies.
    private int safeAvailableCopies(Book book) {
        return book.getAvailableCopies() == null ? 0 : book.getAvailableCopies();
    }

    // Chức năng: tạo Pageable và giới hạn page size cho API danh sách hold.
    private Pageable buildPageable(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
    }
}
