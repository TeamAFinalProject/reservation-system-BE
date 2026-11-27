package com.asbg.backend.domain.member.domain;

import com.asbg.backend.global.exception.BaseErrorCode;
import org.springframework.http.HttpStatus;

public enum MemberErrorCode implements BaseErrorCode {
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "MEMBER_401_1", "아이디 또는 비밀번호가 올바르지 않습니다."),
    INVALID_WITHDRAWAL_PASSWORD(HttpStatus.UNAUTHORIZED, "MEMBER_401_2", "현재 비밀번호가 올바르지 않습니다."),
    DUPLICATE_USERNAME(HttpStatus.CONFLICT, "MEMBER_409_1", "이미 존재하는 아이디입니다."),
    ACTIVE_SESSION(HttpStatus.CONFLICT, "MEMBER_409_2", "이미 활성 로그인 세션이 있습니다."),
    WITHDRAWAL_CONFLICT(HttpStatus.CONFLICT, "MEMBER_409_3", "연결된 데이터가 있어 회원을 삭제할 수 없습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    MemberErrorCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }
}
