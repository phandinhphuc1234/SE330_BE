package com.vn.repository.projection;

import java.math.BigDecimal;
import java.time.Instant;

public interface StaffLoanRowProjection {

    Long getBorrowId();

    Long getMemberId();

    String getMemberName();

    String getMemberEmail();

    Long getBookId();

    String getBookTitle();

    Long getBookCopyId();

    String getItemBarcode();

    String getCopyStatus();

    Instant getBorrowedAt();

    Instant getDueDate();

    Instant getReturnedAt();

    String getStatus();

    Integer getRenewCount();

    Integer getMaxRenewals();

    BigDecimal getFineAmount();

    String getFineStatus();

    Boolean getOverdue();

    Long getDaysOverdue();

    String getLoanType();

    Long getEbookLoanId();

    Long getBookEbookId();

    Long getPaymentId();

    Instant getExpiredAt();
}
