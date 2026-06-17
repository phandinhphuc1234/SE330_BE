package com.vn.service.payment.business;

import com.vn.entity.BookEbook;
import com.vn.entity.EbookLoan;
import com.vn.entity.Member;
import com.vn.entity.PaymentTransaction;
import com.vn.enums.BookEbookStatus;
import com.vn.enums.EbookLoanStatus;
import com.vn.enums.EbookAccessType;
import com.vn.enums.PaymentPurpose;
import com.vn.enums.PaymentTargetType;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.repository.BookEbookRepository;
import com.vn.repository.EbookLoanRepository;
import com.vn.service.borrow.MediaBorrowLimitService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class EbookPaymentApplier implements PaymentBusinessApplier {

    private final BookEbookRepository bookEbookRepository;
    private final EbookLoanRepository ebookLoanRepository;
    private final MediaBorrowLimitService mediaBorrowLimitService;

    @Override
    public boolean supports(PaymentPurpose purpose, PaymentTargetType targetType) {
        return purpose == PaymentPurpose.EBOOK_PAYMENT && targetType == PaymentTargetType.BOOK_EBOOK;
    }

    @Override
    public PayableTarget validatePayableTarget(Long memberId, Long targetId) {
        // Lớp này chỉ validate/tính tiền ebook; không tạo loan và không gọi provider.
        BookEbook ebook = bookEbookRepository.findById(targetId)
                .orElseThrow(() -> new AppException(ErrorCode.EBOOK_NOT_FOUND));

        if (ebook.getStatus() != BookEbookStatus.ACTIVE) {
            throw new AppException(ErrorCode.EBOOK_NOT_AVAILABLE);
        }
        if (ebook.getAccessType() != EbookAccessType.PAID) {
            throw new AppException(ErrorCode.EBOOK_DOES_NOT_REQUIRE_PAYMENT);
        }

        Long amount = toWholeVndAmount(ebook.getAccessFee());
        if (amount <= 0) {
            throw new AppException(ErrorCode.EBOOK_DOES_NOT_REQUIRE_PAYMENT);
        }
        validateLoanAvailabilityForPaymentCreate(memberId, ebook);

        return new PayableTarget(
                PaymentPurpose.EBOOK_PAYMENT,
                PaymentTargetType.BOOK_EBOOK,
                ebook.getId(),
                amount,
                ebook.getCurrency()
        );
    }

    @Override
    public void applySuccess(PaymentTransaction payment) {
        // IPN có thể retry nên applySuccess phải idempotent theo payment_id.
        if (payment == null || payment.getId() == null || ebookLoanRepository.existsByPaymentId(payment.getId())) {
            return;
        }

        // Lock member trước, sau đó mới lock ebook để mọi flow tạo loan dùng cùng thứ tự lock.
        Member lockedMember = mediaBorrowLimitService.lockMember(payment.getMemberId());
        BookEbook ebook = bookEbookRepository.findLockedById(payment.getTargetId())
                .orElseThrow(() -> new AppException(ErrorCode.EBOOK_NOT_FOUND));

        if (ebook.getStatus() != BookEbookStatus.ACTIVE) {
            markFulfillment(payment, "EBOOK_NOT_AVAILABLE", "Ebook is not active when payment succeeded");
            return;
        }

        Instant now = Instant.now();
        if (ebookLoanRepository.existsByMemberIdAndBookEbookIdAndStatusAndExpiredAtAfter(
                payment.getMemberId(), ebook.getId(), EbookLoanStatus.ACTIVE, now)) {
            markFulfillment(payment, "ALREADY_HAS_ACTIVE_LOAN", "Member already has an active ebook loan");
            return;
        }
        try {
            // Đếm cả sách vật lý + ebook để không vượt maxBorrowLimit sau khi thanh toán.
            mediaBorrowLimitService.assertCanBorrowMore(lockedMember);
        } catch (AppException ex) {
            if (ErrorCode.BORROW_LIMIT_EXCEEDED.getCode().equals(ex.getCode())) {
                markFulfillment(payment, "BORROW_LIMIT_EXCEEDED", "Payment succeeded but member reached media borrow limit");
                return;
            }
            throw ex;
        }

        long activeLoanCount = ebookLoanRepository.countByBookEbookIdAndStatusAndExpiredAtAfter(
                ebook.getId(), EbookLoanStatus.ACTIVE, now);
        if (activeLoanCount >= ebook.getMaxConcurrentLoans()) {
            markFulfillment(payment, "LICENSE_UNAVAILABLE", "Payment succeeded but no ebook license was available");
            return;
        }

        EbookLoan loan = new EbookLoan();
        loan.setMemberId(payment.getMemberId());
        loan.setBookId(ebook.getBook().getId());
        loan.setBookEbookId(ebook.getId());
        loan.setPaymentId(payment.getId());
        loan.setStatus(EbookLoanStatus.ACTIVE);
        loan.setBorrowedAt(now);
        loan.setExpiredAt(now.plus(ebook.getLoanDurationDays(), ChronoUnit.DAYS));
        ebookLoanRepository.save(loan);

        markFulfillment(payment, "FULFILLED", "Ebook loan created");
    }

    private void validateLoanAvailabilityForPaymentCreate(Long memberId, BookEbook ebook) {
        Instant now = Instant.now();
        if (ebookLoanRepository.existsByMemberIdAndBookEbookIdAndStatusAndExpiredAtAfter(
                memberId, ebook.getId(), EbookLoanStatus.ACTIVE, now)) {
            throw new AppException(ErrorCode.EBOOK_ALREADY_BORROWED);
        }
        // Payment create cũng chặn nếu member đã đạt hạn mức tổng media.
        mediaBorrowLimitService.assertCanBorrowMore(memberId);

        long activeLoanCount = ebookLoanRepository.countByBookEbookIdAndStatusAndExpiredAtAfter(
                ebook.getId(), EbookLoanStatus.ACTIVE, now);
        if (activeLoanCount >= ebook.getMaxConcurrentLoans()) {
            throw new AppException(ErrorCode.EBOOK_LICENSE_NOT_AVAILABLE);
        }
    }

    private void markFulfillment(PaymentTransaction payment, String status, String message) {
        Map<String, Object> metadata = payment.getProviderMetadata() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(payment.getProviderMetadata());
        metadata.put("ebookFulfillmentStatus", status);
        metadata.put("ebookFulfillmentMessage", message);
        metadata.put("ebookFulfillmentAt", Instant.now().toString());
        payment.setProviderMetadata(metadata);
    }

    // VNPAY dùng VND whole amount; không cho fee thập phân để tránh sai số khi nhân 100.
    private Long toWholeVndAmount(BigDecimal fee) {
        if (fee == null) {
            return 0L;
        }
        return fee.setScale(0, RoundingMode.HALF_UP).longValue();
    }
}
