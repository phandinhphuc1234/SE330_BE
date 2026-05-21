package com.vn.logging;

// Danh sach eventType dung cho structured logging.
// Comment duoi day chi de chia nhom doc nhanh, khong anh huong gia tri enum.
public enum LogEvent {
    // HTTP request / security filter
    HTTP_REQUEST_COMPLETED,
    JWT_AUTHENTICATION,

    // Authentication / session lifecycle
    REGISTER,
    VERIFY_EMAIL,
    LOGIN,
    REFRESH_TOKEN,
    RESEND_VERIFICATION_EMAIL,
    LOGOUT,

    // Email delivery
    SEND_VERIFICATION_EMAIL,

    // Member profile
    GET_MY_PROFILE,
    UPDATE_MY_PROFILE,

    // Catalog management
    CREATE_BOOK,
    UPDATE_BOOK,
    DELETE_BOOK,
    UPDATE_BOOK_AUTHORS,
    CREATE_BOOK_COPY,
    UPDATE_BOOK_COPY,
    DELETE_BOOK_COPY,
    CREATE_AUTHOR,
    UPDATE_AUTHOR,
    CREATE_CATEGORY,
    UPDATE_CATEGORY,

    // CSV import
    IMPORT_BOOKS,

    // Circulation: checkout / checkin / renewal
    BORROW_BOOK,
    RETURN_BOOK,
    RENEW_BORROW,

    // Holds / reservation queue
    CREATE_HOLD,
    CANCEL_HOLD,
    HOLD_READY_FOR_PICKUP,
    CHECKOUT_HOLD,

    // Scheduled jobs: auto-renewal
    AUTO_RENEWAL_JOB,
    AUTO_RENEWAL_ATTEMPT,
    SEND_AUTO_RENEWAL_EMAIL,

    // Scheduled jobs: overdue marking
    MARK_OVERDUE_JOB,
    MARK_BORROW_OVERDUE,

    // Scheduled jobs: due-soon reminder
    DUE_SOON_REMINDER_JOB,
    CREATE_DUE_SOON_REMINDER,
    SEND_DUE_SOON_REMINDER_EMAIL,

    // Scheduled jobs: expired ready holds
    EXPIRE_READY_HOLDS_JOB,
    EXPIRE_READY_HOLD,

    // Error handling
    BUSINESS_EXCEPTION,
    VALIDATION_FAILED,
    ILLEGAL_ARGUMENT,
    ACCESS_DENIED,
    AUTHENTICATION_FAILED,
    MALFORMED_JSON,
    MISSING_REQUEST_PARAMETER,
    METHOD_NOT_ALLOWED,
    RESOURCE_NOT_FOUND,
    CONSTRAINT_VIOLATION,
    DATA_INTEGRITY_VIOLATION,
    UNHANDLED_EXCEPTION
}
