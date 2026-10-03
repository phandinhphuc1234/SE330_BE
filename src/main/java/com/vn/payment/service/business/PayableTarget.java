package com.vn.payment.service.business;

import com.vn.payment.enums.PaymentPurpose;
import com.vn.payment.enums.PaymentTargetType;

/**
 * Target nghiệp vụ đã qua validate và đã được backend tính amount/currency.
 */
public record PayableTarget(
        PaymentPurpose purpose,
        PaymentTargetType targetType,
        Long targetId,
        Long amount,
        String currency
) {
}
