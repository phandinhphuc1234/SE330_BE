package com.vn.repository.projection;

import java.time.Instant;

public interface PaymentReceiptRowProjection {

    Long getPaymentId();

    String getReceiptNumber();

    String getPaymentCode();

    Long getMemberId();

    String getMemberName();

    String getMemberEmail();

    String getProvider();

    String getProviderTransactionId();

    String getProviderResponseCode();

    String getProviderTransactionStatus();

    String getPurpose();

    String getTargetType();

    Long getTargetId();

    String getItemTitle();

    Long getAmount();

    String getCurrency();

    String getStatus();

    Instant getPaidAt();

    Instant getExpiredAt();

    Instant getCreatedAt();

    Instant getUpdatedAt();
}
