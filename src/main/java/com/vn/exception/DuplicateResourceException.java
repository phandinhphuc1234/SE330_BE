package com.vn.exception;

public class DuplicateResourceException extends AppException {

    public DuplicateResourceException() {
        super(ErrorCode.DUPLICATE_RESOURCE);
    }

    public DuplicateResourceException(String message) {
        super(ErrorCode.DUPLICATE_RESOURCE.getStatus(), ErrorCode.DUPLICATE_RESOURCE.getCode(), message);
    }
}

