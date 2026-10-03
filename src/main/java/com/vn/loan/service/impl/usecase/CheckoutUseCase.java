package com.vn.loan.service.impl.usecase;

import com.vn.loan.dto.request.CheckoutRequest;
import com.vn.loan.dto.response.BorrowResponse;
import com.vn.loan.dto.response.CheckoutPreviewResponse;
import com.vn.loan.dto.response.CirculationBlockResponse;
import com.vn.book.entity.Book;
import com.vn.book.entity.BookCopy;
import com.vn.loan.entity.BorrowRecord;
import com.vn.member.entity.Member;
import com.vn.book.enums.BookCopyStatus;
import com.vn.loan.enums.BorrowStatus;
import com.vn.shared.logging.LogEvent;
import com.vn.shared.logging.LogResult;
import com.vn.loan.mapper.CirculationMapper;
import com.vn.book.repository.BookCopyRepository;
import com.vn.book.repository.BookRepository;
import com.vn.loan.repository.BorrowRecordRepository;
import com.vn.loan.service.impl.policy.CirculationPolicyService;
import com.vn.loan.service.impl.policy.CirculationSettingService;
import com.vn.loan.service.impl.support.CirculationLookupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
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
    private final Clock clock;

    // Chức năng: kiểm tra trước một lượt mượn sách và trả về các lý do bị chặn nếu chưa đủ điều kiện.
    public CheckoutPreviewResponse previewCheckout(CheckoutRequest request) {
        Member member = circulationLookupService.findMemberOrNull(request.memberId());
        BookCopy copy = circulationLookupService.findCopyForPreview(request.itemBarcode());
        List<CirculationBlockResponse> reasons = circulationPolicyService.validateCheckout(member, copy);

        int borrowDays = circulationSettingService.getBorrowDaysDefault();
        int maxRenewals = circulationSettingService.getMaxRenewalsDefault();
        Instant dueDate = reasons.isEmpty()
                ? clock.instant().plus(borrowDays, ChronoUnit.DAYS)
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
        // Lock member để hạn mức tổng media không bị vượt khi checkout vật lý và ebook loan chạy đồng thời.
        Member member = circulationLookupService.getLockedMember(request.memberId());
        BookCopy copy = circulationLookupService.getCopyByBarcode(request.itemBarcode());
        circulationPolicyService.assertCheckoutAllowed(member, copy);

        int borrowDays = circulationSettingService.getBorrowDaysDefault();
        int maxRenewals = circulationSettingService.getMaxRenewalsDefault();
        Instant now = clock.instant();

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
