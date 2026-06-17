package com.vn.service.payment.business;

import com.vn.entity.BorrowRecord;
import com.vn.entity.PaymentTransaction;
import com.vn.enums.PaymentPurpose;
import com.vn.enums.PaymentTargetType;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.repository.BorrowRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class FinePaymentApplier implements PaymentBusinessApplier {

    private final BorrowRecordRepository borrowRecordRepository;

    @Override
    public boolean supports(PaymentPurpose purpose, PaymentTargetType targetType) {
        return purpose == PaymentPurpose.OVERDUE_FINE && targetType == PaymentTargetType.BORROW_RECORD;
    }

    @Override
    public PayableTarget validatePayableTarget(Long memberId, Long targetId) {
        BorrowRecord borrow = borrowRecordRepository.findById(targetId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));

        if (!borrow.getMember().getId().equals(memberId)) {
            throw new AppException(ErrorCode.ACCESS_DENIED);
        }

        if (safeFineAmount(borrow).compareTo(BigDecimal.ZERO) <= 0) {
            throw new AppException(ErrorCode.FINE_NOT_FOUND);
        }

        if (borrow.getFinePaidAt() != null) {
            throw new AppException(ErrorCode.PAYMENT_ALREADY_SUCCESS);
        }

        if (borrow.getFineWaivedBy() != null) {
            throw new AppException(ErrorCode.BAD_REQUEST); // Fine already waived
        }

        Long amount = toWholeVndAmount(borrow.getFineAmount());
        
        return new PayableTarget(
                PaymentPurpose.OVERDUE_FINE,
                PaymentTargetType.BORROW_RECORD,
                borrow.getId(),
                amount,
                "VND"
        );
    }

    @Override
    public void applySuccess(PaymentTransaction payment) {
        if (payment == null || payment.getId() == null) {
            return;
        }

        BorrowRecord borrow = borrowRecordRepository.findById(payment.getTargetId())
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));

        // Idempotency: skip if already paid
        if (borrow.getFinePaidAt() != null) {
            return;
        }

        borrow.setFinePaidAt(Instant.now());
        borrowRecordRepository.save(borrow);

        markFulfillment(payment, "FULFILLED", "Overdue fine paid successfully");
    }

    private BigDecimal safeFineAmount(BorrowRecord borrow) {
        return borrow.getFineAmount() == null ? BigDecimal.ZERO : borrow.getFineAmount();
    }

    private Long toWholeVndAmount(BigDecimal fine) {
        if (fine == null) {
            return 0L;
        }
        return fine.setScale(0, RoundingMode.HALF_UP).longValue();
    }

    private void markFulfillment(PaymentTransaction payment, String status, String message) {
        Map<String, Object> metadata = payment.getProviderMetadata() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(payment.getProviderMetadata());
        metadata.put("fineFulfillmentStatus", status);
        metadata.put("fineFulfillmentMessage", message);
        metadata.put("fineFulfillmentAt", Instant.now().toString());
        payment.setProviderMetadata(metadata);
    }
}
