package com.playbackgate.common.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "회원을 찾을 수 없습니다."),
    MEMBER_NOT_ACTIVE(HttpStatus.FORBIDDEN, "활성 회원이 아닙니다."),
    CONTENT_NOT_FOUND(HttpStatus.NOT_FOUND, "콘텐츠를 찾을 수 없습니다."),
    CONTENT_NOT_AVAILABLE(HttpStatus.FORBIDDEN, "현재 재생할 수 없는 콘텐츠입니다."),
    SUBSCRIPTION_NOT_FOUND(HttpStatus.NOT_FOUND, "이용권을 찾을 수 없습니다."),
    SUBSCRIPTION_NOT_ACTIVE(HttpStatus.FORBIDDEN, "이용권이 활성 상태가 아닙니다."),
    SUBSCRIPTION_EXPIRED(HttpStatus.FORBIDDEN, "이용권이 만료되었습니다."),
    PLAN_NOT_ALLOWED(HttpStatus.FORBIDDEN, "콘텐츠 재생에 필요한 이용권 등급이 아닙니다."),
    AGE_RESTRICTED(HttpStatus.FORBIDDEN, "연령 제한으로 재생할 수 없습니다."),
    PLAYBACK_SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "재생 세션을 찾을 수 없습니다."),
    CONCURRENT_PLAYBACK_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "동시 재생 가능 수를 초과했습니다.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }
}
