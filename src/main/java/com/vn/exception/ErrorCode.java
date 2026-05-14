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
    BOOK_HAS_ACTIVE_COPIES(HttpStatus.CONFLICT, "BOOK_HAS_ACTIVE_COPIES", "Không thể xóa sách khi còn bản sao đang được mượn hoặc được giữ chỗ"),
    BOOK_COPY_HAS_BORROW_HISTORY(HttpStatus.CONFLICT, "BOOK_COPY_HAS_BORROW_HISTORY", "Không thể xóa bản sao đã có lịch sử mượn sách"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "Phương thức HTTP không được hỗ trợ"),
    DATA_INTEGRITY_VIOLATION(HttpStatus.CONFLICT, "DATA_INTEGRITY_VIOLATION", "Dữ liệu xung đột với ràng buộc hệ thống"),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR", "Hệ thống đang gặp lỗi, vui lòng thử lại sau");

    private final HttpStatus status;
    private final String code;
    private final String message;
}

