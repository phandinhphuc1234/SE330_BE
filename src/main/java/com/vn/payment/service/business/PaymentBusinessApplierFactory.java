package com.vn.payment.service.business;

import com.vn.payment.enums.PaymentPurpose;
import com.vn.payment.enums.PaymentTargetType;
import com.vn.shared.exception.AppException;
import com.vn.shared.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PaymentBusinessApplierFactory {

    private final List<PaymentBusinessApplier> appliers;

    public PaymentBusinessApplierFactory(List<PaymentBusinessApplier> appliers) {
        this.appliers = appliers;
    }

    // Tách PaymentService khỏi chi tiết nghiệp vụ ebook/fine/subscription.
    public PaymentBusinessApplier get(PaymentPurpose purpose, PaymentTargetType targetType) {
        return appliers.stream()
                .filter(applier -> applier.supports(purpose, targetType))
                .findFirst()
                .orElseThrow(() -> new AppException(ErrorCode.BAD_REQUEST));
    }
}
