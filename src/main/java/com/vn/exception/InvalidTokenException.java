package com.vn.exception;

public class InvalidTokenException extends AppException {

    public InvalidTokenException() {
        super(ErrorCode.INVALID_OR_EXPIRED_TOKEN);
    }
}
