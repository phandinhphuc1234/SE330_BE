package com.vn.service.impl.circulation.usecase;

import com.vn.dto.circulation.request.CheckinRequest;
import com.vn.dto.circulation.response.CheckinResponse;
import com.vn.entity.BookCopy;
import com.vn.entity.BorrowRecord;
import com.vn.entity.Reservation;
import com.vn.enums.BookCopyStatus;
import com.vn.enums.BorrowStatus;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.logging.LogEvent;
import com.vn.logging.LogResult;
import com.vn.mapper.CirculationMapper;
import com.vn.repository.BookCopyRepository;
import com.vn.repository.BookRepository;
import com.vn.repository.BorrowRecordRepository;
import com.vn.service.impl.circulation.hold.HoldQueueService;
import com.vn.service.impl.circulation.support.CirculationFineService;
import com.vn.service.impl.circulation.support.CirculationLookupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
@RequiredArgsConstructor
@Slf4j
public class CheckinUseCase {

    private final CirculationLookupService circulationLookupService;
    private final CirculationFineService circulationFineService;
    private final BorrowRecordRepository borrowRecordRepository;
    private final BookCopyRepository bookCopyRepository;
    private final BookRepository bookRepository;
    private final CirculationMapper circulationMapper;
    private final HoldQueueService holdQueueService;

    // Chức năng: đóng lượt mượn đang mở của một bản sách khi user trả sách.
    public CheckinResponse checkin(CheckinRequest request) {
        BookCopy copy = circulationLookupService.getCopyByBarcode(request.itemBarcode());
        BorrowRecord borrow = borrowRecordRepository
                .findFirstByBookCopyIdAndStatusInOrderByBorrowedAtDesc(copy.getId(), BorrowStatus.openStatuses())
                .orElseThrow(() -> new AppException(ErrorCode.ACTIVE_BORROW_NOT_FOUND));

        Instant returnedAt = Instant.now();
        long overdueDays = circulationFineService.calculateOverdueDays(borrow.getDueDate(), returnedAt);
        if (overdueDays > 0) {
            circulationFineService.applyOverdueFine(borrow, overdueDays, returnedAt);
        }

        borrow.setReturnedAt(returnedAt);
        borrow.setStatus(BorrowStatus.RETURNED);

        Reservation nextHold = null;

        // Bản sách hỏng không quay lại kho sẵn sàng; bản tốt sẽ ưu tiên hold queue trước khi về kệ.
        if ("DAMAGED".equalsIgnoreCase(normalizeOptional(request.returnCondition(), "GOOD"))) {
            copy.setStatus(BookCopyStatus.DAMAGED);
        } else {
            nextHold = holdQueueService.assignReturnedCopyToNextHold(copy).orElse(null);
            if (nextHold == null) {
                copy.setStatus(BookCopyStatus.AVAILABLE);
                bookRepository.adjustCopyCounters(copy.getBook().getId(), 0, 1);
            }
        }

        BorrowRecord savedBorrow = borrowRecordRepository.save(borrow);
        bookCopyRepository.save(copy);

        log.info("eventType={} result={} memberId={} entityType=BORROW_RECORD entityId={} bookCopyId={} overdueDays={}",
                LogEvent.RETURN_BOOK, LogResult.SUCCESS, borrow.getMember().getId(), savedBorrow.getId(), copy.getId(), overdueDays);

        return circulationMapper.toCheckinResponse(savedBorrow, overdueDays, nextHold);
    }

    // Chức năng: chuẩn hóa chuỗi tùy chọn và trả default nếu user không truyền giá trị.
    private String normalizeOptional(String value, String defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        String normalized = value.trim();
        return normalized.isBlank() ? defaultValue : normalized;
    }
}
