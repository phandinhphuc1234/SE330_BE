package com.vn.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

// exception/AppException.java
// Base exception — mọi business exception đều extends cái này
// RuntimeException la RuntimeException trong Java là
// các ngoại lệ (exception) xảy ra trong quá trình chương trình đang chạy (runtime),
// thường do lỗi logic của lập trình viên (như chia cho 0, truy cập sai chỉ số mảng).
// Đây là các unchecked exception, nghĩa là trình biên dịch
// không bắt buộc bạn phải dùng try-catch hoặc throws để xử lý chúng

@Getter
public class AppException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public AppException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public AppException(ErrorCode errorCode) {
        this(errorCode.getStatus(), errorCode.getCode(), errorCode.getMessage());
    }
}
