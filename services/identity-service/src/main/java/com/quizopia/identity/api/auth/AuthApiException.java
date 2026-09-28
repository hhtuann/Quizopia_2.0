package com.quizopia.identity.api.auth;

import java.util.Objects;
import org.springframework.http.HttpStatus;

final class AuthApiException extends RuntimeException {
    private final String code;
    private final HttpStatus status;

    AuthApiException(String code, String safeMessage, HttpStatus status) {
        super(Objects.requireNonNull(safeMessage, "safeMessage"), null, false, false);
        this.code = Objects.requireNonNull(code, "code");
        this.status = Objects.requireNonNull(status, "status");
    }

    String code() {
        return code;
    }

    HttpStatus status() {
        return status;
    }
}
