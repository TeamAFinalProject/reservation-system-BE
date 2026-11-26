package com.asbg.backend.global.exception;

import org.springframework.http.HttpStatusCode;

public interface BaseErrorCode {
    HttpStatusCode status();

    String code();

    String message();
}
