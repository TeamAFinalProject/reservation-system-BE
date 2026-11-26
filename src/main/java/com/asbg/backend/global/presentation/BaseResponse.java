package com.asbg.backend.global.presentation;

import com.asbg.backend.global.exception.BaseErrorCode;

public record BaseResponse<T>(boolean success, String code, String message, T data) {
    public static <T> BaseResponse<T> success(T data) {
        return new BaseResponse<>(true, "COMMON_SUCCESS", "요청에 성공했습니다.", data);
    }

    public static <T> BaseResponse<T> failure(BaseErrorCode errorCode, T data) {
        return new BaseResponse<>(false, errorCode.code(), errorCode.message(), data);
    }
}
