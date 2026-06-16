package com.vn.service.payment.business;

import com.vn.enums.PaymentPurpose;
import com.vn.enums.PaymentTargetType;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
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
