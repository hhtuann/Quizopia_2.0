package com.quizopia.identity.api.auth;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Quizopia API error envelope")
public record ApiErrorResponse(
        @Schema(example = "AUTH_VERIFICATION_FAILED") String code,
        @Schema(example = "Email verification failed.") String message,
        @Schema(example = "400") int status,
        @Schema(example = "/api/auth/email-verification/confirm") String path,
        @Schema(nullable = true) String traceId) {}
