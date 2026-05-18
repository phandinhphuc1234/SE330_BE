package com.vn.enums;

public enum AutoRenewalResultCode {
    SUCCESS("Auto-renewal completed successfully"),
    BORROW_NOT_FOUND("Borrow record was not found"),
    BORROW_NOT_RENEWABLE_STATUS("Borrow status is not renewable"),
    MAX_RENEWALS_REACHED("Maximum renewals reached"),
    BLOCKED_BY_HOLD("Book has an active hold queue"),
    MEMBER_NOT_ACTIVE("Member account is not active"),
    MEMBERSHIP_EXPIRED("Membership is expired"),
    BORROWER_MUST_BE_MEMBER("Borrower must be a member account"),
    BORROW_OVERDUE("Borrow is already overdue"),
    BOOK_COPY_NOT_BORROWED("Book copy is no longer borrowed"),
    BOOK_DELETED("Book has been deleted"),
    SYSTEM_ERROR("Unexpected system error");

    private final String defaultMessage;

    AutoRenewalResultCode(String defaultMessage) {
        this.defaultMessage = defaultMessage;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
