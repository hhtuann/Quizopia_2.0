package com.quizopia.identity.api.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Objects;

@Schema(description = "Stable successful authentication-operation outcome")
public record AuthStatusResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) Status status) {
    public AuthStatusResponse {
        Objects.requireNonNull(status, "status");
    }

    public enum Status {
        VERIFICATION_REQUIRED,
        VERIFICATION_REQUEST_ACCEPTED
    }
}
