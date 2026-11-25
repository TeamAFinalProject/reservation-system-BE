package com.asbg.backend.global.exception;

public class BusinessException extends RuntimeException {
    private final BaseErrorCode errorCode;

    public BusinessException(BaseErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    public BaseErrorCode errorCode() {
        return errorCode;
    }
}
