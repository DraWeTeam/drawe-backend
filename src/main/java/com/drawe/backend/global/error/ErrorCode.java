package com.drawe.backend.global.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "토큰 없음 / 만료"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "본인 리소스가 아님"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "리소스 없음"),
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "요청 파라미터 오류"),
    ALREADY_EXISTS(HttpStatus.CONFLICT, "중복 데이터"),
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 존재하는 이메일입니다"),
    INVALID_PASSWORD(HttpStatus.BAD_REQUEST, "비밀번호가 일치하지 않습니다"),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다"),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 토큰입니다"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류");

    private final HttpStatus status;
    private final String message;

    public String getCode() {
        return this.name();
    }
}
