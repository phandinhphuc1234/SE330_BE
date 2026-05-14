package com.vn.exception;
// exception/EmailAlreadyExistsException.java
// Kế thừa lớp AppException
public class EmailAlreadyExistsException extends AppException {

    public EmailAlreadyExistsException(String email) {
        super(
                ErrorCode.EMAIL_ALREADY_EXISTS.getStatus(),
                ErrorCode.EMAIL_ALREADY_EXISTS.getCode(),
                "Email '" + email + "' đã được sử dụng"
        );
    }
}
