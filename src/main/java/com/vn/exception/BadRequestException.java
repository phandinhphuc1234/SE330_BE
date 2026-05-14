package com.vn.exception;

public class BadRequestException extends AppException {

    public BadRequestException() {
        super(ErrorCode.BAD_REQUEST);
    }

    public BadRequestException(String message) {
        super(ErrorCode.BAD_REQUEST.getStatus(), ErrorCode.BAD_REQUEST.getCode(), message);
    }
}

