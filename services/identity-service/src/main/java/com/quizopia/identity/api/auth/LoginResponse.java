package com.quizopia.identity.api.auth;

import com.quizopia.identity.security.token.IssuedUserAccessToken;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public record LoginResponse(
        @Schema(description = "Quizopia RS256 access JWT") String accessToken,
        @Schema(example = "Bearer") String tokenType,
        @Schema(description = "Remaining access-token lifetime in seconds", example = "300") long expiresIn) {
    private static final String BEARER = "Bearer";

    static LoginResponse from(IssuedUserAccessToken token, Instant responseTime) {
        Objects.requireNonNull(token, "token");
        long remainingSeconds = Duration.between(
                        Objects.requireNonNull(responseTime, "responseTime"), token.expiresAt())
                .getSeconds();
        if (remainingSeconds <= 0) {
            throw new IllegalStateException("Issued access token has no remaining lifetime");
        }
        return new LoginResponse(token.value(), BEARER, remainingSeconds);
    }

    @Override
    public String toString() {
        return "LoginResponse{accessToken=[REDACTED], tokenType=" + tokenType + ", expiresIn=" + expiresIn + "}";
    }
}
