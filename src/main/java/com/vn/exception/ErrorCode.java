package com.vn.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // ── Auth / Token ──
    INVALID_OR_EXPIRED_TOKEN(HttpStatus.UNAUTHORIZED, "INVALID_OR_EXPIRED_TOKEN", "Token không hợp lệ hoặc đã hết hạn"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Email hoặc mật khẩu không đúng"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Bạn chưa được xác thực hoặc phiên đăng nhập đã hết hạn"),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Bạn không có quyền truy cập tài nguyên này"),
    EMAIL_NOT_VERIFIED(HttpStatus.FORBIDDEN, "EMAIL_NOT_VERIFIED", "Tài khoản chưa được xác nhận email"),
    ACCOUNT_INACTIVE(HttpStatus.FORBIDDEN, "ACCOUNT_INACTIVE", "Tài khoản đã bị vô hiệu hóa"),
    VERIFICATION_TOKEN_EXPIRED(HttpStatus.BAD_REQUEST, "VERIFICATION_TOKEN_EXPIRED", "Link xác nhận đã hết hạn, vui lòng yêu cầu gửi lại"),
    EMAIL_ALREADY_VERIFIED(HttpStatus.CONFLICT, "EMAIL_ALREADY_VERIFIED", "Tài khoản đã được xác nhận email"),
    EMAIL_RESEND_COOLDOWN(HttpStatus.TOO_MANY_REQUESTS, "EMAIL_RESEND_COOLDOWN", "Vui lòng chờ trước khi gửi lại email xác thực"),
    EMAIL_RESEND_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "EMAIL_RESEND_LIMIT_EXCEEDED", "Bạn đã vượt quá số lần gửi lại email xác thực trong ngày"),

    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Dữ liệu không hợp lệ"),
    ILLEGAL_ARGUMENT(HttpStatus.BAD_REQUEST, "ILLEGAL_ARGUMENT", "Tham số không hợp lệ"),
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Yêu cầu không hợp lệ"),
    MISSING_REQUEST_PARAMETER(HttpStatus.BAD_REQUEST, "MISSING_REQUEST_PARAMETER", "Thiếu tham số bắt buộc"),
    MALFORMED_JSON(HttpStatus.BAD_REQUEST, "MALFORMED_JSON", "Payload không đúng định dạng JSON hợp lệ"),
    CONSTRAINT_VIOLATION(HttpStatus.BAD_REQUEST, "CONSTRAINT_VIOLATION", "Dữ liệu không thỏa điều kiện ràng buộc"),

    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "EMAIL_ALREADY_EXISTS", "Email đã được sử dụng"),
    DUPLICATE_RESOURCE(HttpStatus.CONFLICT, "DUPLICATE_RESOURCE", "Tài nguyên đã tồn tại"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Không tìm thấy tài nguyên"),
    UNSUPPORTED_PAYMENT_PROVIDER(HttpStatus.BAD_REQUEST, "UNSUPPORTED_PAYMENT_PROVIDER", "Cổng thanh toán không được hỗ trợ"),
    MISSING_IDEMPOTENCY_KEY(HttpStatus.BAD_REQUEST, "MISSING_IDEMPOTENCY_KEY", "Thiếu Idempotency-Key cho thao tác này"),
    IDEMPOTENCY_REQUEST_PROCESSING(HttpStatus.CONFLICT, "IDEMPOTENCY_REQUEST_PROCESSING", "Request với Idempotency-Key này đang được xử lý"),
    EBOOK_NOT_FOUND(HttpStatus.NOT_FOUND, "EBOOK_NOT_FOUND", "Không tìm thấy ebook"),
    EBOOK_NOT_AVAILABLE(HttpStatus.CONFLICT, "EBOOK_NOT_AVAILABLE", "Ebook hiện không khả dụng"),
    EBOOK_DOES_NOT_REQUIRE_PAYMENT(HttpStatus.CONFLICT, "EBOOK_DOES_NOT_REQUIRE_PAYMENT", "Ebook này không cần thanh toán"),
    EBOOK_REQUIRES_PAYMENT(HttpStatus.CONFLICT, "EBOOK_REQUIRES_PAYMENT", "Ebook này yêu cầu thanh toán để mượn"),
    EBOOK_ALREADY_BORROWED(HttpStatus.CONFLICT, "EBOOK_ALREADY_BORROWED", "Bạn đã có quyền đọc ebook này"),
    EBOOK_LOAN_REQUIRED(HttpStatus.FORBIDDEN, "EBOOK_LOAN_REQUIRED", "Bạn cần mượn ebook trước khi đọc"),
    EBOOK_LOAN_EXPIRED(HttpStatus.CONFLICT, "EBOOK_LOAN_EXPIRED", "Quyền đọc ebook đã hết hạn"),
    READING_SESSION_REQUIRED(HttpStatus.BAD_REQUEST, "READING_SESSION_REQUIRED", "Thiếu phiên đọc ebook"),
    READING_SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "READING_SESSION_NOT_FOUND", "Không tìm thấy phiên đọc ebook"),
    READING_SESSION_NOT_ACTIVE(HttpStatus.GONE, "READING_SESSION_NOT_ACTIVE", "Phiên đọc ebook không còn hiệu lực"),
    READING_SESSION_FORBIDDEN(HttpStatus.FORBIDDEN, "READING_SESSION_FORBIDDEN", "Phiên đọc ebook không thuộc tài khoản hiện tại"),
    PAYMENT_ALREADY_PENDING(HttpStatus.CONFLICT, "PAYMENT_ALREADY_PENDING", "Bạn đã có giao dịch thanh toán đang chờ xử lý cho ebook này"),
    PAYMENT_ALREADY_SUCCESS(HttpStatus.CONFLICT, "PAYMENT_ALREADY_SUCCESS", "Bạn đã thanh toán ebook này"),
    EBOOK_LICENSE_NOT_AVAILABLE(HttpStatus.CONFLICT, "EBOOK_LICENSE_NOT_AVAILABLE", "Ebook đã hết license đọc hiện tại"),
    PAYMENT_PROVIDER_ERROR(HttpStatus.BAD_GATEWAY, "PAYMENT_PROVIDER_ERROR", "Cổng thanh toán đang gặp lỗi, vui lòng thử lại sau"),
    INVALID_PAYMENT_LOCALE(HttpStatus.BAD_REQUEST, "INVALID_PAYMENT_LOCALE", "Ngôn ngữ thanh toán không hợp lệ"),
    INVALID_PAYMENT_BANK_CODE(HttpStatus.BAD_REQUEST, "INVALID_PAYMENT_BANK_CODE", "Mã phương thức/ngân hàng thanh toán không hợp lệ"),
    INVALID_MEDIA_FILE(HttpStatus.BAD_REQUEST, "INVALID_MEDIA_FILE", "File media không hợp lệ"),
    INVALID_IMAGE_FILE(HttpStatus.BAD_REQUEST, "INVALID_IMAGE_FILE", "File ảnh không hợp lệ. Chỉ hỗ trợ JPG, PNG hoặc WEBP và dung lượng tối đa 5MB"),
    INVALID_EBOOK_FILE(HttpStatus.BAD_REQUEST, "INVALID_EBOOK_FILE", "File ebook không hợp lệ. Chỉ hỗ trợ PDF và dung lượng tối đa 100MB"),
    BOOK_COVER_ALREADY_EXISTS(HttpStatus.CONFLICT, "BOOK_COVER_ALREADY_EXISTS", "Sách đã có ảnh bìa chính, vui lòng dùng API cập nhật ảnh bìa"),
    CLOUDINARY_CONFIG_MISSING(HttpStatus.INTERNAL_SERVER_ERROR, "CLOUDINARY_CONFIG_MISSING", "Cấu hình Cloudinary chưa đầy đủ"),
    CLOUDINARY_UPLOAD_FAILED(HttpStatus.BAD_GATEWAY, "CLOUDINARY_UPLOAD_FAILED", "Không thể upload ảnh lên Cloudinary, vui lòng thử lại sau"),
    CLOUDINARY_DELETE_FAILED(HttpStatus.BAD_GATEWAY, "CLOUDINARY_DELETE_FAILED", "Không thể xóa ảnh trên Cloudinary, hệ thống sẽ thử lại sau"),
    CLOUDINARY_SIGNED_URL_FAILED(HttpStatus.BAD_GATEWAY, "CLOUDINARY_SIGNED_URL_FAILED", "Không thể tạo URL đọc ebook, vui lòng thử lại sau"),
    BOOK_HAS_ACTIVE_COPIES(HttpStatus.CONFLICT, "BOOK_HAS_ACTIVE_COPIES", "Không thể xóa sách khi còn bản sao đang được mượn hoặc được giữ chỗ"),
    BOOK_COPY_HAS_BORROW_HISTORY(HttpStatus.CONFLICT, "BOOK_COPY_HAS_BORROW_HISTORY", "Không thể xóa bản sao đã có lịch sử mượn sách"),
    BORROWER_MUST_BE_MEMBER(HttpStatus.BAD_REQUEST, "BORROWER_MUST_BE_MEMBER", "Chỉ tài khoản bạn đọc mới được đứng tên mượn sách"),
    MEMBER_NOT_ACTIVE(HttpStatus.FORBIDDEN, "MEMBER_NOT_ACTIVE", "Tài khoản bạn đọc chưa hoạt động hoặc đã bị khóa"),
    MEMBERSHIP_EXPIRED(HttpStatus.FORBIDDEN, "MEMBERSHIP_EXPIRED", "Thẻ thư viện đã hết hạn"),
    BORROW_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "BORROW_LIMIT_EXCEEDED", "Bạn đọc đã đạt giới hạn số sách đang mượn"),
    MEMBER_ALREADY_BORROWED_BOOK(HttpStatus.CONFLICT, "MEMBER_ALREADY_BORROWED_BOOK", "Bạn đọc đang mượn một bản khác của đầu sách này"),
    MEMBER_HAS_OVERDUE_ITEMS(HttpStatus.CONFLICT, "MEMBER_HAS_OVERDUE_ITEMS", "Bạn đọc đang có sách quá hạn"),
    BOOK_COPY_NOT_AVAILABLE(HttpStatus.CONFLICT, "BOOK_COPY_NOT_AVAILABLE", "Bản sách này hiện không khả dụng để mượn"),
    BOOK_AVAILABLE_ON_SHELF(HttpStatus.CONFLICT, "BOOK_AVAILABLE_ON_SHELF", "Sách hiện vẫn còn bản trên kệ, chỉ được đặt giữ chỗ khi đã hết bản khả dụng"),
    ACTIVE_BORROW_NOT_FOUND(HttpStatus.NOT_FOUND, "ACTIVE_BORROW_NOT_FOUND", "Không tìm thấy lượt mượn đang mở cho bản sách này"),
    BORROW_NOT_RENEWABLE(HttpStatus.CONFLICT, "BORROW_NOT_RENEWABLE", "Lượt mượn này không đủ điều kiện gia hạn"),
    RENEWAL_BLOCKED_BY_HOLD(HttpStatus.CONFLICT, "RENEWAL_BLOCKED_BY_HOLD", "Không thể gia hạn vì đang có bạn đọc khác đặt giữ chỗ sách này"),
    HOLD_ALREADY_EXISTS(HttpStatus.CONFLICT, "HOLD_ALREADY_EXISTS", "Bạn đã có lượt giữ chỗ đang hoạt động cho sách này"),
    HOLD_NOT_ACTIVE(HttpStatus.CONFLICT, "HOLD_NOT_ACTIVE", "Lượt giữ chỗ này không còn hoạt động"),
    HOLD_NOT_READY_FOR_PICKUP(HttpStatus.CONFLICT, "HOLD_NOT_READY_FOR_PICKUP", "Lượt giữ chỗ chưa sẵn sàng để checkout tại quầy"),
    HOLD_ASSIGNED_COPY_INVALID(HttpStatus.CONFLICT, "HOLD_ASSIGNED_COPY_INVALID", "Bản sách được gán cho lượt giữ chỗ không hợp lệ"),
    IDEMPOTENCY_KEY_REQUIRED(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "Thiếu Idempotency-Key cho thao tác này"),
    IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST", "Idempotency-Key đã được dùng cho request khác"),
    REQUEST_ALREADY_PROCESSING(HttpStatus.CONFLICT, "REQUEST_ALREADY_PROCESSING", "Request với Idempotency-Key này đang được xử lý"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "Phương thức HTTP không được hỗ trợ"),
    DATA_INTEGRITY_VIOLATION(HttpStatus.CONFLICT, "DATA_INTEGRITY_VIOLATION", "Dữ liệu xung đột với ràng buộc hệ thống"),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR", "Hệ thống đang gặp lỗi, vui lòng thử lại sau");

    private final HttpStatus status;
    private final String code;
    private final String message;
}

