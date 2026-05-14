package com.vn.exception;
/**
 * Exception dùng khi không tìm thấy resource trong hệ thống.
 *
 * Ví dụ:
 * - Không tìm thấy User theo id
 * - Không tìm thấy Product
 * - Không tìm thấy Order
 *
 * Đây là một dạng Business Exception, sẽ được GlobalExceptionHandler bắt lại
 * và trả về response chuẩn cho client.
 */
public class ResourceNotFoundException extends AppException {

    public ResourceNotFoundException() {
        super(ErrorCode.RESOURCE_NOT_FOUND);
    }

    public ResourceNotFoundException(String message) {
        super(ErrorCode.RESOURCE_NOT_FOUND.getStatus(), ErrorCode.RESOURCE_NOT_FOUND.getCode(), message);
    }
}

