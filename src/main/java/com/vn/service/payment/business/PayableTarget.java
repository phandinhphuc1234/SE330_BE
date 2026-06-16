package com.vn.service.payment.business;

import com.vn.enums.PaymentPurpose;
import com.vn.enums.PaymentTargetType;

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
