package com.vn.service.impl.circulation.usecase;

import com.vn.dto.circulation.request.CheckoutRequest;
import com.vn.dto.circulation.response.BorrowResponse;
import com.vn.dto.circulation.response.CheckoutPreviewResponse;
import com.vn.dto.circulation.response.CirculationBlockResponse;
import com.vn.entity.Book;
import com.vn.entity.BookCopy;
import com.vn.entity.BorrowRecord;
import com.vn.entity.Member;
import com.vn.enums.BookCopyStatus;
import com.vn.enums.BorrowStatus;
import com.vn.logging.LogEvent;
import com.vn.logging.LogResult;
import com.vn.mapper.CirculationMapper;
import com.vn.repository.BookCopyRepository;
import com.vn.repository.BookRepository;
import com.vn.repository.BorrowRecordRepository;
import com.vn.service.impl.circulation.policy.CirculationPolicyService;
import com.vn.service.impl.circulation.policy.CirculationSettingService;
import com.vn.service.impl.circulation.support.CirculationLookupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class CheckoutUseCase {

    private final CirculationLookupService circulationLookupService;
    private final CirculationPolicyService circulationPolicyService;
    private final CirculationSettingService circulationSettingService;
    private final BorrowRecordRepository borrowRecordRepository;
    private final BookCopyRepository bookCopyRepository;
    private final BookRepository bookRepository;
    private final CirculationMapper circulationMapper;

    // Chức năng: kiểm tra trước một lượt mượn sách và trả về các lý do bị chặn nếu chưa đủ điều kiện.
    public CheckoutPreviewResponse previewCheckout(CheckoutRequest request) {
        Member member = circulationLookupService.findMemberOrNull(request.memberId());
        BookCopy copy = circulationLookupService.findCopyForPreview(request.itemBarcode());
        List<CirculationBlockResponse> reasons = circulationPolicyService.validateCheckout(member, copy);

        int borrowDays = circulationSettingService.getBorrowDaysDefault();
        int maxRenewals = circulationSettingService.getMaxRenewalsDefault();
        Instant dueDate = reasons.isEmpty()
                ? Instant.now().plus(borrowDays, ChronoUnit.DAYS)
                : null;
        Book book = copy == null ? null : copy.getBook();

        return new CheckoutPreviewResponse(
                reasons.isEmpty(),
                member == null ? request.memberId() : member.getId(),
                member == null ? null : member.getFullName(),
                member == null ? null : member.getEmail(),
                book == null ? null : book.getId(),
                book == null ? null : book.getTitle(),
                copy == null ? null : copy.getId(),
                copy == null ? request.itemBarcode() : copy.getBarcode(),
                copy == null ? null : copy.getStatus().name(),
                borrowDays,
                maxRenewals,
                dueDate,
                reasons
        );
    }

    // Chức năng: tạo lượt mượn sách sau khi request đã qua lớp idempotency.
    public BorrowResponse checkout(CheckoutRequest request) {
        Member member = circulationLookupService.getMember(request.memberId());
        BookCopy copy = circulationLookupService.getCopyByBarcode(request.itemBarcode());
        circulationPolicyService.assertCheckoutAllowed(member, copy);

        int borrowDays = circulationSettingService.getBorrowDaysDefault();
        int maxRenewals = circulationSettingService.getMaxRenewalsDefault();
        Instant now = Instant.now();

        BorrowRecord borrow = BorrowRecord.builder()
                .member(member)
                .bookCopy(copy)
                .borrowedAt(now)
                .dueDate(now.plus(borrowDays, ChronoUnit.DAYS))
                .status(BorrowStatus.BORROWED)
                .renewCount(0)
                .maxRenewalsAtCheckout(maxRenewals)
                .fineAmount(BigDecimal.ZERO)
                .build();

        // Cập nhật bằng delta để không phải đếm lại toàn bộ book_copies sau mỗi lượt mượn.
        copy.setStatus(BookCopyStatus.BORROWED);
        BorrowRecord savedBorrow = borrowRecordRepository.save(borrow);
        bookCopyRepository.save(copy);
        bookRepository.adjustCopyCounters(copy.getBook().getId(), 0, -1);

        log.info("eventType={} result={} memberId={} entityType=BORROW_RECORD entityId={} bookCopyId={}",
                LogEvent.BORROW_BOOK, LogResult.SUCCESS, member.getId(), savedBorrow.getId(), copy.getId());

        return circulationMapper.toBorrowResponse(savedBorrow);
    }
}
