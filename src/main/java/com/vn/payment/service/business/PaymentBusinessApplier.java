package com.vn.payment.service.business;

import com.vn.payment.entity.PaymentTransaction;
import com.vn.payment.enums.PaymentPurpose;
import com.vn.payment.enums.PaymentTargetType;

public interface PaymentBusinessApplier {

    // Xác định implementation nào chịu trách nhiệm cho purpose/targetType.
    boolean supports(PaymentPurpose purpose, PaymentTargetType targetType);

    // Validate target và trả amount/currency, không gọi provider và không apply payment success.
    PayableTarget validatePayableTarget(Long memberId, Long targetId);

    // Áp dụng side-effect nghiệp vụ sau khi payment đã được IPN xác nhận SUCCESS.
    void applySuccess(PaymentTransaction payment);
}
